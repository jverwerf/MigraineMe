#!/usr/bin/env python3
"""
Kotlin display-expression scanner shared by extract.py, codemod.py and
convert_interpolated.py.

The old extractor matched `Text(\s*"literal"` with a regex, which is blind to
every site where the display argument is an EXPRESSION rather than a bare
literal:

    Text(if (selected.isEmpty()) "Select all that apply" else joined)
    Text(x ?: "Not found")
    Text(when (zone) { ... -> "Low" ... })
    Text("Prefix " + suffix)

This scanner parses the argument expression far enough to find every string
literal that can only ever be DISPLAYED — the result branches of if/else and
when, both sides of ?:, the parts of a + chain. It deliberately never descends
into a condition (`if (x == "stored")`), a when branch's match side
(`"stored" -> …`), or the arguments of an arbitrary function call, because a
literal there is a domain value, not display text. That is the same safety
property the regex version had, made explicit. (Ported from the iOS sibling's
scan_argument, which splits ternary/?? arguments the same way.)

A literal already wrapped in t(…)/tSync(…) is still reported (with wrapped=True)
so extraction stays idempotent and codemod never wraps twice.

Interpolated strings ("${x} attacks") and mixed +-chains are reported as
interpolated sites with their full expression span, which is what
convert_interpolated.py rewrites into t("%s attacks", x) form.
"""

import re

IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')

# Kotlin escape sequences, as they appear in SOURCE. The key has to be the
# string the app holds at RUNTIME, because that is what t() is handed.
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "$": "$"}


def decode(raw):
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c != "\\" or i + 1 >= len(raw):
            out.append(c)
            i += 1
            continue
        nxt = raw[i + 1]
        if nxt == "u" and i + 5 < len(raw):
            try:
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            except ValueError:
                pass
        out.append(ESCAPES.get(nxt, nxt))
        i += 2
    return "".join(out)


def skip_trivia(c, i):
    n = len(c)
    while i < n:
        ch = c[i]
        if ch in " \t\r\n":
            i += 1
        elif c.startswith("//", i):
            j = c.find("\n", i)
            i = n if j < 0 else j + 1
        elif c.startswith("/*", i):
            j = c.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            break
    return i


def scan_string(c, i):
    """Parse a string literal starting at c[i] == '\"'.

    Returns (end, parts, has_interp) where end is the index just past the
    closing quote and parts is a list of ('lit', source_text) /
    ('expr', source_text) segments. Returns None if c[i] is not a quote or the
    literal is unterminated.
    """
    n = len(c)
    if i >= n or c[i] != '"':
        return None
    triple = c.startswith('"""', i)
    q = '"""' if triple else '"'
    i += len(q)
    parts = []
    lit = []
    has_interp = False

    def flush():
        if lit:
            parts.append(("lit", "".join(lit)))
            del lit[:]

    while i < n:
        if c.startswith(q, i):
            flush()
            return i + len(q), parts, has_interp
        ch = c[i]
        if ch == "\\" and not triple and i + 1 < n:
            lit.append(c[i:i + 2])
            i += 2
            continue
        if ch == "$" and i + 1 < n:
            if c[i + 1] == "{":
                # Balanced-brace expression that may itself contain strings.
                depth = 1
                j = i + 2
                while j < n and depth:
                    cj = c[j]
                    if cj == '"':
                        sub = scan_string(c, j)
                        if sub is None:
                            j += 1
                            continue
                        j = sub[0]
                        continue
                    if cj == "{":
                        depth += 1
                    elif cj == "}":
                        depth -= 1
                    j += 1
                flush()
                parts.append(("expr", c[i + 2:j - 1]))
                has_interp = True
                i = j
                continue
            m = IDENT.match(c, i + 1)
            if m:
                flush()
                parts.append(("expr", m.group(0)))
                has_interp = True
                i = m.end()
                continue
        lit.append(ch)
        i += 1
    return None  # unterminated


BRACKETS = {"(": ")", "[": "]", "{": "}"}


def skip_balanced(c, i):
    """c[i] is an opening bracket; return index just past its close."""
    n = len(c)
    close = BRACKETS[c[i]]
    opener = c[i]
    depth = 1
    i += 1
    while i < n and depth:
        ch = c[i]
        if ch == '"':
            s = scan_string(c, i)
            i = s[0] if s else i + 1
            continue
        if c.startswith("//", i):
            j = c.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c.startswith("/*", i):
            j = c.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if ch == opener:
            depth += 1
        elif ch == close:
            depth -= 1
        i += 1
    return i


def skip_arg(c, i):
    """Skip one call argument: stop at a top-level ',' or ')' (or '}')."""
    n = len(c)
    while i < n:
        ch = c[i]
        if ch in ",)}":
            return i
        if ch == '"':
            s = scan_string(c, i)
            i = s[0] if s else i + 1
            continue
        if ch in BRACKETS:
            i = skip_balanced(c, i)
            continue
        if c.startswith("//", i):
            j = c.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c.startswith("/*", i):
            j = c.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        i += 1
    return i


def _word_at(c, i, word):
    return c.startswith(word, i) and not IDENT.match(c, i + len(word))


class Lit:
    """One string literal found in a display position."""
    __slots__ = ("start", "end", "raw", "wrapped", "interpolated", "expr_start", "expr_end")

    def __init__(self, start, end, raw, wrapped, interpolated, expr_start, expr_end):
        self.start = start          # index of opening quote
        self.end = end              # index just past closing quote
        self.raw = raw              # source text between the quotes
        self.wrapped = wrapped      # already inside t(…)/tSync(…)
        self.interpolated = interpolated
        self.expr_start = expr_start  # span of the whole owning expression,
        self.expr_end = expr_end      # for interpolated conversion


def scan_display_expr(c, i, out, wrapped=False):
    """Scan the display expression starting at i, appending Lit descriptors to
    `out`. Returns the index just past the expression.

    expr := chain ('?:' chain)*
    chain := term ('+' term)*        # a chain with more than one part is a
                                     # concatenation: one interpolated site,
                                     # not N fragments
    """
    def scan_chain(i):
        """Returns (end, parts): the + chain, each part one of
        ('lit', Lit)      — a direct string literal
        ('final', [Lit…]) — literals already fully classified by a recursive
                            scan (if/when branches, parens, t(…) keys)
        ('expr', None)    — an opaque runtime value
        """
        parts = []
        i = scan_term(c, i, parts, wrapped)
        while True:
            j = skip_trivia(c, i)
            if j < len(c) and c[j] == "+" and not c.startswith("++", j):
                i = scan_term(c, skip_trivia(c, j + 1), parts, wrapped)
            else:
                return i, parts

    i = skip_trivia(c, i)
    end, parts = scan_chain(i)
    emit_chain(c, i, end, parts, out)
    while True:
        j = skip_trivia(c, end)
        if c.startswith("?:", j):
            k = skip_trivia(c, j + 2)
            end, parts = scan_chain(k)
            emit_chain(c, k, end, parts, out)
        else:
            return end


def emit_chain(c, cstart, cend, parts, out):
    def all_lits():
        for p in parts:
            if p[0] == "lit":
                yield p[1]
            elif p[0] == "final":
                yield from p[1]

    if len(parts) > 1:
        # Concatenation: every literal in it is a fragment of one runtime
        # string. Mark the whole chain as one interpolated site.
        for l in all_lits():
            l.interpolated = True
            l.expr_start, l.expr_end = cstart, cend
            out.append(l)
        return
    if not parts:
        return
    tag, val = parts[0]
    if tag == "lit":
        if val.interpolated:
            val.expr_start, val.expr_end = cstart, cend
        out.append(val)
    elif tag == "final":
        # Branch literals keep the flags their own (recursive) chain gave them.
        out.extend(val)


def scan_term(c, i, parts, wrapped):
    """One term of a + chain. Appends ('lit', Lit) / ('expr', None) to parts."""
    n = len(c)
    i = skip_trivia(c, i)
    if i >= n:
        return i

    # if (cond) A else B — never descend into the condition.
    if _word_at(c, i, "if"):
        j = skip_trivia(c, i + 2)
        if j < n and c[j] == "(":
            j = skip_balanced(c, j)
            lits = []
            j = _scan_branch(c, j, lits, wrapped)
            k = skip_trivia(c, j)
            if _word_at(c, k, "else"):
                j = _scan_branch(c, k + 4, lits, wrapped)
            parts.append(("final", lits))
            return j
    # when (subj) { a -> A; else -> B } — only the result side of each branch.
    if _word_at(c, i, "when"):
        j = skip_trivia(c, i + 4)
        if j < n and c[j] == "(":
            j = skip_trivia(c, skip_balanced(c, j))
        if j < n and c[j] == "{":
            body_end = skip_balanced(c, j)
            lits = []
            k = j + 1
            while k < body_end - 1:
                k = skip_trivia(c, k)
                if k >= body_end - 1:
                    break
                # condition side: skip to '->' at depth 0
                while k < body_end - 1 and not c.startswith("->", k):
                    ch = c[k]
                    if ch == '"':
                        s = scan_string(c, k)
                        k = s[0] if s else k + 1
                    elif ch in BRACKETS:
                        k = skip_balanced(c, k)
                    else:
                        k += 1
                if k >= body_end - 1:
                    break
                k = _scan_branch(c, k + 2, lits, wrapped)
            parts.append(("final", lits))
            return body_end
    # parenthesised sub-expression
    if c[i] == "(":
        end = skip_balanced(c, i)
        sub = []
        scan_display_expr(c, i + 1, sub, wrapped)
        parts.append(("final", sub))
        return end
    # negation etc.
    if c[i] == "!" and not c.startswith("!=", i):
        parts.append(("expr", None))
        return _skip_primary(c, i + 1)
    # string literal
    if c[i] == '"':
        s = scan_string(c, i)
        if s is None:
            parts.append(("expr", None))
            return i + 1
        end, segs, has_interp = s
        qlen = 3 if c.startswith('"""', i) else 1
        raw = c[i + qlen:end - qlen]
        parts.append(("lit", Lit(i, end, raw, wrapped, has_interp, i, end)))
        return end

    m = IDENT.match(c, i)
    if m:
        name = m.group(0)
        # t("…") / tSync("…") / Strings.t("…"): descend so the key is still
        # extracted, but marked wrapped.
        callee = name
        j = m.end()
        if name == "Strings":
            k = skip_trivia(c, j)
            if k < n and c[k] == ".":
                m2 = IDENT.match(c, skip_trivia(c, k + 1))
                if m2 and m2.group(0) in ("t", "tSync"):
                    callee = m2.group(0)
                    j = m2.end()
        if callee in ("t", "tSync"):
            k = skip_trivia(c, j)
            if k < n and c[k] == "(":
                close = skip_balanced(c, k)
                sub = []
                scan_display_expr(c, k + 1, sub, True)
                # t("key %s", args): the key literal is what gets extracted;
                # the format arguments after the comma are opaque runtime
                # values and must not make the key look concatenated.
                parts.append(("final", sub) if sub else ("expr", None))
                return _skip_postfix(c, close)
        parts.append(("expr", None))
        return _skip_primary(c, i)

    # number or anything else: single token
    parts.append(("expr", None))
    while i < n and c[i] not in ' \t\r\n,)}]+?:':
        i += 1
    return i


def _scan_branch(c, i, lits, wrapped):
    """An if/when result branch, collecting fully-classified literals into
    `lits`. A { block } branch is skipped opaque — display args never carry
    statement blocks worth mining, and a block's last expression is beyond
    this scanner."""
    i = skip_trivia(c, i)
    if i < len(c) and c[i] == "{":
        return skip_balanced(c, i)
    return scan_display_expr(c, i, lits, wrapped)


def _skip_primary(c, i):
    """Skip an identifier/call/property chain: a.b(x).c[0].let { … }!!."""
    n = len(c)
    m = IDENT.match(c, i)
    if m:
        i = m.end()
    while i < n:
        j = skip_trivia(c, i)
        if j < n and c[j] in "([{":
            # a trailing lambda or call args; opaque
            i = skip_balanced(c, j)
            continue
        if c.startswith("!!", j):
            i = j + 2
            continue
        if c.startswith("?.", j):
            m = IDENT.match(c, skip_trivia(c, j + 2))
            i = m.end() if m else j + 2
            continue
        if j < n and c[j] == "." and not c.startswith("..", j):
            m = IDENT.match(c, skip_trivia(c, j + 1))
            i = m.end() if m else j + 1
            continue
        return i
    return i


def _skip_postfix(c, i):
    """After a closed call: allow .something chains (rare on t())."""
    return _skip_primary(c, i)
