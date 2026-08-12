#!/usr/bin/env python3
"""
Gate translations on the length budget of their call site.

A translation that overruns a hard-constrained label clips a button or pushes a
drawer item onto two lines. That is invisible in a diff and only shows up on a
screen, so it is caught here instead: every entry is measured against the budget
extract.py assigned it, and an overflow is a failure to be retranslated shorter,
usually by choosing a different word rather than abbreviating.

Also reports coverage, since a missing key silently renders English and would
otherwise look like a translation that simply reads oddly.

  check_lengths.py            all languages
  check_lengths.py de         one language
  check_lengths.py --strict   exit non-zero if any language is incomplete
"""

import json
import os
import sys

STRINGS = "tools/i18n/strings.json"
TRANS = "tools/i18n/translations"
LANGS = ["de", "es", "nl", "fr", "it", "pt"]

# Rendered width is not character count: German and Portuguese carry more wide
# glyphs and more capitals than English. A hard-class string that merely equals
# its budget is therefore reported as tight rather than passed silently.
TIGHT = 0.92


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    strict = "--strict" in sys.argv
    langs = args or LANGS

    with open(STRINGS, encoding="utf-8") as fh:
        spec = {e["en"]: e for e in json.load(fh)}

    total = len(spec)
    failed = False

    for lang in langs:
        path = os.path.join(TRANS, f"{lang}.json")
        if not os.path.isfile(path):
            print(f"{lang}: no translations yet ({path})")
            if strict:
                failed = True
            continue

        with open(path, encoding="utf-8") as fh:
            table = json.load(fh)

        over, tight, unknown = [], [], []
        for en, translated in table.items():
            e = spec.get(en)
            if e is None:
                unknown.append(en)
                continue
            n = len(translated)
            if n > e["max_len"]:
                over.append((en, translated, n, e["max_len"], e["class"]))
            elif e["class"] == "hard" and n > e["max_len"] * TIGHT:
                tight.append((en, translated, n, e["max_len"]))

        done = sum(1 for k in table if k in spec)
        pct = 100.0 * done / total if total else 0.0
        status = "FAIL" if over else "ok"
        print(f"\n{lang}: {done}/{total} translated ({pct:.1f}%)  overflow={len(over)}  tight={len(tight)}  [{status}]")

        for en, tr, n, mx, cls in over[:25]:
            print(f"  OVER  [{cls}] {n}>{mx}  {en!r}")
            print(f"        -> {tr!r}")
        if len(over) > 25:
            print(f"  ... {len(over) - 25} more overflows")

        for en, tr, n, mx in tight[:8]:
            print(f"  tight  {n}/{mx}  {en!r} -> {tr!r}")

        if unknown:
            print(f"  {len(unknown)} keys not in strings.json (stale, rerun extract.py):")
            for k in unknown[:5]:
                print(f"    {k!r}")

        if over:
            failed = True
        if strict and done < total:
            failed = True

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
