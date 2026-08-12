#!/usr/bin/env python3
"""
Convert interpolated display strings to positional-placeholder form.

    Text("Logged ${n} attacks")        ->  Text(t("Logged %s attacks", n))
    Text("Hi $name, " + suffix)        ->  Text(t("Hi %1$s, %2$s", name, suffix))
    Text(t("Streak: ${days}"))         ->  Text(t("Streak: %s", days))   # broken wrap: the
                                            # interpolated RESULT was the lookup key, which
                                            # can never hit a translation

Word order moves in translation, so any string with more than one argument uses
positional %1$s/%2$s (written %1\\$s in Kotlin source) per the contract in
Strings.kt; a single argument stays plain %s. Strings.format substitutes after
the (still-placeholdered) English key is translated.

Only display sites recognised by extract.iter_sites are touched — the same
safety property as codemod.py. A site is SKIPPED (and reported) when:

  no-prose     nothing translatable once interpolation is stripped ("$a / $b");
               left alone, nothing to translate
  nested-prose an interpolation expression itself contains an English literal
               (usually a plural: "${n} day${if (n==1) "" else "s"}") — a
               mechanical conversion would bake grammar into the argument, so
               these need a human rewrite into whole-sentence alternatives
  multiline    triple-quoted or comment-carrying expressions the rewrite would
               mangle

  --dry    show what would change, write nothing
  --list   only print the skip report
"""

import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract import SRC, SKIP_FILES, iter_sites, WORD  # noqa: E402
from kscan import scan_string, skip_trivia, skip_arg, scan_display_expr, BRACKETS, skip_balanced  # noqa: E402

NESTED_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def split_chain(c, start, end):
    """Split c[start:end] on top-level '+'. Returns list of (kind, s, e) where
    kind is 'str' for a string literal part, 'expr' otherwise. Also detects an
    enclosing t(/tSync( wrap: returns (parts, wrapper) where wrapper is the
    span of the whole t(...) call if the chain IS a single wrapped literal."""
    parts = []
    i = skip_trivia(c, start)
    while i < end:
        i = skip_trivia(c, i)
        if i >= end:
            break
        if c[i] == '"':
            s = scan_string(c, i)
            if s is None:
                return None
            parts.append(("str", i, s[0]))
            i = s[0]
        else:
            j = i
            while j < end:
                ch = c[j]
                if ch == '"':
                    st = scan_string(c, j)
                    j = st[0] if st else j + 1
                    continue
                if ch in BRACKETS:
                    j = skip_balanced(c, j)
                    continue
                if ch == "+" and not c.startswith("++", j):
                    break
                j += 1
            parts.append(("expr", i, min(j, end)))
            i = j
        i = skip_trivia(c, i)
        if i < end and c[i] == "+":
            i += 1
        else:
            break
    return parts


def build(c, parts):
    """Build (pattern, args, problems) from chain parts."""
    segs = []       # ('lit', srctext) / ('arg', srctext)
    problems = []
    for kind, s, e in parts:
        if kind == "str":
            st = scan_string(c, s)
            if st is None:
                problems.append("unparsable")
                continue
        if kind == "str":
            _, inner, _ = st
            if c.startswith('"""', s):
                problems.append("multiline")
            for tag, val in inner:
                if tag == "lit":
                    segs.append(("lit", val))
                else:
                    segs.append(("arg", val.strip()))
        else:
            segs.append(("arg", c[s:e].strip()))

    args = [v for t_, v in segs if t_ == "arg"]
    for a in args:
        if "//" in a or "\n" in a and a.count("\n") > 2:
            problems.append("multiline")
        for m in NESTED_LIT.finditer(a):
            if WORD.search(m.group(1)):
                problems.append("nested-prose")
    prose = "".join(v for t_, v in segs if t_ == "lit")
    if not WORD.search(prose):
        problems.append("no-prose")

    positional = len(args) > 1
    n = 0
    out = []
    for t_, v in segs:
        if t_ == "lit":
            out.append(v.replace("%", "%%"))
        else:
            n += 1
            out.append(f"%{n}\\$s" if positional else "%s")
    return "".join(out), args, problems


def main():
    dry = "--dry" in sys.argv
    root = os.getcwd()
    if not os.path.isdir(os.path.join(root, SRC)):
        sys.exit(f"run from the repo root; {SRC} not found")

    converted = 0
    conv_files = 0
    skipped = defaultdict(list)
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

            # Group interpolated literals by owning chain span.
            chains = {}
            for kind, lit, wrap_ok in iter_sites(content):
                if not lit.interpolated:
                    continue
                if not wrap_ok:
                    continue  # component translates internally; leave for humans
                chains[(lit.expr_start, lit.expr_end)] = lit.wrapped

            edits = []
            for (s, e), was_wrapped in sorted(chains.items()):
                ln = content.count("\n", 0, s) + 1
                site = f"{rel}:{ln}"
                span_s, span_e = s, e
                src = content[s:e]
                # An already-wrapped interpolated key: convert the INSIDE and
                # replace the whole t(...) call.
                m = re.match(r'(?:Strings\.)?t(?:Sync)?\(\s*', src)
                fname = "t"
                if was_wrapped and m:
                    close = skip_balanced(content, s + m.end() - 1) if content[s + m.end() - 1] == "(" else e
                    # find the actual open paren
                    op = content.index("(", s)
                    close = skip_balanced(content, op)
                    fname = "tSync" if "tSync" in src[:m.end()] else "t"
                    inner_s = op + 1
                    inner_e = skip_arg(content, skip_trivia(content, inner_s))
                    parts = split_chain(content, inner_s, inner_e)
                    span_e = close
                else:
                    parts = split_chain(content, s, e)
                if not parts:
                    skipped["unparsable"].append(site)
                    continue
                pattern, args, problems = build(content, parts)
                if problems:
                    for p in set(problems):
                        if p != "no-prose" or set(problems) == {"no-prose"}:
                            skipped[p].append(f"{site}  {src.splitlines()[0][:80]}")
                    continue
                if not args:
                    # A chain of pure literals. If it is already inside t(…)
                    # there is nothing to convert — `t("a") + t("b")` is a
                    # legitimate translated concatenation. Only an unwrapped
                    # pure-literal chain gets merged into one key.
                    if was_wrapped:
                        continue
                    repl = f'{fname}("{pattern}")'
                else:
                    repl = f'{fname}("{pattern}", {", ".join(args)})'
                edits.append((span_s, span_e, repl, site, src))

            if not edits:
                continue
            new = content
            for s, e, repl, site, src in sorted(edits, reverse=True):
                if len(samples) < 10:
                    samples.append((site, src.replace("\n", " ")[:90], repl[:90]))
                new = new[:s] + repl + new[e:]
            converted += len(edits)
            conv_files += 1
            if not dry:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(new)

    print(f"{'would convert' if dry else 'converted'} {converted} sites in {conv_files} files")
    for site, before, after in samples:
        print(f"  {site}")
        print(f"    - {before}")
        print(f"    + {after}")
    print()
    for reason, sites in sorted(skipped.items()):
        print(f"skipped {len(sites):4d}  {reason}")
        for s in sites[:1000 if '--list' in sys.argv else 12]:
            print(f"    {s}")


if __name__ == "__main__":
    main()
