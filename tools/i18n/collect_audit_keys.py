#!/usr/bin/env python3
"""
Produce tools/i18n/audit_missing_keys.json: every English key that currently
needs a translation, from two sources:

1. strings.json entries with no entry in translations/de.json (de is the
   reference table; all six languages are translated together).
2. Harvested data-table values — strings that reach the screen through a
   variable wrapped at RENDER (drawer/tab/title tables, enum display labels,
   metric label maps, tour copy, device catalogue, onboarding tables). The
   extractor cannot see these because no display anchor ever holds the
   literal; they are declared here so they stay in the spec (extract.py merges
   this file the same way it merges manual_keys.json).

This file is a staging area: another session owns manual_keys.json while the
pool-label translation run lands, so audit keys are kept separate and merged
after it finishes.
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kscan import decode  # noqa: E402

SRC = "app/src/main/java/com/migraineme"
OUT = "tools/i18n/audit_missing_keys.json"
LIT = re.compile(r'"((?:[^"\\]|\\.)+)"')
WORDY = re.compile(r'[A-Za-z]{2,}')


def read(fn):
    with open(os.path.join(SRC, fn), encoding="utf-8") as fh:
        return fh.read()


def span(content, start_marker, end_marker):
    i = content.index(start_marker)
    j = content.index(end_marker, i)
    return content[i:j], content.count("\n", 0, i) + 1


SNAKE = re.compile(r'^[a-z]+(?:_[a-z0-9]+)+$')


def lits(text, exclude=()):
    out = []
    for m in LIT.finditer(text):
        v = decode(m.group(1))
        if "$" in m.group(1):
            continue
        if not WORDY.search(v):
            continue
        if SNAKE.match(v):  # route ids, highlight targets, column names
            continue
        if v in exclude:
            continue
        out.append(v)
    return out


def harvest():
    """Returns list of (key, class, site, note)."""
    found = []

    def add(keys, cls, site, note):
        for k in keys:
            found.append((k, cls, site, note))

    # MainActivity: drawer, bottom nav, top-bar titles, pool categories
    c = read("MainActivity.kt")
    s, ln = span(c, "val drawerItems = listOf(", ")\n")
    add(lits(s), "hard", f"{SRC}/MainActivity.kt:{ln}", "drawer titles, t(item.title) at render")
    add(re.findall(r'BottomItem\(Routes\.\w+, "([^"]+)"', c), "hard",
        f"{SRC}/MainActivity.kt", "bottom nav labels, t(item.label) at render")
    s, ln = span(c, "val titleText = when (current) {", "Text(t(titleText))")
    add(lits(s, exclude=("monitor_treatments_config", "help_article", "logType")), "hard",
        f"{SRC}/MainActivity.kt:{ln}", "top bar titles, t(titleText) at render")
    for m in re.finditer(r'categories = listOf\(([^)]*)\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add(lits(m.group(1)), "hard", f"{SRC}/MainActivity.kt:{ln2}",
            "pool category chips, t(cat) at render")

    # RiskZone labels
    c = read("HomeViewModel.kt")
    s, ln = span(c, "enum class RiskZone", "}")
    add(lits(s), "hard", f"{SRC}/HomeViewModel.kt:{ln}", "gauge zone labels, t(riskZone.label)")

    # QuickLogCategory labels
    c = read("QuickLogStrip.kt")
    s, ln = span(c, "enum class QuickLogCategory", "}")
    add(lits(s), "hard", f"{SRC}/QuickLogStrip.kt:{ln}", "quick log strip, t(category.label)")

    # Tour copy
    c = read("OnboardingCoach.kt")
    s, ln = span(c, "val tourSteps = listOf(", "@Composable")
    add(lits(s), "soft", f"{SRC}/OnboardingCoach.kt:{ln}", "tour steps, t(step.title/body/highlight)")

    # Onboarding question/data tables
    c = read("OnboardingQuestions.kt")
    for m in re.finditer(r'SeverityQuestion\(\s*"[^"]*",\s*"([^"]+)",\s*"([^"]+)"', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/OnboardingQuestions.kt:{ln2}", "question displayName")
        add([m.group(2)], "soft", f"{SRC}/OnboardingQuestions.kt:{ln2}", "question description")
    for m in re.finditer(r'DataCollectionItem\(\s*"[^"]*",\s*"([^"]+)",\s*"([^"]+)"', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/OnboardingQuestions.kt:{ln2}", "data item displayName")
        add([m.group(2)], "soft", f"{SRC}/OnboardingQuestions.kt:{ln2}", "data item description")
    for m in re.finditer(r'DataCollectionGroup\(\s*"([^"]+)"', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/OnboardingQuestions.kt:{ln2}", "data group title")
    s, ln = span(c, "enum class SeverityChoice", "}")
    add(lits(s), "hard", f"{SRC}/OnboardingQuestions.kt:{ln}", "severity choice labels")

    # Onboarding option lists + steps + wearable pairs
    c = read("OnboardingPages.kt")
    for marker in ("val steps = listOf(",):
        s, ln = span(c, marker, "var revealedSteps")
        add(lits(s), "soft", f"{SRC}/OnboardingPages.kt:{ln}", "how-it-works steps")
    for m in re.finditer(r'listOf\(((?:\s*"[^"]+"(?:\s+to\s+"[^"]+")?\s*,?)+)\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add(lits(m.group(1)), "hard", f"{SRC}/OnboardingPages.kt:{ln2}",
            "onboarding choice options, t(label) in chip")

    # AI setup chips + certainty items
    c = read("AiSetupQuestions.kt")
    for m in re.finditer(r'listOf\(((?:\s*t?\(?"[^"]+"\)?\s*,?)+)\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add(lits(m.group(1)), "hard", f"{SRC}/AiSetupQuestions.kt:{ln2}", "AI setup chip options")
    for f2 in ("AiSetupQuestions.kt", "CertaintyMultiSelect.kt"):
        c2 = read(f2)
        for m in re.finditer(r'CertaintyItem\("[^"]*",\s*"([^"]+)"(?:,\s*"([^"]+)")?', c2):
            ln2 = c2.count("\n", 0, m.start()) + 1
            add([m.group(1)], "hard", f"{SRC}/{f2}:{ln2}", "certainty item label")
            if m.group(2):
                add([m.group(2)], "soft", f"{SRC}/{f2}:{ln2}", "certainty item description")

    # Devices catalogue
    c = read("DevicesScreen.kt")
    for field, cls in (("blurb", "soft"), ("what", "soft"), ("evidence", "soft"),
                       ("communitySource", "hard"), ("linkNote", "hard"), ("safetyNote", "soft")):
        for m in re.finditer(field + r'\s*=\s*"((?:[^"\\]|\\.)+)"', c):
            if "$" in m.group(1):
                continue
            ln2 = c.count("\n", 0, m.start()) + 1
            add([decode(m.group(1))], cls, f"{SRC}/DevicesScreen.kt:{ln2}", f"device {field}")
    for field in ("communityPros", "communityCons"):
        for m in re.finditer(field + r'\s*=\s*listOf\(([^)]*)\)', c, re.S):
            ln2 = c.count("\n", 0, m.start()) + 1
            add(lits(m.group(1)), "soft", f"{SRC}/DevicesScreen.kt:{ln2}", f"device {field}")

    # Metric label tables
    for fn, marker_end in (("SleepCardConfig.kt", None), ("PhysicalCardConfig.kt", None),
                           ("MentalCardConfig.kt", None)):
        c = read(fn)
        s, ln = span(c, "private fun rawLabelFor(metric: String): String = when (metric) {", "}")
        add(lits(s), "hard", f"{SRC}/{fn}:{ln}", "metric label (labelFor)")
    c = read("MentalCardConfig.kt")
    s, ln = span(c, "fun noiseLabel(", "}")
    add(lits(s), "hard", f"{SRC}/MentalCardConfig.kt:{ln}", "noise band label")
    c = read("WeatherCardConfig.kt")
    s, ln = span(c, "WEATHER_METRIC_LABELS", ")\n")
    add([v for _, v in re.findall(r'"([^"]+)" to "([^"]+)"', s)], "hard",
        f"{SRC}/WeatherCardConfig.kt:{ln}", "weather metric label")
    c = read("MonitorCardConfig.kt")
    for name in ("NUTRITION_METRIC_LABELS", "CARD_LABELS"):
        s, ln = span(c, name, ")\n")
        add([v for _, v in re.findall(r'"([^"]+)" to "([^"]+)"', s)], "hard",
            f"{SRC}/MonitorCardConfig.kt:{ln}", name)
    c = read("MetricRegistry.kt")
    s, ln = span(c, "LABEL_OVERRIDES", ")\n")
    add([v for _, v in re.findall(r'"([^"]+)"(?:\s*|\s*::[^"]*)to "([^"]+)"', s)], "hard",
        f"{SRC}/MetricRegistry.kt:{ln}", "metric label override")

    # Weather conditions
    c = read("MonitorScreen.kt")
    s, ln = span(c, "fun weatherCodeToCondition", "}")
    add(lits(s), "hard", f"{SRC}/MonitorScreen.kt:{ln}", "weather condition, t(condition)")
    s, ln = span(c, "private fun noiseSlotColor", "}")
    add(lits(s), "hard", f"{SRC}/MonitorScreen.kt:{ln}", "noise band values shown on card")

    # Scale/status enums
    c = read("SideEffectChips.kt")
    s, ln = span(c, "enum class SideEffectScale", "}")
    add(lits(s), "hard", f"{SRC}/SideEffectChips.kt:{ln}", "side effect scale, t(scale.display)")
    c = read("ManagePoolScreen.kt")
    s, ln = span(c, "enum class PredictionValue", "}")
    add(lits(s), "hard", f"{SRC}/ManagePoolScreen.kt:{ln}", "prediction value, t(value.display)")
    try:
        c = read("ReliefScale.kt")
        add(lits(c), "hard", f"{SRC}/ReliefScale.kt", "relief scale display")
    except FileNotFoundError:
        pass
    c = read("SupabaseProfileService.kt")
    s, ln = span(c, "enum class MigraineType", "}")
    add([v for v in lits(s) if not v.islower()], "hard",
        f"{SRC}/SupabaseProfileService.kt:{ln}", "migraine type label (dbValue stays English)")
    c = read("InsightsViewModel.kt")
    s, ln = span(c, "enum class TimeFrame", "}")
    add(lits(s), "hard", f"{SRC}/InsightsViewModel.kt:{ln}", "time frame label")
    c = read("DataSettingsRowComponents.kt")
    s, ln = span(c, "enum class GaugeAlertLevel", "companion object")
    add(lits(s), "soft", f"{SRC}/DataSettingsRowComponents.kt:{ln}", "gauge alert labels+descriptions")
    s, ln = span(c, "enum class OngoingReminderInterval", "companion object")
    add(lits(s), "hard", f"{SRC}/DataSettingsRowComponents.kt:{ln}", "reminder interval labels")
    add(["Remind me every %s while a migraine is still open"], "soft",
        f"{SRC}/DataSettingsRowComponents.kt:{ln}", "reminder description template")

    # Sort/filter option lists
    for fn in ("InsightsScreen.kt", "InsightsImpactScreen.kt", "InsightsTreatmentsScreen.kt",
               "InsightsPatternsScreen.kt"):
        c = read(fn)
        for m in re.finditer(r'listOf\(\s*("(?:Highest risk|Most frequent|Most effective|Most severe|Strongest effect|Days before)[^)]*)\)', c):
            ln2 = c.count("\n", 0, m.start()) + 1
            add(lits(m.group(1)), "hard", f"{SRC}/{fn}:{ln2}", "sort options, t(mode) in menu")
    c = read("JournalScreen.kt")
    s, ln = span(c, "val typeFilters", ")\n")
    add(lits(s), "hard", f"{SRC}/JournalScreen.kt:{ln}", "journal type filters")
    add(["All", "Manual"], "hard", f"{SRC}/JournalScreen.kt:244", "source filter chips")
    c = read("FullScreenGraphScreen.kt")
    s, ln = span(c, "val rangeOptions", ")\n")
    add(lits(s), "hard", f"{SRC}/FullScreenGraphScreen.kt:{ln}", "graph range chips")
    c = read("MonitorMedicineScreen.kt")
    add(["Day", "Week", "Month", "By category"], "hard", f"{SRC}/MonitorMedicineScreen.kt:622",
        "medicine usage table")
    c = read("ManageSymptomsScreen.kt")
    for m in re.finditer(r'->\s*"([^"$]+)"', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        v = m.group(1)
        if WORDY.search(v):
            add([v], "hard", f"{SRC}/ManageSymptomsScreen.kt:{ln2}", "symptom category display")

    # Community tabs
    add(["Articles", "Forum", "Blogs", "For You", "Latest", "Browse"], "hard",
        f"{SRC}/CommunityScreen.kt:101", "community tabs, t(label) in SegmentedTabRow")

    # Seasons + hub labels + treatment strip
    add(["Winter", "Spring", "Summer", "Autumn"], "hard",
        f"{SRC}/InsightsReportScreen.kt:697", "season labels")
    c = read("RiskHistoryGraph.kt")
    s, ln = span(c, "val hubTypeLabels", ")\n")
    add(lits(s), "hard", f"{SRC}/RiskHistoryGraph.kt:{ln}", "graph tooltip type label")
    s, ln = span(c, "val catOrder", ")\n")
    add(lits(s), "hard", f"{SRC}/RiskHistoryGraph.kt:{ln}", "graph legend categories")
    c = read("MonitorTreatmentDetailScreen.kt")
    s, ln = span(c, 'val labels = listOf(', ")\n")
    add(lits(s), "hard", f"{SRC}/MonitorTreatmentDetailScreen.kt:{ln}", "clinical band labels")
    c = read("EveningCheckInScreen.kt")
    s, ln = span(c, "val TREATMENT_SIDE_EFFECT_POOL", ")\n")
    add(lits(s), "hard", f"{SRC}/EveningCheckInScreen.kt:{ln}", "treatment side effect chips")
    add(["+ other"], "hard", f"{SRC}/MonitorTreatmentDetailScreen.kt:1022", "side effect chip")

    # LogHomeScreen severity triple
    c = read("LogHomeScreen.kt")
    for m in re.finditer(r'Triple\("[A-Z]+",\s*"([^"]+)",\s*"([^"]+)"\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/LogHomeScreen.kt:{ln2}", "severity title")
        add([m.group(2)], "soft", f"{SRC}/LogHomeScreen.kt:{ln2}", "severity blurb")

    # Pain locations (stored values — render-only translation)
    c = read("PaintThePictureScreen.kt")
    s, ln = span(c, "val painLocationOptions", "// ── AI state")
    add(lits(s), "hard", f"{SRC}/PaintThePictureScreen.kt:{ln}", "pain location chips (stored value stays English)")

    # Aura pieces
    c = read("AuraDetailSheet.kt")
    s, ln = span(c, "val EYES = listOf(", ")\n")
    add([v for _, v in re.findall(r'"([^"]+)" to "([^"]+)"', s)], "hard",
        f"{SRC}/AuraDetailSheet.kt:{ln}", "aura eye label")
    s, ln = span(c, "private val CELL_LABELS", ")\n")
    add([v for _, v in re.findall(r'"([^"]+)" to "([^"]+)"', s)], "hard",
        f"{SRC}/AuraDetailSheet.kt:{ln}", "aura cell label")

    # Paywall feature tables
    c = read("PaywallScreen.kt")
    for m in re.finditer(r'FeatureItem\(R\.drawable\.\w+,\s*"([^"]+)",\s*"([^"]+)"\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/PaywallScreen.kt:{ln2}", "paywall feature title")
        add([m.group(2)], "soft", f"{SRC}/PaywallScreen.kt:{ln2}", "paywall feature subtitle")
    c = read("OnboardingPaywallScreen.kt")
    for m in re.finditer(r'Triple\(R\.drawable\.\w+,\s*"([^"]+)",\s*"([^"]+)"\)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/OnboardingPaywallScreen.kt:{ln2}", "paywall feature title")
        add([m.group(2)], "soft", f"{SRC}/OnboardingPaywallScreen.kt:{ln2}", "paywall feature subtitle")

    # Explore sections (2nd positional arg is the display title)
    c = read("InsightsExploreScreen.kt")
    for m in re.finditer(r'RecommendationsSection\(\s*"[^"]*",\s*"([^"]+)"', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        add([m.group(1)], "hard", f"{SRC}/InsightsExploreScreen.kt:{ln2}", "explore section title")

    add(["Other"], "hard", f"{SRC}/ThirdPartyConnectionsScreen.kt:1490", "garmin family fallback")
    add(["steady"], "hard", f"{SRC}/InsightsWhatChangedScreen.kt:142", "habit row caption, t(spec.caption)")

    # PoolConfig infoText: a concat of literals; the runtime key is the JOINED
    # string, translated at render in ManagePoolScreen.
    c = read("MainActivity.kt")
    for m in re.finditer(r'infoText\s*=\s*((?:"(?:[^"\\]|\\.)+"\s*\+?\s*)+)', c):
        ln2 = c.count("\n", 0, m.start()) + 1
        joined = "".join(decode(x) for x in re.findall(r'"((?:[^"\\]|\\.)+)"', m.group(1)))
        add([joined], "soft", f"{SRC}/MainActivity.kt:{ln2}", "pool infoText (joined concat)")
    return found


def main():
    root = os.getcwd()
    if not os.path.isdir(os.path.join(root, SRC)):
        sys.exit(f"run from the repo root; {SRC} not found")

    with open("tools/i18n/strings.json", encoding="utf-8") as fh:
        spec = {e["en"]: e for e in json.load(fh)}
    translated = set()
    with open("tools/i18n/translations/de.json", encoding="utf-8") as fh:
        translated |= set(json.load(fh).keys())

    entries = {}
    # 1. spec keys with no translation
    for en, e in spec.items():
        if en not in translated:
            entries[en] = {
                "en": en, "class": e["class"], "max_len": e["max_len"],
                "sites": [s for s in e["sites"] if not s.startswith("...")][:3],
                "source": "extracted",
            }
    # 2. harvested data-table keys
    harvested = 0
    for en, cls, site, note in harvest():
        if en in translated:
            continue
        if en in entries:
            if site not in entries[en]["sites"]:
                entries[en]["sites"].append(site)
            continue
        if en in spec:
            continue  # in spec and translated? no — spec+untranslated handled above
        n = len(en)
        entries[en] = {
            "en": en, "class": cls,
            "max_len": max(int(n * (1.10 if cls == "hard" else 1.45)), n + (3 if cls == "hard" else 10), 14),
            "sites": [site], "source": "harvested", "note": note,
        }
        harvested += 1

    ordered = sorted(entries.values(), key=lambda e: e["en"].lower())
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(ordered, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    hard = sum(1 for e in ordered if e["class"] == "hard")
    print(f"missing translations: {len(ordered)} keys ({hard} hard, {len(ordered) - hard} soft)")
    print(f"  from extraction: {sum(1 for e in ordered if e['source'] == 'extracted')}")
    print(f"  harvested from data tables: {sum(1 for e in ordered if e['source'] == 'harvested')}")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
