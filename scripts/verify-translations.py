#!/usr/bin/env python3
# Apache-2.0. Read-only catalog parity and format-argument checks.
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([sdf])")


def catalog(folder):
    result = {}
    for node in ET.parse(ROOT / folder / "strings.xml").getroot():
        if not node.get("name") or node.get("translatable") == "false":
            continue
        key = (node.tag, node.get("name"))
        if key in result:
            raise ValueError(f"Duplicate resource in {folder}: {key}")
        values = [node] if node.tag == "string" else list(node)
        result[key] = sorted(tuple(sorted(FORMAT.findall("".join(value.itertext()))))
                             for value in values)
    return result


def main():
    english = catalog("values")
    for folder in ("values-es", "values-pt"):
        translated = catalog(folder)
        if english.keys() != translated.keys():
            missing = sorted(english.keys() - translated.keys())
            extra = sorted(translated.keys() - english.keys())
            raise ValueError(f"{folder}: missing={missing}, extra={extra}")
        for key, arguments in english.items():
            if translated[key] != arguments:
                raise ValueError(f"Format arguments differ in {folder}: {key}")
    print(f"Translation verification passed: {len(english)} resources in en/es/pt.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"Translation verification failed: {error}", file=sys.stderr)
        sys.exit(1)
