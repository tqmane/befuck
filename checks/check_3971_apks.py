"""Verify the full original BeReal 3.97.1 multidex APKS without bundling its DEX.

Usage: python3 checks/check_3971_apks.py /path/to/com.bereal.ft_3.97.1.apks
Also accepts an extracted base.apk. Python standard library only.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile

INVENTORY = Path(__file__).with_name("dex-inventory-3599414.json")


def u16(blob, offset):
    return struct.unpack_from("<H", blob, offset)[0]


def u32(blob, offset):
    return struct.unpack_from("<I", blob, offset)[0]


def utf8_len(blob, pos):
    result = blob[pos]
    pos += 1
    if result & 0x80:
        result = ((result & 0x7F) << 8) | blob[pos]
        pos += 1
    return result, pos


def utf16_len(blob, pos):
    result = u16(blob, pos)
    pos += 2
    if result & 0x8000:
        result = ((result & 0x7FFF) << 16) | u16(blob, pos)
        pos += 2
    return result, pos


def manifest_identity(blob):
    assert blob[:4] == b"\x03\x00\x08\x00", "Not an Android binary XML manifest"
    strings, offset = [], 8
    while offset < u32(blob, 4):
        kind, header, size = struct.unpack_from("<HHI", blob, offset)
        assert size >= header >= 8, "Invalid binary XML chunk"
        if kind == 1:  # String pool
            count, flags = u32(blob, offset + 8), u32(blob, offset + 16)
            start = offset + u32(blob, offset + 20)
            for index in range(count):
                pos = start + u32(blob, offset + header + 4 * index)
                if flags & 0x100:
                    _, pos = utf8_len(blob, pos)
                    length, pos = utf8_len(blob, pos)
                    strings.append(blob[pos:pos + length].decode("utf-8"))
                else:
                    length, pos = utf16_len(blob, pos)
                    strings.append(blob[pos:pos + length * 2].decode("utf-16le"))
        if kind == 0x102 and strings and strings[u32(blob, offset + 20)] == "manifest":
            out = {}
            for index in range(u16(blob, offset + 28)):
                pos = offset + 16 + u16(blob, offset + 24) + 20 * index
                key = strings[u32(blob, pos + 4)]
                typ, raw = blob[pos + 15], u32(blob, pos + 16)
                if key in ("package", "versionName", "versionCode"):
                    out[key] = strings[raw] if typ == 3 else raw
            return out
        offset += size
    raise AssertionError("Manifest start element not found")


def certificate_digest(apk_bytes):
    # APK Signature Scheme v2/v3 block precedes the ZIP central directory.
    eocd = apk_bytes.rfind(b"PK\x05\x06")
    assert eocd >= 0
    cd = u32(apk_bytes, eocd + 16)
    assert apk_bytes[cd - 16:cd] == b"APK Sig Block 42"
    size = struct.unpack_from("<Q", apk_bytes, cd - 24)[0]
    pos = cd - (size + 8) + 8
    end = cd - 24

    def take(blob, at):
        n = u32(blob, at)
        return blob[at + 4:at + 4 + n], at + 4 + n

    digests = set()
    while pos < end:
        size = struct.unpack_from("<Q", apk_bytes, pos)[0]
        kind = u32(apk_bytes, pos + 8)
        item = apk_bytes[pos + 12:pos + 8 + size]
        if kind in (0x7109871A, 0xF05368C0):
            signers, _ = take(item, 0)
            signer, _ = take(signers, 0)
            signed_data, _ = take(signer, 0)
            _, next_pos = take(signed_data, 0)
            certificates, _ = take(signed_data, next_pos)
            certificate, _ = take(certificates, 0)
            digests.add(base64.b64encode(hashlib.sha256(certificate).digest()).decode("ascii"))
        pos += size + 8
    assert len(digests) == 1, f"Ambiguous or missing certificates: {digests}"
    return digests.pop()


def check(path):
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    archive = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(archive)) as top:
        nested = [name for name in top.namelist() if name.endswith(".apk")]
        if nested:
            assert "base.apk" in nested, "APKS has no base.apk"
            base_bytes = top.read("base.apk")
        else:
            base_bytes = archive
    found = {}
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as apk:
        identity = manifest_identity(apk.read("AndroidManifest.xml"))
        assert identity == {
            "package": inventory["package"],
            "versionName": inventory["version_name"],
            "versionCode": inventory["version_code"],
        }, identity
        for name in apk.namelist():
            if re.fullmatch(r"classes(?:[2-9]|[1-9]\d+)?\.dex", name):
                assert name not in found, name
                found[name] = apk.read(name)

    if nested:
        with zipfile.ZipFile(io.BytesIO(archive)) as apks:
            for split in nested:
                if split == "base.apk":
                    continue
                with zipfile.ZipFile(io.BytesIO(apks.read(split))) as apk:
                    assert not any(
                        re.fullmatch(r"classes(?:[2-9]|[1-9]\d+)?\.dex", name)
                        for name in apk.namelist()
                    ), f"Unexpected additional DEX in {split}"

    assert set(found) == {entry["name"] for entry in inventory["dex"]}, "DEX inventory mismatch"
    for entry in inventory["dex"]:
        name, data = entry["name"], found[entry["name"]]
        assert data[:4] == b"dex\n", f"Invalid DEX header: {name}"
        assert u32(data, 32) == entry["bytes"] == len(data), name
        assert u32(data, 96) == entry["class_defs"], f"Class count mismatch: {name}"
        assert hashlib.sha256(data).hexdigest() == entry["sha256"], f"DEX changed: {name}"

    assert certificate_digest(base_bytes) == inventory["signature_certificate_sha256_base64"]
    print(
        f"Verified {len(found)} DEX; {sum(map(len, found.values())):,} bytes; "
        f"{sum(entry['class_defs'] for entry in inventory['dex']):,} defined classes; "
        "original signing certificate"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Original BeReal 3.97.1 APKS or base APK")
    check(parser.parse_args().apk)
