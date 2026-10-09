"""Validate the reviewed 3.97.0 string inventory without a private APK.

Run: python3 checks/check_known_strings.py
Optional full rescan (requires androguard): python3 checks/check_known_strings.py --apk BASE_APK
The rescan reads only the supplied APK; it never installs it or contacts a device.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CATALOG = Path(__file__).with_name("known-strings-3597523.json")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, f"Duplicate inventory key: {key}"
        result[key] = value
    return result


def java_hash(value):
    raw = value.encode("utf-16-be")
    result = 0
    for index in range(0, len(raw), 2):
        result = (31 * result + int.from_bytes(raw[index:index + 2], "big")) & 0xFFFFFFFF
    return result if result < 0x80000000 else result - 0x100000000


def read_pools():
    source = (ROOT / "app/src/main/kotlin/dev/tqmane/befuck/symbols/KnownMappings3970.kt").read_text()
    pools = {}
    for owner, function in re.findall(r'"([^"]+)" to (stringPool\d+)\(\)', source):
        assert owner not in pools, f"Duplicate pool: {owner}"
        block = source.split(f"private fun {function}(): Map<String, String> = mapOf(", 1)[1].split("\n    )", 1)[0]
        values = {}
        for name, literal in re.findall(r'"([^"]+)" to ("(?:[^"\\]|\\.)*")', block):
            assert name not in values, f"Duplicate field: {owner}.{name}"
            values[name] = json.loads(literal.replace(r"\$", "$"))
        pools["L" + owner.replace(".", "/") + ";"] = values
    return source, pools


def validate():
    catalog = json.loads(CATALOG.read_text(), object_pairs_hook=unique_object)
    source, pools = read_pools()
    assert set(pools) == set(catalog["classes"])
    mappings = {}
    for symbol, owner, field in re.findall(r'"([^"]+)" to Pair\("([^"]+)", "([^"]+)"\)', source):
        assert symbol not in mappings, f"Duplicate symbol: {symbol}"
        mappings[symbol] = "L" + owner.replace(".", "/") + ";->" + field
    fields = {}
    for owner, pool in catalog["classes"].items():
        assert set(pools[owner]) == set(pool["fields"]), f"Incomplete pool: {owner}"
        assert sorted(e["initializer_index"] for e in pool["fields"].values()) == list(range(len(pool["fields"])))
        for name, entry in pool["fields"].items():
            field = owner + "->" + name
            value = pools[owner][name]
            fields[field] = dict(entry, value=value)
            assert entry["status"] == "recovered"
            assert hashlib.sha256(value.encode()).hexdigest() == entry["value_sha256"], f"Changed original value: {field}"
            assert not value.startswith("__unresolved_"), field
            if "symbol" in entry:
                assert mappings[entry["symbol"]] == field
            if "java_hash" in entry:
                assert java_hash(value) == entry["java_hash"], f"Wrong switch literal: {field}"
    assert set(mappings.values()) <= fields.keys()
    assert catalog["counts"] == {"recovered": 1401}
    assert (catalog["version_name"], catalog["version_code"]) == ("3.97.0", 3597523)
    assert len(pools) == 32 and len(fields) == 1401
    assert sum(e["value"] == "" for e in fields.values()) == 29
    assert sum(e["reads"] == 0 for e in fields.values()) == 2
    bootstrap = source.split("private val bootstrapStringFields = listOf(", 1)[1].split("\n    )", 1)[0]
    early = 0
    for owner, name in re.findall(r'"([^"]+)" to "([^"]+)"', bootstrap):
        entry = fields["L" + owner.replace(".", "/") + ";->" + name]
        assert entry.get("bootstrap"), f"Unreviewed bootstrap string: {owner}.{name}"
        early += 1
    assert early == 17
    print("Verified all 1,401 original values in 32 Kotlin pools, including 29 original empty strings.")
    return catalog, fields


def verify_initializer(apk, catalog, expected):
    import struct
    with zipfile.ZipFile(apk) as archive:
        data = archive.read(catalog["initializer_asset"])
    assert hashlib.sha256(data).hexdigest() == catalog["initializer_sha256"]
    length = len(data)

    def address(offset):
        assert 0 <= offset <= length - 4
        return (struct.unpack_from("<I", data, offset)[0] ^ (~length & 0xFFFFFFFF)) % length

    def string(offset):
        record = address(offset)
        encrypted, key = address(record), address(record + 4)
        size = struct.unpack_from("<H", data, encrypted)[0] ^ struct.unpack_from("<H", data, key)[0]
        assert encrypted + size + 2 <= length
        raw = bytes(data[encrypted + i] ^ data[key + (i & 255)] for i in range(2, size + 2))
        return raw.replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass").encode("utf-16", "surrogatepass").decode("utf-16")

    def array(offset):
        count = (~(struct.unpack_from("<H", data, offset)[0] ^ offset)) & 0xFFFF
        assert 1 <= count <= 100
        return [string(address(offset + 2 + i * 4)) for i in range(count)]

    for owner, pool in catalog["classes"].items():
        names = array(pool["field_array_offset"])
        values = array(pool["value_array_offset"])
        assert len(names) == len(values) == len(pool["fields"])
        assert set(names) == set(pool["fields"])
        for index, (name, value) in enumerate(zip(names, values)):
            entry = expected[owner + "->" + name]
            assert entry["initializer_index"] == index and entry["value"] == value
    print("All 1,401 Kotlin values match the original APK's ordered initializer tables.")


def rescan(apk, catalog, expected):
    # Keep the private input out of git, artifacts, and generated reports.
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == catalog["base_apk_sha256"], "This inventory belongs to a different APK"
    verify_initializer(apk, catalog, expected)
    from loguru import logger
    logger.remove()
    from androguard.core.dex import DEX
    import gc
    pools = {}
    reads = Counter()
    writes = Counter()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            dex = DEX(archive.read(name))
            for cls in dex.get_classes():
                declared = list(cls.get_fields())
                if declared and not list(cls.get_methods()) and all(
                    f.get_descriptor() == "Ljava/lang/String;"
                    and "static" in f.get_access_flags_string()
                    and "final" not in f.get_access_flags_string()
                    and f.get_init_value() is None for f in declared
                ):
                    pools[cls.get_name()] = {f.get_name() for f in declared}
                for method in cls.get_methods():
                    for ins in method.get_instructions():
                        op = ins.get_name()
                        if op not in ("sget-object", "sput-object"):
                            continue
                        field = ins.get_output().split(", ", 1)[-1].split(" ")[0]
                        if field in expected:
                            (reads if op == "sget-object" else writes)[field] += 1
            del dex
            gc.collect()
            print(f"Scanned {name}", flush=True)
    assert pools == {c: set(v["fields"]) for c, v in catalog["classes"].items()}, "Pool inventory is incomplete"
    assert not writes, "A candidate is initialized by DEX code"
    assert all(reads[f] == entry["reads"] for f, entry in expected.items()), "Reader counts changed"
    print("All DEX pool declarations and reader counts match; no DEX writers found.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Locally supplied original base APK (never uploaded)")
    args = parser.parse_args()
    catalog, fields = validate()
    if args.apk:
        rescan(args.apk, catalog, fields)
