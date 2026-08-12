#!/usr/bin/env python3
"""
Extract user-facing display strings from the Compose sources.

The safety property this relies on: it only matches strings that are ALREADY at
a display site — the text argument of Text(), or a label/title/subtitle/
description/contentDescription parameter, or a prose parameter of a project
composable listed in COMPONENTS. A string that flows into a stored value never
looks like that. `trigManual("Skipped meals", sev, fav = true)` in
DeterministicMapper is a positional argument to a domain function, so it is not
matched and cannot be translated by accident. That matters because English is
the canonical value in Supabase and the key the insights engine groups on; see
the contract on LangPrefs.kt.

Display ARGUMENTS are parsed with the expression scanner in kscan.py rather
than a bare-literal regex, so the branches of `if (…) "A" else "B"`, both sides
of `?:`, and the result side of `when` branches are all extracted — condition
sides and arbitrary call arguments never are. (Ported from the iOS extractor's
scan_argument, which does the same for ternary/`??`.)

Each string is classified by how much room it has:

  hard  — cannot wrap or the layout breaks. Button and chip labels, drawer and
          tab titles, row titles. Budget is the English length plus a small
          tolerance.
  soft  — wraps freely. Body copy, descriptions, help text. Budget is generous
          and exists only to catch a translation that has run away.

Writes tools/i18n/strings.json, which is the input to both the translation step
and check_lengths.py.
"""

import json
import os
import re
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kscan import (  # noqa: E402
    IDENT, decode, scan_display_expr, skip_arg, skip_trivia,
)

SRC = "app/src/main/java/com/migraineme"
OUT = "tools/i18n/strings.json"
INTERP_OUT = "tools/i18n/interpolated.json"

# Strings the BACKEND renders. Nothing in SRC produces them, so scanning cannot
# find them, and without this merge every re-extraction would quietly drop them
# from the spec — taking their translations out of the length gate and out of
# the generated tables while the app carried on looking fully translated.
# Declared by hand, merged here, so strings.json stays the one spec.
SERVER = "tools/i18n/server_strings.json"

# Pool-item vocabulary: canonical English labels seeded into every user's
# template pools (triggers, symptoms, medicines, reliefs, …). The client
# translates them at render via t(label) while the stored value stays English,
# so no Compose source ever spells them out at a display site and scanning
# cannot find them. Same mechanism as SERVER: declared by hand, merged here,
# so they stay in front of the length gate and the generated tables.
MANUAL = "tools/i18n/manual_keys.json"

# Audit staging file: keys found by wrapping passes that still need translating,
# produced by this repo's audit sweeps and merged like MANUAL. Kept separate
# because another session owns manual_keys.json while translations land.
AUDIT = "tools/i18n/audit_missing_keys.json"

# ── Display anchors ─────────────────────────────────────────────────────────
# Named parameters that always carry display text, on ANY composable. The
# scanner is invoked on the expression after the '='.
NAMED_ANCHORS = [
    ("text", re.compile(r'\btext\s*=\s*(?![=])')),
    ("label", re.compile(r'\blabel\s*=\s*(?![=])')),
    ("title", re.compile(r'\btitle\s*=\s*(?![=])')),
    ("subtitle", re.compile(r'\bsubtitle\s*=\s*(?![=])')),
    ("description", re.compile(r'\bdescription\s*=\s*(?![=])')),
    ("contentDescription", re.compile(r'\bcontentDescription\s*=\s*(?![=])')),
    ("placeholder", re.compile(r'\bplaceholder\s*=\s*(?![=])')),
    ("message", re.compile(r'\bmessage\s*=\s*(?![=])')),
    ("question", re.compile(r'\bquestion\s*=\s*(?![=])')),
    ("emptyMessage", re.compile(r'\bemptyMessage\s*=\s*(?![=])')),
    ("hint", re.compile(r'\bhint\s*=\s*(?![=])')),
    # Monitor card explainer bodies — a project-specific parameter the original
    # extractor never knew about, leaving seven multi-paragraph card texts
    # invisible.
    ("infoBody", re.compile(r'\binfoBody\s*=\s*(?![=])')),
    ("warningText", re.compile(r'\bwarningText\s*=\s*(?![=])')),
    ("buttonLabel", re.compile(r'\bbuttonLabel\s*=\s*(?![=])')),
    ("emptyText", re.compile(r'\bemptyText\s*=\s*(?![=])')),
]

# Project composables (plus Text/Icon) whose POSITIONAL arguments carry prose.
# Maps component name -> {position: (kind, wrap)}.
#
# Built from the 2026-08-12 component-site safety audit: every entry here was
# checked to be display-only inside the component (the param reaches
# Text()/semantics and is never compared against stored data or sent to a
# callback that persists it). A component whose param doubles as a domain key
# must NOT be listed — the audited blacklist: EmptyInsightCard idx0 (logType
# drives colorForLogType + a when()), SeverityChip idx1 / ScalePicker idx1
# (compared against DB CHECK-constraint values and written via
# updateTriggerPoolItem), LegendLevel idx0 / BarcodeRiskRow idx1 /
# DialogRiskRowWithIcon idx1 ("high"/"medium"/"low" tokens feeding
# riskLevelColor), WeatherEditField idx1 (echoed into the saved weather value),
# EditableSeverityGroup idx0 ("HIGH"/"MILD"/"LOW" DB enum tokens),
# NoteMatchPill idx1 (lowercase domain tokens), MedicalDisclaimerCard idx0
# (a SharedPreferences key), FullScreenGraphScreen / JournalEditScreen idx0
# (routing/table keys).
#
# wrap=False: the component already calls t(param) internally, so the call-site
# literal must stay English (it IS the lookup key) — the extractor still
# records it so it reaches the translation tables, but codemod must not wrap it
# or the inner lookup runs on translated text and pollutes missingKeys().
COMPONENTS = {
    "Text": {0: ("text", True)},
    "Icon": {1: ("contentDescription", True)},
    # display-only params, wrapped at the call site like QCard
    "OnboardingQuestionSection": {0: ("question", True)},
    "EmptyInsightCard": {1: ("description", True)},
    "CollapsibleSection": {0: ("title", True)},
    "ReviewSectionHeader": {0: ("title", True)},
    "FavouritesPage": {0: ("title", True), 1: ("subtitle", True)},
    "SectionHeader": {0: ("title", True)},
    "FieldLabel": {0: ("label", True)},
    "MatrixAxisLabel": {0: ("label", True)},
    "NoiseBandRow": {0: ("label", True), 1: ("description", True)},
    "LegendCompound": {0: ("label", True)},
    "StatColumn": {1: ("label", False), 2: ("description", True)},
    "PainStageLegend": {0: ("title", True)},
    "TimingBucket": {0: ("title", True)},
    "ToggleButton": {0: ("label", True)},
    "FeatureBullet": {1: ("text", True)},
    "QPageHeader": {1: ("title", True), 2: ("subtitle", True)},
    "AiPoolPage": {1: ("title", True), 2: ("subtitle", True)},
    "OnboardingIconHeader": {1: ("title", True)},
    "GiftPerk": {1: ("text", True)},
    "SeverityExplainRow": {1: ("description", True)},
    "ReportSectionHeader": {0: ("title", True), 1: ("subtitle", True)},
    # components that call t(param) internally: extract the key, never wrap
    "JournalDetail": {0: ("label", False)},
    "JournalEntryHeader": {0: ("label", False)},
    "BarcodeRiskRow": {0: ("label", False)},
    "SeverityChip": {0: ("label", False)},
    "ReviewItemRow": {0: ("label", False)},
    "ScalePicker": {0: ("label", False)},
    "MetricTile": {1: ("label", False)},
    "DialogRiskRowWithIcon": {0: ("label", False)},
    "RiskMetricRow": {0: ("label", False)},
    "SummaryItem": {0: ("label", False)},
    "EditableThresholdBadge": {0: ("label", False)},
    "NoteMatchPill": {0: ("label", False)},
    "MStatColumn": {0: ("label", False)},
    "MPhaseChip": {0: ("label", False)},
    "LegendLevel": {1: ("label", False)},
    "GaugeThresholdCircle": {0: ("label", False)},
    "DStatColumn": {0: ("label", False)},
    "ActionButton": {0: ("label", False)},
    "ReviewRow": {0: ("label", False)},
    "WeatherEditField": {0: ("label", False)},
}
COMPONENT_ANCHOR = re.compile(
    r'\b(' + "|".join(sorted(COMPONENTS, key=len, reverse=True)) + r')\s*\(')

# A hand-written t("…")/tSync("…") IS a display site by definition — it is the
# translation call itself. Without it, any string the codemod cannot reach (a
# title stored on a value type lifted out of composition) renders through t()
# but never appears in strings.json, so it can never be translated and fails
# silently as English. Runs LAST and only ever ADDS a string, never
# reclassifies one. The non-identifier, non-dot lookbehind keeps it off
# `.let(`, `format(`.
LIT = r'"((?:[^"\\]|\\.)+)"'
WRAPPED = re.compile(r'(?<![A-Za-z0-9_.])t(?:Sync)?\(\s*' + LIT)

# Compose animation APIs take a `label` that names the animation for the
# tooling inspector. It is never rendered, so translating it is meaningless
# noise — that is where "navPulse", "ringAngle" and "toggleBg" came from.
# AnimatedContent and Crossfade place `label` after a multi-line transitionSpec
# or content lambda, so the call that owns it can be well above the label line.
ANIM_LABEL = re.compile(
    r'\b(?:animate[A-Za-z]*AsState|animateFloat|animateColor|animateDp|animateValue|'
    r'updateTransition|rememberInfiniteTransition|infiniteRepeatable|Transition|'
    r'AnimatedContent|AnimatedVisibility|Crossfade|transitionSpec)\b'
)
ANIM_LOOKBACK = 14

# Developer-only screens. Their text is diagnostic output for us, not the user.
SKIP_FILES = {"NutritionDiagnosticActivity.kt"}

# A Text() inside one of these is space-constrained.
HARD_CONTEXT = re.compile(
    r'\b(Button|TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|'
    r'IconButton|AssistChip|FilterChip|InputChip|SuggestionChip|Tab\(|'
    r'NavigationDrawerItem|NavigationBarItem|BottomNavigationItem|Badge)\b'
)

# These parameters are always constrained regardless of surroundings.
HARD_KINDS = {"label", "title", "subtitle", "question"}

# Not user-facing, or not translatable text. Anything matching is dropped.
SKIP = re.compile(
    r'^(?:'
    r'\s*|'                       # whitespace only
    r'[^A-Za-z]*|'                # no letters: punctuation, numbers, symbols
    r'[a-z_]+(?:_[a-z0-9_]+)+|'   # snake_case identifiers and column names
    r'https?://\S*|'              # URLs
    r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+|'  # email addresses
    r'%(?:\d+\$)?[sd@]|'          # bare format specifiers
    r'(?:\\u[0-9a-fA-F]{4})+|'    # escaped codepoints, e.g. an arrow glyph
    r'[A-Za-z]+/[A-Za-z0-9.+-]+'  # mime types and paths
    r')$'
)

# Once interpolation/placeholders are stripped, is there any actual prose left
# to translate? Rejects "$n", "$pct%", "${a} / ${b}", "%s / %s".
STRIPPED = re.compile(r'\$\{[^}]*\}?|\$[A-Za-z_][A-Za-z0-9_.]*|%(?:\d+\$)?[sd]')
WORD = re.compile(r'[A-Za-z]{2,}')

# Tolerance over the English length for a hard-constrained string. Kept tight
# because character count under-reads rendered width for caps-heavy German.
HARD_TOLERANCE = 1.10
HARD_FLOOR = 14      # very short labels get absolute headroom instead of a ratio.
SOFT_TOLERANCE = 1.45

# Below this length a string is almost certainly a label rather than prose, and
# the lookback for an enclosing Button/Chip is unreliable across line breaks.
SHORT_IS_HARD = 20


def constraint(kind, text, line, prev_lines):
    if kind in HARD_KINDS:
        return "hard"
    if kind == "contentDescription":
        return "soft"  # read aloud, never laid out
    context = line + "\n" + "\n".join(prev_lines)
    if HARD_CONTEXT.search(context):
        return "hard"
    return "hard" if len(text) <= SHORT_IS_HARD else "soft"


def budget(text, cls):
    n = len(text)
    if cls == "hard":
        return max(int(n * HARD_TOLERANCE), n + 3, HARD_FLOOR)
    return max(int(n * SOFT_TOLERANCE), n + 10)


def iter_sites(content):
    """Yield (kind, Lit, wrap_ok) for every display literal in a file, deduped
    by span. wrap_ok=False marks a site the codemod must leave alone (the
    component translates internally; the literal is the lookup key).

    Sources, in priority order: component positional arguments (so a no-wrap
    verdict wins), then named display parameters, then bare t()/tSync() wraps
    nowhere else recognised.
    """
    seen = set()

    def emit(kind, lits, wrap_ok):
        for l in lits:
            if l.start in seen:
                continue
            seen.add(l.start)
            yield kind, l, wrap_ok

    for m in COMPONENT_ANCHOR.finditer(content):
        name = m.group(1)
        spec = COMPONENTS[name]
        i = m.end()
        idx = 0
        while True:
            i = skip_trivia(content, i)
            if i >= len(content) or content[i] in ")}":
                break
            # named argument?
            argname = None
            vstart = i
            im = IDENT.match(content, i)
            if im:
                j = skip_trivia(content, im.end())
                if j < len(content) and content[j] == "=" and not content.startswith("==", j):
                    argname = im.group(0)
                    vstart = skip_trivia(content, j + 1)
            if argname is None and idx in spec:
                kind, wrap_ok = spec[idx]
                lits = []
                scan_display_expr(content, vstart, lits)
                yield from emit(kind, lits, wrap_ok)
            i = skip_arg(content, vstart)
            i = skip_trivia(content, i)
            if i < len(content) and content[i] == ",":
                i += 1
                idx += 1
                continue
            break

    for kind, pat in NAMED_ANCHORS:
        for m in pat.finditer(content):
            lits = []
            scan_display_expr(content, m.end(), lits)
            yield from emit(kind, lits, True)


def main():
    root = os.getcwd()
    if not os.path.isdir(os.path.join(root, SRC)):
        sys.exit(f"run from the repo root; {SRC} not found")

    entries = OrderedDict()
    interpolated = OrderedDict()
    # Wrapped-but-unrecognised sites, held back until every display-site
    # anchor has had its say. See WRAPPED.
    orphans = OrderedDict()
    files = 0

    for dirpath, _, filenames in os.walk(os.path.join(root, SRC)):
        for fn in sorted(filenames):
            if not fn.endswith(".kt"):
                continue
            # Our own machinery must not feed itself.
            if fn in ("Strings.kt", "LangPrefs.kt") or fn.startswith("Strings"):
                continue
            if fn in SKIP_FILES:
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            files += 1
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
            lines = content.splitlines(keepends=True)

            def line_of(pos):
                return content.count("\n", 0, pos) + 1

            for kind, lit, _wrap_ok in iter_sites(content):
                raw = decode(lit.raw)
                ln = line_of(lit.start)
                i = ln - 1
                line = lines[i] if i < len(lines) else ""
                if line.lstrip().startswith("//"):
                    continue
                prev = lines[max(0, i - ANIM_LOOKBACK):i]
                if kind == "label" and ANIM_LABEL.search(line + "\n" + "".join(prev)):
                    continue
                if lit.interpolated:
                    # Needs converting to positional-placeholder form by hand
                    # or by convert_interpolated.py, since word order moves in
                    # translation. Diverted to its own file.
                    if WORD.search(STRIPPED.sub("", raw)):
                        interpolated.setdefault(f"{rel}:{ln}", line.rstrip())
                    continue
                if SKIP.match(raw):
                    continue
                if not WORD.search(STRIPPED.sub("", raw)):
                    continue
                cls = constraint(kind, raw, line, lines[max(0, i - 6):i])
                e = entries.setdefault(raw, {"en": raw, "class": cls, "sites": []})
                # A string used anywhere constrained is constrained everywhere,
                # since one map serves every site.
                if cls == "hard":
                    e["class"] = "hard"
                e["sites"].append(f"{rel}:{ln}")

            # Second pass over the same file: wrapped strings no display-site
            # anchor claimed. Collected now, merged after the whole walk, so a
            # site recognised in a later file still wins.
            for m in WRAPPED.finditer(content):
                raw = decode(m.group(1))
                if SKIP.match(raw):
                    continue
                ln = line_of(m.start(1))
                i = ln - 1
                line = lines[i] if i < len(lines) else ""
                if line.lstrip().startswith("//"):
                    continue
                if "${" in raw or re.search(r'\$[A-Za-z_]', raw):
                    if WORD.search(STRIPPED.sub("", raw)):
                        interpolated.setdefault(f"{rel}:{ln}", line.rstrip())
                    continue
                if not WORD.search(STRIPPED.sub("", raw)):
                    continue
                cls = constraint("wrapped", raw, line, lines[max(0, i - 6):i])
                o = orphans.setdefault(raw, {"en": raw, "class": cls, "sites": []})
                if cls == "hard":
                    o["class"] = "hard"
                o["sites"].append(f"{rel}:{ln}")

    for raw, o in orphans.items():
        if raw not in entries:
            entries[raw] = o
        else:
            e = entries[raw]
            known = {s for s in e["sites"] if not s.startswith("...")}
            for s in o["sites"]:
                if s not in known:
                    e["sites"].append(s)

    for e in entries.values():
        e["max_len"] = budget(e["en"], e["class"])
        e["count"] = len(e["sites"])
        # Keep the file readable; the full list is rarely what you want.
        if len(e["sites"]) > 5:
            e["sites"] = e["sites"][:5] + [f"... {len(e['sites']) - 5} more"]

    # Backend strings, merged after the derived budgets so their hand-set
    # max_len survives. A key already present in the Compose sources keeps the
    # scanned entry: the tightest real call site is the one that has to fit.
    counters = {}
    for spec_path, counter in ((SERVER, "server"), (MANUAL, "manual"), (AUDIT, "audit")):
        counters[counter] = 0
        full_path = os.path.join(root, spec_path)
        if not os.path.isfile(full_path):
            continue
        with open(full_path, encoding="utf-8") as fh:
            hand_spec = json.load(fh)
        if isinstance(hand_spec, dict):
            hand_entries = hand_spec.get("entries", [])
        else:
            hand_entries = hand_spec
        merged = 0
        for e in hand_entries:
            if e["en"] in entries:
                continue
            entries[e["en"]] = {
                "en": e["en"],
                "class": e["class"],
                "sites": list(e.get("sites", [])),
                "max_len": e.get("max_len", budget(e["en"], e["class"])),
                "count": len(e.get("sites", [])) or 1,
            }
            merged += 1
        counters[counter] = merged

    ordered = sorted(entries.values(), key=lambda e: (-e["count"], e["en"]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(ordered, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    with open(INTERP_OUT, "w", encoding="utf-8") as fh:
        json.dump(
            [{"site": k, "line": v} for k, v in sorted(interpolated.items())],
            fh, ensure_ascii=False, indent=2,
        )
        fh.write("\n")

    hard = sum(1 for e in ordered if e["class"] == "hard")
    sites = sum(e["count"] for e in ordered)
    print(f"scanned   {files} files")
    print(f"server    {counters['server']} backend strings merged from {SERVER}")
    print(f"manual    {counters['manual']} hand-declared strings merged from {MANUAL}")
    print(f"audit     {counters['audit']} audit-staged strings merged from {AUDIT}")
    print(f"unique    {len(ordered)} strings across {sites} sites")
    print(f"hard      {hard}")
    print(f"soft      {len(ordered) - hard}")
    print(f"deferred  {len(interpolated)} interpolated sites -> {INTERP_OUT}")
    print(f"wrote     {OUT}")


if __name__ == "__main__":
    main()
