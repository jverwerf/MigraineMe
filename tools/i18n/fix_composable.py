#!/usr/bin/env python3
"""
Convert t() to tSync() wherever the Kotlin compiler says composition is not
available.

codemod.py deliberately wraps every display site in the @Composable t(), then
leans on kotlinc to identify the ones that were wrong. This reads those errors
and rewrites exactly those call sites. Four diagnostics all mean the same thing:

  @Composable invocations can only happen from the context of …
  Functions which invoke @Composable functions must be marked …
  Composable calls are not allowed inside the calculation parameter of remember
  Try catch is not supported around composable function invocations

Only positions where the source actually reads "t(" are rewritten. The
"Functions which invoke" diagnostic points at the enclosing declaration rather
than a call, and it clears itself once the calls inside it are fixed.

Runs the compile itself and repeats until the errors stop changing, because
fixing one site can reveal another in the same function.
"""

import os
import re
import subprocess
import sys
from collections import defaultdict

ERR = re.compile(r"^e: file://(?P<path>[^:]+):(?P<line>\d+):(?P<col>\d+) (?P<msg>.+)$")

TRIGGERS = (
    "@Composable invocations can only happen",
    "Functions which invoke @Composable functions",
    "Composable calls are not allowed inside the calculation parameter",
    "Try catch is not supported around composable function invocations",
)

MAX_ROUNDS = 8


def compile_errors():
    p = subprocess.run(
        ["./gradlew", ":app:compileDebugKotlin", "-q"],
        capture_output=True, text=True,
    )
    out = p.stdout + p.stderr
    errors = []
    for line in out.splitlines():
        m = ERR.match(line.strip())
        if m:
            errors.append(m.groupdict())
    return p.returncode, errors, out


def apply(errors):
    by_file = defaultdict(list)
    for e in errors:
        if any(t in e["msg"] for t in TRIGGERS):
            by_file[e["path"]].append((int(e["line"]), int(e["col"])))

    fixed = 0
    for path, positions in by_file.items():
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
        # Right to left so earlier columns keep their offsets.
        for ln, col in sorted(set(positions), reverse=True):
            i = ln - 1
            if i >= len(lines):
                continue
            line = lines[i]
            c = col - 1
            if line[c:c + 2] == "t(":
                lines[i] = line[:c] + "tSync(" + line[c + 2:]
                fixed += 1
            elif line[c:c + 6] == "tSync(":
                continue  # already done in an earlier round
        with open(path, "w", encoding="utf-8") as fh:
            fh.writelines(lines)
    return fixed


def main():
    if not os.path.isfile("./gradlew"):
        sys.exit("run from the repo root")

    for rnd in range(1, MAX_ROUNDS + 1):
        code, errors, out = compile_errors()
        if code == 0:
            print(f"round {rnd}: compiles clean")
            return
        relevant = [e for e in errors if any(t in e["msg"] for t in TRIGGERS)]
        print(f"round {rnd}: {len(errors)} errors, {len(relevant)} composability")
        if not relevant:
            print("\nremaining errors are not composability related:\n")
            for e in errors[:25]:
                print(f"  {os.path.basename(e['path'])}:{e['line']} {e['msg'][:100]}")
            sys.exit(1)
        n = apply(relevant)
        print(f"           rewrote {n} call sites to tSync()")
        if n == 0:
            print("no progress; stopping")
            sys.exit(1)

    print("hit round limit")
    sys.exit(1)


if __name__ == "__main__":
    main()
