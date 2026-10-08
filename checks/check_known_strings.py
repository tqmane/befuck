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


def validate():
    catalog = json.loads(CATALOG.read_text(), object_pairs_hook=unique_object)
    source = (ROOT / "app/src/main/kotlin/dev/tqmane/befuck/symbols/KnownMappings3970.kt").read_text()
    mappings = {}
    for symbol, owner, field in re.findall(r'"([^"]+)" to Pair\("([^"]+)", "([^"]+)"\)', source):
        assert symbol not in mappings, f"Duplicate symbol: {symbol}"
        mappings[symbol] = "L" + owner.replace(".", "/") + ";->" + field
    assert len(set(mappings.values())) == len(mappings), "A field is mapped twice"
    block = source.split("private val runtimeStringValues = mapOf(", 1)[1].split("\n    )", 1)[0]
    values = {}
    for symbol, literal in re.findall(r'"([^"]+)" to ("(?:[^"\\]|\\.)*")', block):
        assert symbol not in values, f"Duplicate repair: {symbol}"
        assert symbol in mappings, f"Repair lacks a field: {symbol}"
        values[symbol] = json.loads(literal.replace(r"\$", "$"))
    counts = Counter()
    fields = {}
    used_symbols = set()
    for owner, pool in catalog["classes"].items():
        for name, entry in pool["fields"].items():
            field = owner + "->" + name
            fields[field] = entry
            counts[entry["status"]] += 1
            symbol = entry.get("symbol")
            if symbol:
                assert mappings[symbol] == field, f"Wrong repair target: {symbol}"
                used_symbols.add(symbol)
                if symbol in values:
                    assert entry["value"] == values[symbol], f"Wrong repair value: {symbol}"
            if entry["status"] == "unresolved":
                assert symbol is None and "value" not in entry, f"Unresolved value was guessed: {field}"
            elif entry["status"] == "confirmed":
                assert entry.get("evidence") and symbol in values, f"Missing confirmation: {field}"
                assert entry["value"] and not entry["value"].startswith("__unresolved_"), field
            elif entry["status"] == "legacy_placeholder":
                assert entry["value"].startswith("__unresolved_"), field
            else:
                assert entry["status"] in {"existing", "existing_bootstrap", "existing_diagnostics", "dynamic"}
            if "java_hash" in entry:
                assert java_hash(entry["value"]) == entry["java_hash"], f"Wrong switch literal: {field}"
    assert used_symbols == set(mappings), "KnownMappings and inventory disagree"
    assert counts == catalog["counts"]
    assert (catalog["version_name"], catalog["version_code"]) == ("3.97.0", 3597523)
    assert len(catalog["classes"]) == 32 and len(fields) == 1401
    assert sum(entry["reads"] == 0 for entry in fields.values()) == 2
    # Early startup has no RuntimeKnowledge version yet. Preserve its existing call
    # points and ensure its constants cannot drift from the centralized catalog.
    java = (ROOT / "app/src/main/java/dev/tqmane/befuck/BeRealModule.java").read_text()
    bootstrap = java.split("private void restoreBeRealOnCreateDispatch", 1)[1].split("private void restoreMainActivityLifecycleDispatch", 1)[0]
    early = 0
    for block in bootstrap.split("        try {")[2:]:
        owner = re.search(r'Class.forName\(\s*"([^"]+)"', block).group(1)
        name = re.search(r'getDeclaredField\("([^"]+)"', block).group(1)
        literal = re.search(r'restoreStaticStringIfNull\([^,]+, "runtime-string", ("(?:[^"\\]|\\.)*")\)', block).group(1)
        entry = fields["L" + owner.replace(".", "/") + ";->" + name]
        assert entry["status"] == "existing_bootstrap" and entry["value"] == json.loads(literal)
        early += 1
    assert early == counts["existing_bootstrap"] == 17
    print(f"Inventory verified: {len(fields)} fields in 32 classes; {dict(counts)}")
    return catalog, fields


def rescan(apk, catalog, expected):
    # Keep the private input out of git, artifacts, and generated reports.
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == catalog["base_apk_sha256"], "This inventory belongs to a different APK"
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
