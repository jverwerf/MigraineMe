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
import re
import sys

STRINGS = "tools/i18n/strings.json"
TRANS = "tools/i18n/translations"
LANGS = ["de", "es", "nl", "fr", "it", "pt", "ja", "ko", "tr", "pl", "zh"]

# Hanzi, kana and hangul take about 1.8x the width of a Latin letter, so counting
# characters would wave through a label that clips on screen.
WIDE = re.compile(r"[\u1100-\u11ff\u2e80-\u9fff\uac00-\ud7a3\uf900-\ufaff\uff00-\uffef]")


def width(s):
    wide = len(WIDE.findall(s))
    return round(wide * 1.8 + (len(s) - wide))

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
            # "<key>|few" and friends are plural forms of <key>, same budget.
            e = spec.get(en.split("|")[0])
            if e is None:
                unknown.append(en)
                continue
            n = width(translated)
            if n > e["max_len"]:
                over.append((en, translated, n, e["max_len"], e["class"]))
            elif e["class"] == "hard" and n > e["max_len"] * TIGHT:
                tight.append((en, translated, n, e["max_len"]))

        done = sum(1 for k in table if k in spec)  # plural variants do not count
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
