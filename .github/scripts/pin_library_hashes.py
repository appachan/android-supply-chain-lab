#!/usr/bin/env python3
"""Pin sha256 for a signed library in a regenerated verification-metadata.xml.

Regeneration does not stop on a bad signature, so read its result instead of trusting the files.
Exits non-zero unless every fetched version is signed by an expected key. Stop the workflow on failure:
the hashes written by the regeneration stay in the metadata, and a later verification passes with them.

usage: pin_library_hashes.py <metadata.xml> <library-repository> <group> <name> <fingerprint>...
"""
import hashlib
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"
ORIGIN = "pinned after signature verification"


def reject(message):
    sys.exit(f"Rejected: {message}")


def trusted_versions(root, group, name, fingerprints):
    for key in root.iter(f"{NS}trusted-key"):
        if key.get("id") in fingerprints:
            for scope in [key, *key.findall(f"{NS}trusting")]:
                if scope.get("group") == group and scope.get("name") == name:
                    yield scope.get("version")


def insertion_point(text, group, name, version):
    """Keep the order that Gradle writes, so the next regeneration does not move the block."""
    for match in re.finditer(r'      <component group="([^"]*)" name="([^"]*)" version="([^"]*)"', text):
        if match.groups() > (group, name, version):
            return match.start()
    return text.index("   </components>")


def main(metadata, repository, group, name, *fingerprints):
    root = ET.parse(metadata).getroot()
    failed = [key.get("id") for key in root.iter(f"{NS}ignored-key") if "verification failed" in (key.get("reason") or "")]
    if failed:
        reject(f"signature verification failed for keys {failed}")

    trusted = set(trusted_versions(root, group, name, fingerprints))
    components = {c.get("version"): c for c in root.iter(f"{NS}component") if c.get("group") == group and c.get("name") == name}
    module_directory = pathlib.Path(repository, *group.split("."), name)
    new = []
    for version_directory in sorted(path for path in module_directory.iterdir() if path.is_dir()):
        version = version_directory.name
        if version not in trusted:
            reject(f"{version} is not covered by the expected keys {fingerprints}")
        existing = components.get(version)
        if existing is not None:
            if all(entry.get("origin") == ORIGIN for entry in existing.iter(f"{NS}sha256")):
                continue
            reject(f"{version} already has a hash that this script did not write")
        rows = []
        for suffix in (".aar", ".module"):
            file_name = f"{name}-{version}{suffix}"
            digest = hashlib.sha256((version_directory / file_name).read_bytes()).hexdigest()
            rows.append(f'         <artifact name="{file_name}">\n            <sha256 value="{digest}" origin="{ORIGIN}"/>\n         </artifact>\n')
        new.append((version, f'      <component group="{group}" name="{name}" version="{version}">\n{"".join(rows)}      </component>\n'))

    path = pathlib.Path(metadata)
    text = path.read_text()
    for version, block in new:
        position = insertion_point(text, group, name, version)
        text = text[:position] + block + text[position:]
    path.write_text(text)
    print(f"Pinned {len(new)} versions")


if __name__ == "__main__":
    main(*sys.argv[1:])
