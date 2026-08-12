#!/usr/bin/env python3
"""
Wrap extracted display strings in t().

Only strings present in tools/i18n/strings.json are touched, and only at
display sites the extractor's scanner recognises (see extract.iter_sites), so
this cannot reach a literal that flows into a stored value. Run extract.py
first.

The scanner reports a literal's exact span, including literals inside
conditional expressions (`if (…) "A" else "B"`, `?:` fallbacks, `when`
branches), and reports whether it is already wrapped — so this can be re-run
any number of times without double-wrapping. Interpolated literals are left
for convert_interpolated.py.

On composability: t() is @Composable, and most display sites sit inside a
@Composable function, but some do not — a top-level val, a companion object, a
notification builder. Rather than guess, this wraps everything in t() and lets
kotlinc find the ones that are wrong: fix_composable.py reads those errors and
switches exactly those sites to tSync(). The compiler is a more reliable
oracle here than any amount of static analysis, and the failure mode is a
build error rather than a silent bug.

  --dry   show what would change, write nothing
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import SRC, SKIP_FILES, iter_sites, ANIM_LABEL, ANIM_LOOKBACK  # noqa: E402
from kscan import decode  # noqa: E402

STRINGS = "tools/i18n/strings.json"


def main():
    dry = "--dry" in sys.argv
    root = os.getcwd()
    if not os.path.isdir(os.path.join(root, SRC)):
        sys.exit(f"run from the repo root; {SRC} not found")

    with open(STRINGS, encoding="utf-8") as fh:
        wanted = {e["en"] for e in json.load(fh)}

    changed_files = 0
    changed_sites = 0
    samples = []

    for dirpath, _, filenames in os.walk(os.path.join(root, SRC)):
        for fn in sorted(filenames):
            if not fn.endswith(".kt"):
                continue
            if fn in ("Strings.kt", "LangPrefs.kt") or fn.startswith("Strings"):
                continue
            if fn in SKIP_FILES:
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
            lines = content.splitlines(keepends=True)

            spans = []
            for kind, lit, wrap_ok in iter_sites(content):
                if not wrap_ok or lit.wrapped or lit.interpolated:
                    continue
                if decode(lit.raw) not in wanted:
                    continue
                ln = content.count("\n", 0, lit.start) + 1
                i = ln - 1
                line = lines[i] if i < len(lines) else ""
                if line.lstrip().startswith("//"):
                    continue
                prev = lines[max(0, i - ANIM_LOOKBACK):i]
                if kind == "label" and ANIM_LABEL.search(line + "\n" + "".join(prev)):
                    continue
                spans.append((lit.start, lit.end, ln))

            if not spans:
                continue
            # Right to left so earlier offsets stay valid.
            new = content
            for start, end, ln in sorted(set(spans), reverse=True):
                if len(samples) < 8:
                    samples.append((f"{rel}:{ln}",
                                    new[start:end][:100],
                                    f"t({new[start:end]})"[:100]))
                new = new[:start] + f"t({new[start:end]})" + new[end:]
            changed_files += 1
            changed_sites += len(set(spans))
            if not dry:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(new)

    print(f"{'would change' if dry else 'changed'} {changed_sites} sites in {changed_files} files\n")
    for site, before, after in samples:
        print(f"  {site}")
        print(f"    - {before[:110]}")
        print(f"    + {after[:110]}")
    if dry:
        print("\ndry run, nothing written")


if __name__ == "__main__":
    main()
