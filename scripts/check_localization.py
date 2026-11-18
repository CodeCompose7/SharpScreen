#!/usr/bin/env python3
"""Check the Mac host's Localizable.strings files.

  scripts/check_localization.py            # en/ko parity, specifiers, Text("…") grep
  scripts/check_localization.py --extract  # also compile with -emit-localized-strings
                                           # and require every extracted key in en

Exit status is non-zero on any error. Needs macOS (plutil) and, for
--extract, a Swift toolchain.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAC = os.path.join(ROOT, "MacHost")
RES = os.path.join(MAC, "Resources")
LANGS = ["en", "ko"]

# Keys looked up at runtime from data rather than a literal the compiler sees
# (DisplaySettings.resolutionGroups ratio labels via LocalizedStringKey(_:)).
DYNAMIC_KEYS = {"Widescreen", "HD/4K", "Classic", "Surface/Pixel", "Tablet Wide", "iPad"}

SPEC = re.compile(r"%(?:\d+\$)?[-+ #0']*\d*(?:\.\d+)?(?:hh|h|ll|l|q|z|t|j)?[@dDuUxXoOfeEgGcCsSpaA%]")


def load(lang, table="Localizable"):
    path = os.path.join(RES, f"{lang}.lproj", f"{table}.strings")
    out = subprocess.check_output(["plutil", "-convert", "json", "-o", "-", path])
    return json.loads(out)


def specifiers(s):
    return [m for m in SPEC.findall(s) if m != "%%"]


def extract_keys():
    with tempfile.TemporaryDirectory() as tmp:
        data = os.path.join(tmp, "data")
        cmd = ["swift", "build", "--package-path", MAC, "--scratch-path", os.path.join(tmp, "build"),
               "-Xswiftc", "-emit-localized-strings",
               "-Xswiftc", "-emit-localized-strings-path", "-Xswiftc", data]
        build = subprocess.run(cmd, capture_output=True, text=True)
        if build.returncode != 0:
            sys.stderr.write(build.stdout + build.stderr)
            raise SystemExit("swift build failed")
        keys = {}
        for f in glob.glob(os.path.join(data, "*.stringsdata")):
            for entries in json.load(open(f)).get("tables", {}).values():
                for e in entries:
                    if e["key"]:
                        keys.setdefault(e["key"], os.path.basename(f).split(".")[0])
        return keys


TEXT_LITERAL = re.compile(r'\bText\("((?:[^"\\]|\\.)*)"\)')


def grep_text_literals():
    """Text("literal") calls in Sources, as regexes over keys (interpolations
    become 'any specifier')."""
    found = []
    for path in sorted(glob.glob(os.path.join(MAC, "Sources", "*.swift"))):
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            for m in TEXT_LITERAL.finditer(line):
                lit = m.group(1)
                if not lit:
                    continue
                parts = re.split(r"\\\((?:[^()]|\([^()]*\))*\)", lit)
                body = [re.escape(p.replace('\\"', '"').replace("\\n", "\n")) for p in parts]
                found.append((f"{os.path.basename(path)}:{n}", lit, re.compile("^" + "%[^ ]*?[@d-gsu]".join(body) + "$")))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--extract", action="store_true", help="compile and compare against compiler-extracted keys")
    args = ap.parse_args()

    errors = []
    tables = {lang: load(lang) for lang in LANGS}
    en, ko = tables["en"], tables["ko"]

    for a, b in (("en", "ko"), ("ko", "en")):
        for k in sorted(set(tables[a]) - set(tables[b])):
            errors.append(f"key in {a} but missing in {b}: {k!r}")

    for k in sorted(set(en) & set(ko)):
        if en[k] != k:
            errors.append(f"en value differs from key: {k!r}")
        if specifiers(k) != specifiers(ko[k]):
            errors.append(f"specifier mismatch: {k!r} {specifiers(k)} vs ko {specifiers(ko[k])}")
        if not ko[k].strip():
            errors.append(f"empty ko value: {k!r}")

    # InfoPlist.strings: same keys in every language that has one.
    info = {lang: load(lang, "InfoPlist") for lang in LANGS
            if os.path.exists(os.path.join(RES, f"{lang}.lproj", "InfoPlist.strings"))}
    if len(info) > 1:
        ref = set(next(iter(info.values())))
        for lang, t in info.items():
            if set(t) != ref:
                errors.append(f"InfoPlist.strings keys differ in {lang}")

    texts = grep_text_literals()
    unmatched = [(loc, lit) for loc, lit, rx in texts if not any(rx.match(k) for k in en)]
    for loc, lit in unmatched:
        errors.append(f'Text("{lit}") at {loc} has no key')

    print(f"en: {len(en)} keys, ko: {len(ko)} keys, InfoPlist languages: {sorted(info)}")
    print(f'Text("…") literals in Sources: {len(texts)}, without a key: {len(unmatched)}')

    if args.extract:
        extracted = extract_keys()
        missing = sorted(set(extracted) - set(en))
        for k in missing:
            errors.append(f"compiler-extracted key missing from en ({extracted[k]}): {k!r}")
        unused = sorted(set(en) - set(extracted) - DYNAMIC_KEYS)
        print(f"compiler-extracted keys: {len(extracted)}, missing from en: {len(missing)}, "
              f"en keys not in source: {len(unused)}")
        for k in unused:
            print(f"  warning: unused key {k!r}")

    for e in errors:
        print("ERROR:", e)
    print("OK" if not errors else f"{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
