#!/usr/bin/env python3
"""Validate that Corestra's shaded JAR contains runtime dependencies and no provided/test APIs."""

from pathlib import Path
import sys
import zipfile


def main() -> int:
    candidates = sorted(Path("target").glob("corestra-*-all.jar"))
    if len(candidates) != 1:
        print(f"Expected one shaded JAR in target/, found {len(candidates)}", file=sys.stderr)
        return 1

    jar = candidates[0]
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())

    required = {
        "dev/nquangit/corestra/CorestraExtension.class",
        "com/fasterxml/jackson/databind/ObjectMapper.class",
    }
    missing = sorted(required - names)
    forbidden_prefixes = (
        "burp/api/montoya/",
        "org/junit/",
        "org/opentest4j/",
    )
    forbidden = sorted(name for name in names if name.startswith(forbidden_prefixes))

    if missing:
        print("Missing required shaded entries:", *missing, sep="\n  ", file=sys.stderr)
    if forbidden:
        print("JAR contains provided/test classes:", *forbidden[:20], sep="\n  ", file=sys.stderr)
    if missing or forbidden:
        return 1

    print(f"Validated {jar}: {len(names)} entries; Jackson included; Montoya/JUnit excluded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
