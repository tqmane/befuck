"""Check 3.97.1 mappings and signatures; optionally rescan the original base APK.

python3 checks/check_compatibility.py
python3 checks/check_compatibility.py --apk BASE_APK  # requires androguard
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = Path(__file__).with_name("compatibility-3599414.json")
PRIMITIVES = dict(zip(
    ("void", "boolean", "byte", "short", "char", "int", "long", "float", "double"),
    "VZBSCIJFD"))


def descriptor(name):
    return PRIMITIVES.get(name, "L" + name.replace(".", "/") + ";")


def signatures(value):
    if isinstance(value, list):
        for item in value:
            yield from signatures(item)
    elif isinstance(value, dict):
        kind = value.get("kind")
        if kind == "class":
            yield [kind, value["name"]]
        elif kind in ("method", "constructor"):
            desc = "(" + "".join(map(descriptor, value["parameters"])) + ")"
            desc += descriptor(value.get("returns", "void"))
            yield ["method", value["owner"], value.get("name", "<init>"), desc]
        elif kind in ("field", "singleton"):
            yield ["field", value["owner"], value["name"], descriptor(value.get("type", value["owner"]))]
        for item in value.values():
            yield from signatures(item)


def fingerprint(cls):
    return hashlib.sha256(json.dumps(cls, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def validate():
    evidence = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    source = (ROOT / "app/src/main/kotlin/dev/tqmane/befuck/symbols/KnownMappings3971.kt").read_text(encoding="utf-8")
    classes = dict(re.findall(r'"(\w+)" to "(\w+)"', source))
    methods = dict(re.findall(r'"(\w+\.\w+)" to "(\w+)"', source))
    assert classes == {key: value["name"] for key, value in evidence["classes"].items()}
    assert methods == evidence["methods"]
    assert 'VERSION_NAME = "3.97.1"' in source and 'VERSION_CODE = 3599414L' in source
    preset = json.loads((ROOT / "app/src/main/assets/befuck/symbols-3599414.json").read_text(encoding="utf-8"))
    assert preset["schema"] == 1 and preset["version_code"] == 3599414
    assert preset["package"] == "com.bereal.ft"
    values = preset["symbols"]["values"]
    legacy_preset = json.loads((ROOT / "app/src/main/assets/befuck/symbols-3597523.json").read_text(encoding="utf-8"))
    legacy_values = legacy_preset["symbols"]["values"]
    assert values.keys() == legacy_values.keys(), "Incomplete symbol record"
    assert values["feedMediaSymbols"]["values"].keys() == legacy_values["feedMediaSymbols"]["values"].keys()
    assert len(values["postDomainModelFieldOrder"]) == 28
    assert len(values["postContentsFieldOrder"]) == 5
    assert values["versionName"] == "3.97.1"
    assert values["sourceIdentity"] == "bundled:com.bereal.ft:3599414"
    assert values["stringFields"]["values"] == {}, "3.97.1 has no PairIP string repair pools"
    expected = {tuple(row) for row in evidence["signatures"]}
    actual = {tuple(row) for row in signatures(preset)}
    assert actual <= expected, f"Unverified preset members: {actual - expected}"
    legacy = ROOT / "app/src/main/assets/pairip/anonymous.dex"
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == evidence["legacy_lifecycle_sha256"]
    print(f"Verified {len(classes)} class mappings, {len(methods)} method renames, "
          f"{len(actual)} preset signatures; legacy lifecycle DEX unchanged.")
    return evidence


def rescan(apk, evidence):
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == evidence["base_apk_sha256"], "Unexpected base APK"
    from loguru import logger
    logger.remove()
    from androguard.core.dex import DEX
    required = {v["name"] for v in evidence["classes"].values()}
    required.update(row[1] for row in evidence["signatures"])
    found = {}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", entry):
                continue
            dex = DEX(archive.read(entry))
            for cls in dex.get_classes():
                name = cls.get_name()[1:-1].replace("/", ".")
                assert name not in ("com.pairip.VMRunner", "com.pairip.StartupLauncher")
                if name not in required:
                    continue
                found[name] = {
                    "super": cls.get_superclassname(),
                    "interfaces": list(cls.get_interfaces()),
                    "flags": cls.get_access_flags(),
                    "fields": [[f.get_name(), f.get_descriptor(), f.get_access_flags()] for f in cls.get_fields()],
                    "methods": [[m.get_name(), m.get_descriptor().replace(" ", ""), m.get_access_flags()] for m in cls.get_methods()],
                }
            print(f"Scanned {entry}", flush=True)
    assert required <= found.keys(), f"Missing classes: {required - found.keys()}"
    for item in evidence["classes"].values():
        assert fingerprint(found[item["name"]]) == item["sha256"], item["name"]
    for row in evidence["signatures"]:
        kind, owner, *member = row
        if kind == "class":
            continue
        assert any(item[:2] == member for item in found[owner][kind + "s"]), row
    print("All reviewed classes and member signatures match the original 3.97.1 DEX.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    evidence = validate()
    if args.apk:
        rescan(args.apk, evidence)
