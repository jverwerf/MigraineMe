#!/usr/bin/env python3
"""
Revert t()/tSync() wrapping at sites that are no longer in strings.json.

Needed whenever extract.py learns to exclude something it previously picked up —
Compose animation `label` parameters, developer diagnostic screens — because the
codemod has already been applied by then. Run extract.py first, then this, then
rebuild.

  --dry   report only
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import SRC, SKIP_FILES, decode  # noqa: E402

STRINGS = "tools/i18n/strings.json"

# A wrapped literal, with whatever call prefix precedes it.
WRAPPED = re.compile(
    r'(Text\(\s*|label\s*=\s*|title\s*=\s*|subtitle\s*=\s*|description\s*=\s*'
    r'|contentDescription\s*=\s*|placeholder\s*=\s*)'
    r't(?:Sync)?\(\s*("(?:[^"\\]|\\.)+")\s*\)'
)


def main():
    dry = "--dry" in sys.argv
    root = os.getcwd()

    with open(STRINGS, encoding="utf-8") as fh:
        wanted = {e["en"] for e in json.load(fh)}

    reverted = 0
    files = 0
    samples = []

    for dirpath, _, filenames in os.walk(os.path.join(root, SRC)):
        for fn in sorted(filenames):
            if not fn.endswith(".kt") or fn.startswith("Strings") or fn == "LangPrefs.kt":
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8") as fh:
                lines = fh.readlines()

            touched = False
            for i, line in enumerate(lines):
                def repl(m):
                    nonlocal touched
                    prefix, quoted = m.group(1), m.group(2)
                    lit = decode(quoted[1:-1])
                    # A file we now skip entirely loses every wrap in it.
                    if fn in SKIP_FILES or lit not in wanted:
                        touched = True
                        return prefix + quoted
                    return m.group(0)

                new = WRAPPED.sub(repl, line)
                if new != line:
                    reverted += 1
                    if len(samples) < 8:
                        samples.append((f"{rel}:{i + 1}", line.strip(), new.strip()))
                    lines[i] = new

            if touched:
                files += 1
                if not dry:
                    with open(path, "w", encoding="utf-8") as fh:
                        fh.writelines(lines)

    print(f"{'would revert' if dry else 'reverted'} {reverted} sites in {files} files\n")
    for site, before, after in samples:
        print(f"  {site}")
        print(f"    - {before[:100]}")
        print(f"    + {after[:100]}")


if __name__ == "__main__":
    main()
