#!/usr/bin/env python3
"""Writes the licence texts of everything compiled into the Android app, for shipping in the APK.

Exits non-zero, without writing, if a crate ships no text for the licence it declares. Reads only
what cargo reports and the crates' own files, and writes crate names rather than local paths.

usage: tools/third_party_notices.py   (run from the repository root, after sourcing tools/env.sh)
Writes app/src/main/assets/THIRD-PARTY-NOTICES.txt, which Settings links to and the APK carries.
Adapted from the desktop's script of the same name.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT_PACKAGE = "booklub-mobile"
TARGET = "aarch64-linux-android"

# How to recognise each licence's text. "Is this file really the licence" matters because some
# crates ship a LICENSE or COPYING that only points at another file.
LICENCE_MARKERS = {
    "0BSD": [r"with or without fee is hereby granted\.\s+THE SOFTWARE"],
    "Apache-2.0": [r"Apache License", r"Version 2\.0"],
    "BSD-3-Clause": [r"Redistribution and use in source and binary forms", r"Neither the name"],
    "ISC": [r"Permission to use, copy, modify, and/or distribute", r"provided that the above"],
    "MIT": [r"Permission is hereby granted, free of charge"],
    "MPL-2.0": [r"Mozilla Public License Version 2\.0"],
    "Unicode-3.0": [r"UNICODE LICENSE V3"],
    "Unlicense": [r"This is free and unencumbered software"],
    "Zlib": [r"The origin of this software must not be misrepresented"],
}

LICENCE_FILE = re.compile(r"(un)?licen[cs]e|copying|copyright|notice", re.IGNORECASE)
LICENCE_DIR = re.compile(r"licen[cs]es?", re.IGNORECASE)
NOT_TEXT = {".rs", ".toml", ".json", ".py", ".sh", ".html", ".c", ".h"}


def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def rust_std_notice():
    # std is compiled into the library but is not in cargo's dependency data, so its notices
    # come from the toolchain that built it: the library's own copyright page, and the two
    # licences it is offered under.
    docs = Path(run("rustc", "--print", "sysroot").strip()) / "share" / "doc" / "rust"
    page = docs / "COPYRIGHT-library.html"
    licences = [docs / "licenses" / "MIT.txt", docs / "licenses" / "Apache-2.0.txt"]
    if not page.is_file() or not all(path.is_file() for path in licences):
        raise FileNotFoundError(f"Rust standard library: no copyright files under {docs.name}")
    html = page.read_text(encoding="utf-8")
    text = re.sub(r"<[^>]+>", "", re.sub(r"<(br|/p|/h\d|/li)[^>]*>", "\n", html))
    text = re.sub(r"\n{3,}", "\n\n", text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", '"')).strip()
    return "\n\n".join([text, *map(read_text, licences)])


# The shared core comes from Booklub's own desktop repository, under this app's licence.
OWN_SOURCE = "git+https://github.com/Dulestrom/Desktop-Booklub"
# Licence texts for crates that declare a licence but ship no file for it, such as UniFFI's.
SUPPLIED = {"MPL-2.0": Path("tools/licences/MPL-2.0.txt")}

ANDROID_LIBRARIES = """The app's Kotlin code uses these libraries, each under the Apache License 2.0, whose text
appears below among the crates' licences:

  AndroidX Activity, Lifecycle, Core, and Jetpack Compose (Google)
  AndroidX CameraX (Google)
  Kotlin standard library and kotlinx.coroutines (JetBrains)
  ZXing core (ZXing authors)
  JNA (Java Native Access), used under its Apache License 2.0 option

SQLite, compiled into the Rust library, is in the public domain.
Manrope, the typeface, is under the SIL Open Font License 1.1, in assets/MANROPE-LICENSE.txt."""


def compiled_crates(target):
    # cargo tree rather than the graph in cargo metadata, which unifies features across build and
    # dev dependencies and so lists crates that are never compiled into the app.
    tree = run("cargo", "tree", "--locked", "--manifest-path", "rust/Cargo.toml", "-p", ROOT_PACKAGE, "-e", "normal", "--target", target,
               "--prefix", "none", "-f", "{p}")
    return {tuple(line.split()[:2]) for line in tree.splitlines() if line.strip()}


def licence_files(crate_dir):
    found = []
    for path in sorted(crate_dir.rglob("*")):
        if not path.is_file() or path.suffix.lower() in NOT_TEXT:
            continue
        relative = path.relative_to(crate_dir)
        if "target" in relative.parts[:-1] or ".git" in relative.parts[:-1]:
            continue
        in_licence_dir = any(LICENCE_DIR.fullmatch(part) for part in relative.parts[:-1])
        if LICENCE_FILE.match(path.name) or in_licence_dir:
            found.append(path)
    return found


def read_text(path):
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode("latin-1")
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    return "\n".join(lines).strip("\n")


def licences_in(text):
    return {
        licence
        for licence, markers in LICENCE_MARKERS.items()
        if all(re.search(marker, text, re.IGNORECASE) for marker in markers)
    }


def satisfied(expression, available):
    """Evaluates an SPDX expression, or raises ValueError on a licence with no marker above."""
    tokens = re.findall(r"[()/]|[^\s()/]+", expression)

    def parse_or():
        result = parse_and()
        while tokens and tokens[0] in ("OR", "/"):
            tokens.pop(0)
            right = parse_and()
            result = result or right
        return result

    def parse_and():
        result = parse_term()
        while tokens and tokens[0] == "AND":
            tokens.pop(0)
            right = parse_term()
            result = result and right
        return result

    def parse_term():
        token = tokens.pop(0)
        if token == "(":
            result = parse_or()
            tokens.pop(0)
            return result
        if token not in LICENCE_MARKERS:
            raise ValueError(token)
        return token in available

    return parse_or()


def collect(packages, compiled, workspace_members):
    """Returns ({text key: (text, [(crate label, file name)])}, [crate lines], [problems])."""
    groups, index, problems = {}, [], []
    for package in sorted(packages, key=lambda p: (p["name"], p["version"])):
        if (package["name"], f"v{package['version']}") not in compiled:
            continue
        if package["id"] in workspace_members or (package.get("source") or "").startswith(OWN_SOURCE):
            continue
        label = f"{package['name']} {package['version']}"
        expression = package["license"]
        crate_dir = Path(package["manifest_path"]).parent
        files = licence_files(crate_dir)
        if package["license_file"]:
            files = sorted(set(files) | {crate_dir / package["license_file"]})
        texts = {path: read_text(path) for path in files if path.exists()}
        if not texts and expression in SUPPLIED:
            texts = {SUPPLIED[expression]: read_text(SUPPLIED[expression])}

        available = set().union(*map(licences_in, texts.values())) if texts else set()
        if not texts:
            problems.append(f"{label}: no licence file (declares {expression})")
        elif expression:
            try:
                if not satisfied(expression, available):
                    found = ", ".join(sorted(available)) or "nothing recognisable"
                    problems.append(f"{label}: declares {expression} but ships {found}")
            except ValueError as unknown:
                problems.append(f"{label}: add a marker for {unknown} to LICENCE_MARKERS")
        elif not package["license_file"]:
            problems.append(f"{label}: declares no licence")

        index.append(f"{label}: {expression or 'see its licence file'}")
        for path, text in texts.items():
            # Grouping ignores how lines wrap, but not any wording or copyright holder.
            key = " ".join(text.split())
            groups.setdefault(key, (text, []))[1].append(
                (label, path.relative_to(crate_dir).as_posix() if path.is_relative_to(crate_dir) else "supplied text")
            )
    return groups, index, problems


def render(groups, index, own_licence, std_notice):
    rule = "=" * 78
    out = [
        "Third-party notices for Booklub for Android",
        "",
        "This file is generated when the app is built. Its native library is built from the",
        "Rust crates below, and this file holds the licence files each one ships. Where a crate",
        "offers a choice of licences, all of its licence files are here.",
        "",
        f"{rule}\nBooklub\n{rule}",
        "",
        own_licence,
        "",
        "",
        f"{rule}\nAndroid libraries\n{rule}",
        "",
        ANDROID_LIBRARIES,
        "",
        "",
        f"{rule}\nCrates ({len(index)})\n{rule}",
        "",
        *index,
    ]
    ordered = sorted(groups.values(), key=lambda group: (-len(group[1]), group[1][0]))
    for text, users in ordered:
        names = sorted({f"{crate} ({file})" for crate, file in users})
        out += ["", "", rule, "Used by:", *[f"  {name}" for name in names], rule, "", text]
    out += [
        "",
        "",
        rule,
        "The Rust standard library",
        "",
        "Part of it is compiled into the native library. These are the licence files of the",
        "toolchain that built it.",
        rule,
        "",
        std_notice,
    ]
    return "\n".join(out) + "\n"


def main():
    rust = Path("rust")
    metadata = json.loads(
        run("cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", TARGET, "--manifest-path", str(rust / "Cargo.toml"))
    )
    compiled = compiled_crates(TARGET)
    groups, index, problems = collect(
        metadata["packages"], compiled, set(metadata["workspace_members"])
    )
    if problems:
        print("REFUSING: these crates have no usable licence text:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        sys.exit(1)
    own_licence = read_text(Path("LICENSE"))
    try:
        std_notice = rust_std_notice()
    except FileNotFoundError as missing:
        sys.exit(f"REFUSING: {missing}")
    out_file = Path("app/src/main/assets/THIRD-PARTY-NOTICES.txt")
    out_file.write_text(render(groups, index, own_licence, std_notice), encoding="utf-8")
    print(f"{len(index)} crates, {len(groups)} distinct licence texts.", file=sys.stderr)
    print(out_file)


if __name__ == "__main__":
    main()
