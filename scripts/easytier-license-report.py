#!/usr/bin/env python3
"""Collect pinned EasyTier JNI dependency license texts for optional APKs.

Uses Cargo's resolved target graph, conservatively including build tools.
Fails if a package has no source license/notice text; such packages require a
reviewed, checked-in supplemental notice before a binary can be released.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess


def license_files(base):
    for directory, dirs, files in os.walk(base):
        dirs[:] = sorted(d for d in dirs if d not in (".git", "target", "build", "node_modules"))
        for name in sorted(files):
            lower = name.lower()
            if lower.startswith(("license", "licence", "copying", "copyright", "notice")):
                path = Path(directory) / name
                if path.is_file() and path.stat().st_size < 2_000_000:
                    try:
                        yield path, path.read_text()
                    except UnicodeError:
                        continue


def is_notice_pointer(text):
    value = text.strip()
    # Raw GitHub URLs return a symlink's target text, not the target contents.
    # Such one-line paths must never count as a reproduced license grant.
    if value.startswith(("../", "./", "..\\", ".\\")) and "\n" not in value and "\r" not in value:
        return True
    return bool(re.fullmatch(
        r"(?:(?:\.\.?|[A-Za-z0-9_.-]+)/)*(?:LICENSE|LICENCE|COPYING|NOTICE|COPYRIGHT)[A-Za-z0-9_.-]*",
        value, flags=re.IGNORECASE,
    ))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", action="append")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / "build/easytier/source"
    output = root / "build/easytier/licenses"
    output.mkdir(parents=True, exist_ok=True)
    targets = sorted(set(args.target or ["aarch64-linux-android"]))
    packages = {}
    reachable = set()
    for target in targets:
        data = json.loads(subprocess.check_output([
            "cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", target,
        ], cwd=source))
        packages.update({package["id"]: package for package in data["packages"]})
        nodes = {node["id"]: node for node in data["resolve"]["nodes"]}
        pending = [package["id"] for package in data["packages"] if package["name"] == "easytier-android-jni"]
        if len(pending) != 1:
            raise RuntimeError("expected exactly one EasyTier JNI root")
        seen = set()
        while pending:
            identity = pending.pop()
            if identity in seen:
                continue
            seen.add(identity)
            for dependency in nodes[identity]["deps"]:
                if any(kind["kind"] != "dev" for kind in dependency["dep_kinds"]):
                    pending.append(dependency["pkg"])
        reachable.update(seen)
    supplements = root / "config/easytier/supplemental"
    provenance = json.loads((root / "config/easytier/supplemental-sources.json").read_text())
    reviewed = {entry["path"]: entry["sha256"] for entry in provenance}
    for file in supplements.glob("*/*"):
        if file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest() != reviewed.get(file.relative_to(supplements).as_posix()):
            raise RuntimeError("supplemental notice lacks a matching reviewed source hash: " + file.name)
    inventory = []
    texts = {}
    missing = []
    pointers = []
    resolved_pointers = []
    for identity in sorted(reachable):
        package = packages[identity]
        base = Path(package["manifest_path"]).parent
        candidates = list(license_files(base))
        # Workspace members and Git sub-crates often share the repository's
        # top-level license instead of shipping a duplicate per package.
        if package.get("license_file"):
            explicit = base / package["license_file"]
            if explicit.is_file():
                candidates.append((explicit, explicit.read_text()))
        for parent in [base, *base.parents]:
            if (parent / ".git").exists():
                for entry in parent.iterdir():
                    if entry.is_file() and entry.name.lower().startswith(("license", "copying", "notice")):
                        candidates.append((entry, entry.read_text()))
                break
            if parent.name == "registry" or parent == parent.parent:
                break
        # These supplemental files must be reviewed from the package's pinned
        # source revision; the script never invents copyright or license text.
        supplement = root / "config/easytier/supplemental" / (package["name"] + "-" + package["version"])
        if supplement.exists():
            candidates.extend(license_files(supplement))
        references = []
        for path, contents in candidates:
            if is_notice_pointer(contents):
                record = {"package": package["name"] + "-" + package["version"], "file": path.name}
                replacement = supplement / path.name
                # A separately reviewed, hash-checked pinned-source supplement may
                # replace a broken symlink stub in a published crate. Never package the stub.
                if replacement.is_file() and replacement != path and replacement.read_text().strip() and not is_notice_pointer(replacement.read_text()):
                    record["resolved_by"] = replacement.relative_to(supplements).as_posix()
                    resolved_pointers.append(record)
                else:
                    pointers.append(record)
                continue
            if not contents.strip():
                continue
            digest = hashlib.sha256(contents.encode()).hexdigest()
            texts[digest] = contents
            references.append({"name": path.name, "sha256": digest})
        references = sorted({(entry["name"], entry["sha256"]) for entry in references})
        if not references:
            missing.append(package["name"] + "-" + package["version"])
        inventory.append({
            "name": package["name"], "version": package["version"],
            "source": package.get("source") or "EasyTier pinned workspace",
            "repository": package.get("repository"), "license": package.get("license"),
            "notices": [{"name": name, "sha256": digest} for name, digest in references],
        })
    inventory.sort(key=lambda package: (package["name"], package["version"]))
    (output / "EASYTIER_DEPENDENCIES.json").write_text(json.dumps({
        "root": "easytier-android-jni", "targets": targets,
        "scope": "resolved non-dev dependency graph, including build dependencies",
        "packages": inventory, "missing_notice_packages": sorted(missing),
        "pointer_notice_files": pointers, "resolved_pointer_notice_files": resolved_pointers,
        "notice_text_count": len(texts),
    }, indent=2) + "\n")
    bundle = ["EasyTier JNI dependency notices\n", "Includes build tools as a conservative superset.\n"]
    emitted = set()
    for package in inventory:
        bundle.append("\n" + "=" * 72 + "\n")
        bundle.append(package["name"] + " " + package["version"] + "\n")
        bundle.append("Declared license: " + (package["license"] or "see included source license") + "\n")
        bundle.append("Source: " + (package["repository"] or package["source"]) + "\n")
        for reference in package["notices"]:
            digest = reference["sha256"]
            bundle.append(reference["name"] + " (SHA-256 " + digest + ")\n")
            if digest in emitted:
                bundle.append("Identical notice reproduced earlier in this bundle.\n")
            else:
                bundle.append(texts[digest].rstrip() + "\n")
                emitted.add(digest)
    (output / "THIRD_PARTY_NOTICES.txt").write_text("".join(bundle))
    print(f"Resolved packages: {len(inventory)}; unique notice texts: {len(texts)}; missing: {len(missing)}")
    if pointers:
        raise SystemExit("License files contain unresolved path pointers; resolve their pinned source targets before release.")
    if missing:
        raise SystemExit("License review required: " + ", ".join(sorted(missing)))


if __name__ == "__main__":
    main()
