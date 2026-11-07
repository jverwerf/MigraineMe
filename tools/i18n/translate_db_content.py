#!/usr/bin/env python3
"""
Translate the in-app content that lives in Postgres rather than the string tables.

  python3 tools/i18n/translate_db_content.py shop
  python3 tools/i18n/translate_db_content.py practitioners
  python3 tools/i18n/translate_db_content.py help [--langs de,ja] [--slugs a,b] [--force]
  python3 tools/i18n/translate_db_content.py help-normalize
  python3 tools/i18n/translate_db_content.py status

MigraineMe project only. Run from anywhere; resumable; safe to re-run.

  shop           shop_item_translations, shop_group_translations. Fills what is
                 missing; a row with a stale source_hash is reported, not redone.
  practitioners  practitioner_bios, _offers, _sections. Fills every language a
                 practitioner has no row for, from the language she wrote in.
                 Never rewrites an existing row: those may be hand-checked.
  help           help_category_translations, help_article_translations. Fills
                 what is missing AND redoes whatever English has moved under, so
                 after editing an English article this is the only step needed.

A reply that fails validation is retried and otherwise left out, so the app shows
English for that one row and `status` shows the gap.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.expanduser("~/dev/website-i18n"))
import sbsql  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IOS_REPO = os.path.expanduser("~/dev/migraineme-ios")
MODEL = os.environ.get("TRANSLATE_MODEL", "sonnet")
CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
THREADS = int(os.environ.get("TRANSLATE_THREADS", "3"))
SEP = "\x1f"
LANGS = ["de", "es", "fr", "it", "nl", "pt", "ja", "ko", "tr", "pl", "zh"]
WIDE_LANGS = {"ja", "ko", "zh"}
BATCH_CHARS = 12000

NAMES = {
    "en": "English",
    "de": "German", "es": "Spanish", "nl": "Dutch", "fr": "French", "it": "Italian",
    "pt": "European Portuguese (Portugal, never Brazilian)",
    "ja": "Japanese", "ko": "Korean", "tr": "Turkish", "pl": "Polish",
    "zh": "Traditional Chinese as written in Taiwan (never Simplified characters, never mainland vocabulary)",
}

VOICE = {
    "en": "Address the reader as 'you'. Plain British English.",
    "de": "Address the reader as 'du'.",
    "es": "Address the reader as 'tú' (Spain and Latin America both read it fine).",
    "nl": "Address the reader as 'je'.",
    "fr": "Address the reader as 'tu'.",
    "it": "Address the reader as 'tu'.",
    "pt": "Address the reader as 'tu'. European Portuguese spelling and verbs: 'estás a fazer', 'ecrã', 'telemóvel', 'definições'.",
    "ja": "Plain polite です・ます. Avoid katakana when a normal Japanese word exists.",
    "ko": "Polite 해요체. Prefer everyday native words over transliterations.",
    "tr": "Address the reader as 'sen'. Keep Turkish dotted and dotless i exact (i/İ, ı/I).",
    "pl": "Address the reader as 'ty'. The reader's gender is unknown, so avoid gendered past-tense verbs about the reader where you can.",
    "zh": "Address the reader as 你. Taiwan usage: 資料 not 数据, 軟體 not 软件, 設定 not 设置, 影片 not 视频, 登入 not 登录, 手機 not 手机, 應用程式 not 应用, 網路 not 网络, 訊息 not 信息.",
}

GLOSSARY = {
    "ja": "migraine=片頭痛; attack=発作; trigger=誘因; aura=前兆; prodrome=予兆; postdrome=後発症状; relief=緩和; symptom=症状; medicine=薬; insights=インサイト",
    "ko": "migraine=편두통; attack=발작; trigger=유발 요인; aura=조짐; prodrome=전구 증상; postdrome=후기 증상; relief=완화; symptom=증상; medicine=약; insights=인사이트",
    "tr": "migraine=migren; attack=atak; trigger=tetikleyici; aura=aura; prodrome=prodrom; postdrome=postdrom; relief=rahatlama; symptom=belirti; medicine=ilaç; insights=içgörüler",
    "pl": "migraine=migrena; attack=atak; trigger=wyzwalacz; aura=aura; prodrome=prodrom; postdrome=postdrom; relief=ulga; symptom=objaw; medicine=lek; insights=wnioski",
    "zh": "migraine=偏頭痛; attack=發作; trigger=誘因; aura=預兆; prodrome=前驅症狀; postdrome=後期症狀; relief=緩解; symptom=症狀; medicine=藥物; insights=洞察",
}

# Names that must survive a translation untouched. Latin script even in ja/ko/zh:
# they have to match what the reader sees on their phone or in the store.
KEEP_NAMES = [
    "MigraineMe", "VertigoMe", "Brainy", "Android", "iPhone", "iPad", "iOS", "Apple Watch",
    "Apple Health", "HealthKit", "Health Connect", "Google Fit", "Samsung Health", "Google Play",
    "App Store", "Garmin", "Garmin Connect", "Fitbit", "Oura", "WHOOP", "Whoop", "Polar", "Withings",
    "Amazfit", "Zepp", "Huawei", "Xiaomi", "Wear OS", "Galaxy Watch", "Pixel Watch", "Flo", "Clue",
    "Premom", "Natural Cycles", "MyFitnessPal", "Cronometer", "Sleep Cycle", "Autosleep", "AutoSleep",
    "Sleep++", "Napbot", "HRV", "SpO2", "PDF", "CSV", "USDA", "Open Food Facts", "RevenueCat",
    "help@migraineme.app", "migraineme.app",
]

# Characters that only exist in Simplified: their presence means a wrong script.
SIMPLIFIED_ONLY = set(
    "这个们为说时会对发动过还么关开进种学样经现长间问题头数据设软视录应"
    "网络图书买卖电脑话语请让给见觉带页码钟后复节点"
)
CJK = re.compile(r"[぀-ヿ㐀-鿿가-힯]")
LATIN_WORD = re.compile(r"[A-Za-z]{2,}")
EN_TELLS = re.compile(r"\b(the|and|your|you|with|that|this|from|when|have|will|tap)\b", re.I)
URLISH = re.compile(r"https?://[^\s)\]]+|[\w.+-]+@[\w-]+\.[\w.]+")

_tmp = tempfile.mkdtemp(prefix="translate_db_")
_log_lock = threading.Lock()


def log(*a):
    with _log_lock:
        print(time.strftime("%H:%M:%S"), *a, flush=True)


def sql(s):
    return sbsql.run("migraineme", s)


def lit(v):
    """Dollar-quoted literal: the management API takes no bind parameters."""
    if v is None:
        return "null"
    tag = "$i18n$"
    n = 0
    while tag in v:
        n += 1
        tag = "$i18n%d$" % n
    return tag + v + tag


def lit_array(a):
    return "array[%s]::text[]" % ",".join(lit(x) for x in a) if a else "'{}'::text[]"


def call_claude(prompt):
    # Bare system prompt, no tools, no settings, run outside any project: the
    # translator sees the job and nothing else, and each call stays cheap.
    p = subprocess.run(
        [CLAUDE, "-p", "--model", MODEL, "--output-format", "text", "--tools", "",
         "--no-session-persistence", "--strict-mcp-config", "--setting-sources", "",
         "--system-prompt", "You are a careful professional medical-software translator. "
                            "You output exactly the format asked for and nothing else."],
        input=prompt, capture_output=True, text=True, timeout=1800, cwd=_tmp)
    if p.returncode != 0:
        raise RuntimeError("claude rc=%s %s %s" % (p.returncode, p.stdout[-300:], p.stderr[-300:]))
    return p.stdout.strip()


def call_json(prompt):
    txt = call_claude(prompt)
    m = re.search(r"```(?:json)?\s*(.*?)```", txt, re.S)
    if m:
        txt = m.group(1).strip()
    a, b = min((i for i in (txt.find("{"), txt.find("[")) if i >= 0), default=-1), max(txt.rfind("}"), txt.rfind("]"))
    if a < 0:
        raise ValueError("no JSON in reply")
    return json.loads(txt[a:b + 1])


def house_rules(lang):
    g = GLOSSARY.get(lang)
    return (
        "Target language: %s. %s\n"
        "%s"
        "Plain, warm language for patients, not clinicians. Natural %s, never word for word.\n"
        "NEVER use em dashes or en dashes as punctuation (write a comma, colon, full stop or brackets).\n"
        "Keep the medical meaning exact. Estimates stay estimates: never turn 'may', 'can', 'estimate' or "
        "'might' into a promise or a certainty, and never soften or strengthen a claim. Add nothing, drop nothing.\n"
        "Keep names of people, companies, products, medicines and doses, every number, unit, price, URL, "
        "email address and discount code exactly as written.\n%s"
    ) % (NAMES[lang], VOICE[lang], ("Terms: %s\n" % g) if g else "", NAMES[lang].split(" (")[0],
         {"ja": "Japanese punctuation: 、 and 。, full-width ：！？ between Japanese words. Never ，.\n",
          "zh": "Full-width punctuation between Chinese characters: ，。：！？、\n"}.get(lang, ""))


def fix_punct(lang, text):
    """Half-width punctuation between CJK characters reads as a typo in ja and zh."""
    if lang not in ("ja", "zh"):
        return text
    for half, full in ((",", "，" if lang == "zh" else "、"), (":", "："), ("!", "！"), ("?", "？")):
        text = re.sub(r"(?<=[぀-ヿ㐀-鿿])%s(?=\s*[぀-ヿ㐀-鿿])" % re.escape(half), full, text)
    return text.replace("，", "、") if lang == "ja" else text


def fix_quotes(lang, text):
    """Polish opens with „ and closes with ”, and the model tends to close with "."""
    if lang == "pl":
        text = re.sub(r'„([^"”\n]*)"', r"„\1”", text)
    return text


def _punct(ch):
    return ch in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" or unicodedata.category(ch).startswith("P")


PAIRS = {"「": "」", "『": "』", "（": "）", "(": ")", '"': '"', "“": "”", "„": "“", "‘": "’", "«": "»", "《": "》", "【": "】", "〈": "〉"}


def fix_bold(text):
    """iOS parses bold by CommonMark: a ** that touches punctuation on its inside
    and a letter on its outside is not bold, and the asterisks show on screen.
    Common in ja, zh and ko, which have no space after a full stop:
    **一處。**你 and 「**x**」 are the shapes that work. Android does not care."""
    def fix_span(m):
        inner, before, after = m.group(2), m.group(1), m.group(3)
        pre, post = "", ""

        def bad():
            return (inner and _punct(inner[-1]) and after and not after.isspace() and not _punct(after)) or \
                   (inner and _punct(inner[0]) and before and not before.isspace() and not _punct(before))

        while bad() and len(inner) > 1:
            if PAIRS.get(inner[0]) == inner[-1] and len(inner) > 2:
                pre, post, inner = pre + inner[0], inner[-1] + post, inner[1:-1]
            elif _punct(inner[-1]):
                post, inner = inner[-1] + post, inner[:-1]
            else:
                pre, inner = pre + inner[0], inner[1:]
        return "%s%s**%s**%s%s" % (before, pre, inner, post, after)

    out = []
    for line in text.split("\n"):
        # Twice, because the character after one span can be the start of the next.
        for _ in range(2):
            line = re.sub(r"(^|[^*])\*\*([^*\n]+?)\*\*($|[^*])", fix_span, line)
        out.append(line)
    return "\n".join(out)


def normalize(lang, text):
    return fix_bold(fix_quotes(lang, fix_punct(lang, text)))


def script_problem(lang, text):
    """A reason this text is not in the target language or script, or None."""
    if "—" in text or re.search(r"\s–\s", text):
        return "em dash"
    if lang == "zh":
        bad = SIMPLIFIED_ONLY & set(text)
        if bad:
            return "simplified characters: " + "".join(sorted(bad))
    if lang in WIDE_LANGS:
        cjk = len(CJK.findall(text))
        latin = sum(len(w) for w in LATIN_WORD.findall(text))
        if len(text) > 80 and cjk < latin * 0.8:
            return "too little %s (%d vs %d Latin letters)" % (lang, cjk, latin)
    elif len(text) > 200:
        words = max(1, len(text.split()))
        tells = len(EN_TELLS.findall(text))
        # Dutch and German share a few of these words, so the bar is high.
        if tells / words > 0.12:
            return "looks untranslated (%d English tells in %d words)" % (tells, words)
    return None


def number_problem(src, new):
    # Each run of digits must survive as a value: 31.08.2026 may become 2026年8月31日.
    # "1:1" is a word for one-to-one, and many languages say it in words.
    def values(t):
        return {int(n) for n in re.findall(r"\d+", t.replace("1:1", " "))}
    gone = values(src) - values(new)
    return "numbers changed: %s" % sorted(gone) if gone else None


def names_problem(src, new, lang=""):
    # Whole words: "Polarised" is not Polar, "Flow" is not Flo.
    def has(n, text):
        return re.search(r"(?<![A-Za-z])%s(?![A-Za-z])" % re.escape(n), text) is not None
    # Polish declines names (iPhone, iPhonie), so there the stem has to survive.
    gone = [n for n in KEEP_NAMES if has(n, src) and (n[:-1] if lang == "pl" else n) not in new]
    if gone:
        return "names lost: %s" % gone
    links = set(URLISH.findall(src)) - set(URLISH.findall(new))
    if links:
        return "links lost: %s" % sorted(links)
    return None


def run_pool(jobs, fn):
    with ThreadPoolExecutor(THREADS) as ex:
        for f in [ex.submit(fn, j) for j in jobs]:
            f.result()


# ───────────────────────────────── shop ─────────────────────────────────


def item_hash(i):
    # Worked out from the existing rows; the first link note counts as the note.
    import hashlib
    parts = [i["what"] or "", i["evidence"] or "", json.dumps(i["pros"], ensure_ascii=False),
             json.dumps(i["cons"], ensure_ascii=False), i["safety_note"] or "", i["note"] or ""]
    return hashlib.md5(SEP.join(parts).encode()).hexdigest()


def shop():
    groups = sql("select key, title, blurb, md5(title || e'\\x1f' || blurb) h from public.shop_groups order by key")
    items = sql(
        "select i.key, i.name, i.what, i.evidence, i.pros, i.cons, i.safety_note,"
        " (select l.note from public.shop_item_links l where l.item_key = i.key and l.note is not null order by l.sort limit 1) note"
        " from public.shop_items i order by i.key")
    have_g = {(r["group_key"], r["lang"]): r for r in sql("select group_key, lang, title, blurb from public.shop_group_translations")}
    have_i = {(r["item_key"], r["lang"]): r for r in sql("select item_key, lang, what, evidence, pros, cons, safety_note, note, source_hash from public.shop_item_translations")}

    stale = [(k, l) for (k, l), r in have_i.items() if r["source_hash"] != item_hash(next(i for i in items if i["key"] == k))]
    if stale:
        log("shop: stale existing rows (not touched):", stale)

    jobs = []
    for lang in LANGS:
        gs = [g for g in groups if (g["key"], lang) not in have_g and (g["key"], "de") in have_g]
        if gs:
            jobs.append((lang, "groups", gs))
        todo = [i for i in items if (i["key"], lang) not in have_i]
        for n in range(0, len(todo), 6):
            jobs.append((lang, "items", todo[n:n + 6]))

    def work(job):
        lang, kind, rows = job
        for attempt in range(3):
            left = translate_shop(lang, kind, rows, have_g, have_i)
            if not left:
                return
            rows = left
        log("shop FAILED", lang, kind, [r["key"] for r in rows])

    run_pool(jobs, work)


def translate_shop(lang, kind, rows, have_g, have_i):
    payload = []
    for r in rows:
        if kind == "groups":
            payload.append({"key": r["key"], "title": r["title"], "blurb": r["blurb"],
                            "de": {k: have_g[(r["key"], "de")][k] for k in ("title", "blurb")},
                            "fr": {k: have_g[(r["key"], "fr")][k] for k in ("title", "blurb")}})
        else:
            payload.append({"key": r["key"], "product_name_keep_exact": r["name"], "what": r["what"],
                            "evidence": r["evidence"], "pros": r["pros"], "cons": r["cons"],
                            "safety_note": r["safety_note"], "note": r["note"]})
    fields = "key, title, blurb" if kind == "groups" else "key, what, evidence, pros, cons, safety_note, note"
    prompt = (
        "Translate these cards from the shop page of MigraineMe, a migraine tracking app.\n"
        + house_rules(lang) +
        "Where 'de' or 'fr' is given it is the existing translation in that language: use it only to settle meaning.\n"
        "Product names stay exactly as written. Discount notes keep the code verbatim (MESERIES, ME-SERIES), translate only the words around it.\n"
        "Fields that are null stay null. pros and cons keep the same number of entries in the same order.\n"
        "Return ONLY a JSON array of objects with these keys: %s.\n\n%s\n"
    ) % (fields, json.dumps(payload, ensure_ascii=False, indent=1))
    try:
        out = call_json(prompt)
    except Exception as e:  # noqa: BLE001
        log("shop retry", lang, kind, str(e)[:200])
        return rows
    by_key = {o.get("key"): o for o in out if isinstance(o, dict)}
    left = []
    stmts = []
    for r in rows:
        o = by_key.get(r["key"])
        why = None
        if not o:
            why = "missing"
        elif kind == "groups":
            all_src, all_new = r["title"] + " " + r["blurb"], (o.get("title") or "") + " " + (o.get("blurb") or "")
            if not o.get("title") or not o.get("blurb"):
                why = "empty"
            else:
                why = script_problem(lang, all_new) or number_problem(all_src, all_new)
        else:
            why = shop_item_problem(lang, r, o)
        if why:
            log("shop reject", lang, r["key"], why)
            left.append(r)
            continue
        if kind == "groups":
            stmts.append(
                "insert into public.shop_group_translations (group_key, lang, title, blurb, source_hash) values (%s,%s,%s,%s,%s)"
                " on conflict (group_key, lang) do update set title=excluded.title, blurb=excluded.blurb, source_hash=excluded.source_hash, updated_at=now();"
                % (lit(r["key"]), lit(lang), lit(o["title"]), lit(o["blurb"]), lit(r["h"])))
        else:
            stmts.append(
                "insert into public.shop_item_translations (item_key, lang, what, evidence, pros, cons, safety_note, note, source_hash) values (%s,%s,%s,%s,%s,%s,%s,%s,%s)"
                " on conflict (item_key, lang) do update set what=excluded.what, evidence=excluded.evidence, pros=excluded.pros, cons=excluded.cons, safety_note=excluded.safety_note, note=excluded.note, source_hash=excluded.source_hash, updated_at=now();"
                % (lit(r["key"]), lit(lang), lit(o["what"]), lit(o.get("evidence")), lit_array(o.get("pros") or []),
                   lit_array(o.get("cons") or []), lit(o.get("safety_note")), lit(o.get("note")), lit(item_hash(r))))
    if stmts:
        sql("\n".join(stmts))
        log("shop", lang, kind, "+%d" % len(stmts))
    return left


def shop_item_problem(lang, r, o):
    if not o.get("what"):
        return "empty what"
    for f in ("evidence", "safety_note", "note"):
        if bool(r[f]) != bool(o.get(f)):
            return "%s presence changed" % f
    for f in ("pros", "cons"):
        if len(r[f] or []) != len(o.get(f) or []):
            return "%s count changed" % f
    src = " ".join([r["what"], r["evidence"] or "", r["safety_note"] or "", r["note"] or ""] + (r["pros"] or []) + (r["cons"] or []))
    new = " ".join([o["what"], o.get("evidence") or "", o.get("safety_note") or "", o.get("note") or ""] + (o.get("pros") or []) + (o.get("cons") or []))
    if r["note"]:
        for code in re.findall(r"\b[A-Z][A-Z-]{4,}\b", r["note"]):
            if code not in (o.get("note") or ""):
                return "discount code lost"
    return script_problem(lang, new) or number_problem(src, new) or names_problem(src, new, lang)


# ────────────────────────────── practitioners ───────────────────────────


def practitioners():
    # Names and qualifications only: never notes, emails or anything about clients.
    people = {r["id"]: r for r in sql(
        "select id::text id, display_name, practice_name, qualification from public.practitioners")}
    bios = sql(
        "select practitioner_id::text pid, lang, headline, quote, bio, treats, meta, facts, is_source"
        " from public.practitioner_bios")
    offers = sql(
        "select practitioner_id::text pid, lang, sort, title, price, subtitle, bullets, kind, image_url"
        " from public.practitioner_offers order by sort, id")
    sections = sql(
        "select practitioner_id::text pid, lang, sort, title, body, items"
        " from public.practitioner_sections order by sort, id")

    jobs = []
    for pid in sorted({b["pid"] for b in bios}):
        mine = [b for b in bios if b["pid"] == pid]
        src = next((b for b in mine if b["is_source"]), None) or next(b for b in mine if b["lang"] == "en")
        slang = src["lang"]
        src_offers = [o for o in offers if o["pid"] == pid and o["lang"] == slang]
        src_sections = [s for s in sections if s["pid"] == pid and s["lang"] == slang]
        en_ref = next((b for b in mine if b["lang"] == "en"), None) if slang != "en" else None
        for lang in ["en"] + LANGS:
            if lang == slang:
                continue
            need_bio = not any(b["lang"] == lang for b in mine)
            need_off = src_offers and not any(o["pid"] == pid and o["lang"] == lang for o in offers)
            need_sec = src_sections and not any(s["pid"] == pid and s["lang"] == lang for s in sections)
            if need_bio or need_off or need_sec:
                jobs.append(dict(pid=pid, person=people[pid], lang=lang, slang=slang, src=src, en_ref=en_ref,
                                 offers=src_offers if need_off else [],
                                 sections=src_sections if need_sec else [],
                                 need_bio=need_bio))
    log("practitioners: %d jobs" % len(jobs))

    def work(job):
        for attempt in range(3):
            try:
                if translate_practitioner(job):
                    return
            except Exception as e:  # noqa: BLE001
                log("practitioner retry", job["person"]["display_name"], job["lang"], str(e)[:300])
        log("practitioner FAILED", job["person"]["display_name"], job["lang"])

    run_pool(jobs, work)


def translate_practitioner(job):
    lang, person, src = job["lang"], job["person"], job["src"]
    payload = {}
    if job["need_bio"]:
        payload["card"] = {k: src[k] for k in ("headline", "quote", "bio", "treats", "meta", "facts")}
    if job["offers"]:
        payload["offers"] = [{k: o[k] for k in ("sort", "title", "price", "subtitle", "bullets")} for o in job["offers"]]
    if job["sections"]:
        payload["sections"] = [{k: s[k] for k in ("sort", "title", "body", "items")} for s in job["sections"]]
    ref = ""
    if job["en_ref"] and lang != "en" and job["need_bio"]:
        ref = ("\nThe practitioner's own wording is the %s above. For reference only, the English of the card, "
               "to settle meaning, not to copy from:\n%s\n" % (
                   NAMES[job["slang"]], json.dumps({k: job["en_ref"][k] for k in ("headline", "quote", "bio")}, ensure_ascii=False)))
    prompt = (
        "Translate this real practitioner's public profile in MigraineMe, a migraine tracking app, from %s.\n"
        % NAMES[job["slang"]] + house_rules(lang) +
        "These are the practitioner's own words about a real practice. Translate only what is written: no extra "
        "praise, no extra claims, nothing added or left out. First person stays first person. The word order and "
        "tone follow the source.\n"
        "Keep EXACTLY as written, in the original script and spelling: the person's name (%s), the practice name (%s), "
        "street addresses and postcodes, registration numbers, degree and qualification abbreviations (ND, DipNT, BSc, "
        "MSc, TEN, EMR, ASCA, HCPC, CSP, BAcC, AHPRA, HICAPS, McKenzie, Mulligan, Watson Headache), the names of "
        "professional bodies and insurers (leave them in their original language), programme and method names. "
        "City names may use the usual %s form.\n"
        "Short labels (treats, facts k and v, offer subtitle and price words) stay short, the size of a chip. "
        "In subtitle keep the ' · ' separator exactly: the app splits on it.\n"
        "Null stays null. Lists keep the same number of entries in the same order. Keep sort and tone values.\n"
        "Return ONLY JSON with the same shape and keys as this:\n%s\n%s"
    ) % (person["display_name"], person["practice_name"] or "none", NAMES[lang].split(" (")[0],
         json.dumps(payload, ensure_ascii=False, indent=1), ref)
    out = call_json(prompt)
    why = practitioner_problem(job, payload, out)
    if why:
        log("practitioner reject", person["display_name"], lang, why)
        return False

    pid = lit(job["pid"]) + "::uuid"
    stmts = []
    if job["need_bio"]:
        c = out["card"]
        stmts.append(
            "insert into public.practitioner_bios (practitioner_id, lang, headline, quote, bio, treats, meta, facts, is_source)"
            " values (%s,%s,%s,%s,%s,%s,%s,%s::jsonb,false) on conflict (practitioner_id, lang) do nothing;"
            % (pid, lit(lang), lit(c.get("headline")), lit(c.get("quote")), lit(c.get("bio")),
               lit_array(c.get("treats") or []), lit_array(c.get("meta") or []),
               lit(json.dumps(c.get("facts") or [], ensure_ascii=False))))
    for s, o in zip(job["offers"], out.get("offers") or []):
        stmts.append(
            "insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind, image_url)"
            " values (%s,%s,%d,%s,%s,%s,%s,%s,%s);"
            % (pid, lit(lang), s["sort"], lit(o["title"]), lit(o.get("price")), lit(o.get("subtitle")),
               lit_array(o.get("bullets") or []), lit(s["kind"]), lit(s["image_url"])))
    for s, o in zip(job["sections"], out.get("sections") or []):
        stmts.append(
            "insert into public.practitioner_sections (practitioner_id, lang, sort, title, body, items)"
            " values (%s,%s,%d,%s,%s,%s);"
            % (pid, lit(lang), s["sort"], lit(o["title"]), lit(o.get("body")), lit_array(o.get("items") or [])))
    # One transaction, so a job is whole or absent and a re-run cannot double a list.
    sql("begin;\n" + "\n".join(stmts) + "\ncommit;")
    log("practitioner", person["display_name"], lang, "ok")
    return True


def flat(v):
    if v is None:
        return ""
    if isinstance(v, str):
        return v
    if isinstance(v, dict):
        return " ".join(flat(x) for k, x in v.items() if k not in ("sort", "tone"))
    if isinstance(v, list):
        return " ".join(flat(x) for x in v)
    return str(v)


def same_shape(a, b, path="card"):
    if isinstance(a, dict):
        if not isinstance(b, dict) or set(a) != set(b):
            return "%s keys" % path
        for k in a:
            w = same_shape(a[k], b[k], path + "." + k)
            if w:
                return w
    elif isinstance(a, list):
        if not isinstance(b, list) or len(a) != len(b):
            return "%s length %d vs %s" % (path, len(a), len(b) if isinstance(b, list) else "?")
        for i, (x, y) in enumerate(zip(a, b)):
            w = same_shape(x, y, "%s[%d]" % (path, i))
            if w:
                return w
    elif a is None:
        if b not in (None, ""):
            return "%s invented" % path
    elif path.endswith(".tone"):
        if a != b:
            return "%s changed" % path
    elif isinstance(a, str):
        if not isinstance(b, str) or not b.strip():
            return "%s empty" % path
    elif a != b:
        return "%s changed" % path
    return None


def practitioner_problem(job, payload, out):
    if not isinstance(out, dict):
        return "not an object"
    why = same_shape(payload, out, "out")
    if why:
        return why
    src, new = flat(payload), flat(out)
    # Only the source's own names and qualifications are checked here.
    for name in (job["person"]["display_name"], job["person"]["practice_name"]):
        if name and name in src and name not in new:
            return "name changed: %s" % name
    if job["lang"] == "en":
        return number_problem(src, new)
    return script_problem(job["lang"], new) or number_problem(src, new)


# ───────────────────────────────── help ─────────────────────────────────

_labels = {}


def app_labels(lang):
    """The words the app itself shows, so an article names the button you can see."""
    if lang in _labels:
        return _labels[lang]
    table = {}
    for root in (IOS_REPO, REPO):  # Android last, so it wins where they differ.
        path = os.path.join(root, "tools/i18n/translations/%s.json" % lang)
        if os.path.exists(path):
            for k, v in json.load(open(path)).items():
                if "|" in k or not isinstance(v, str):
                    continue
                base = k.split("##")[0]
                if base == k or base not in table:
                    table[base] = v
    _labels[lang] = {k.lower(): (k, v) for k, v in table.items()}
    return _labels[lang]


def labels_for(lang, text):
    if lang not in LANGS:
        return []
    table = app_labels(lang)
    found = {}
    for phrase in re.findall(r"\*\*(.+?)\*\*", text) + re.findall(r'"([^"\n]{2,60})"', text):
        p = phrase.strip().rstrip(".:,")
        hit = table.get(p.lower())
        if hit:
            found[hit[0]] = hit[1]
    return sorted(found.items())


TERMS = ["Attack", "Migraine", "Trigger", "Triggers", "Prodrome", "Postdrome", "Aura", "Relief", "Reliefs", "Medicine",
         "Medicines", "Insights", "Recalibration", "Evening check-in", "Risk", "Monitor", "Journal", "Settings", "Premium"]


def term_hints(lang):
    table = app_labels(lang)
    return "; ".join("%s=%s" % (t, table[t.lower()][1]) for t in TERMS if t.lower() in table)


def help_prompt(lang, arts):
    hints = {}
    blob = []
    for a in arts:
        for k, v in labels_for(lang, a["body_markdown"]):
            hints[k] = v
        blob.append('<article slug="%s">\n<title>%s</title>\n<body>\n%s\n</body>\n</article>' % (a["slug"], a["title"], a["body_markdown"]))
    label_block = ""
    if hints:
        label_block = ("BUTTONS AND SCREENS: where the English names one of these, use exactly this wording, because it is "
                       "what the reader sees in the app:\n" + "\n".join("%s = %s" % kv for kv in hints.items()) + "\n")
    return (
        "Translate these Help Centre articles from MigraineMe, a migraine tracking and risk-estimate app.\n"
        "The same article is read on Android and on iPhone, so every mention of Android, iPhone, iOS, Apple Health, "
        "Health Connect or a store stays as precise as the English: if a step is for one platform, it stays for that platform.\n"
        + house_rules(lang) +
        "App terms already in use: %s\n" % term_hints(lang) + label_block +
        "\nMARKDOWN, kept line for line:\n"
        "- Same number of lines of each kind in the same order: '# ', '## ', '### ', '> ', '- ', '1. ', and paragraphs. Do not merge or split lines.\n"
        "- Same number of **bold** spans, around the same things. Same *italics*. List numbers unchanged.\n"
        "- If the body opens with '# ' and the title, that heading is word for word the same as your translated title.\n"
        "- Third-party app and device names, and what their own screens say, stay as those apps show them "
        "(for example a Garmin Connect menu name can stay English when the source quotes it).\n"
        "\nReturn ONLY the articles in the same XML-like wrapper, no code fence, nothing before or after:\n"
        '<article slug="...">\n<title>...</title>\n<body>\n...\n</body>\n</article>\n\n'
        + "\n\n".join(blob) + "\n")


ART_RE = re.compile(r'<article slug="([^"]+)">\s*<title>(.*?)</title>\s*<body>\n?(.*?)\n?</body>\s*</article>', re.S)


def md_shape(body):
    out = []
    for line in body.splitlines():
        s = line.strip()
        if not s:
            continue
        m = re.match(r"(#{1,3} |> |- |\d+\.\s)", s)
        out.append(m.group(1).strip() if m else "p")
    return out


def article_problem(lang, a, title, body):
    if not title.strip() or not body.strip():
        return "empty"
    src = a["body_markdown"]
    sh1, sh2 = md_shape(src), md_shape(body)
    if sh1 != sh2:
        diff = next((i for i, (x, y) in enumerate(zip(sh1, sh2)) if x != y), min(len(sh1), len(sh2)))
        return "markdown shape: %d vs %d lines, first diff at %d (%s vs %s)" % (
            len(sh1), len(sh2), diff, sh1[diff:diff + 1], sh2[diff:diff + 1])
    if src.count("**") != body.count("**"):
        return "bold markers %d vs %d" % (src.count("**"), body.count("**"))
    ratio = len(body) / max(1, len(src))
    lo, hi = (0.3, 1.3) if lang in WIDE_LANGS else (0.6, 1.9)
    if not lo <= ratio <= hi:
        return "length ratio %.2f" % ratio
    return script_problem(lang, title + "\n" + body) or number_problem(a["title"] + " " + src, title + " " + body) \
        or names_problem(a["title"] + " " + src, title + " " + body, lang)


def fix_title_heading(a, title, body):
    """The apps hide a first heading that repeats the title. Make it match exactly."""
    src_first = a["body_markdown"].split("\n", 1)[0].strip()
    if src_first.startswith("# ") and src_first[2:].strip().lower() == a["title"].strip().lower():
        lines = body.split("\n")
        if lines and lines[0].strip().startswith("# "):
            lines[0] = "# " + title.strip()
            return "\n".join(lines)
    return body


def upsert_article(a, lang, title, body):
    sql("insert into public.help_article_translations (slug, lang, title, body_markdown, source_hash) values (%s,%s,%s,%s,%s)"
        " on conflict (slug, lang) do update set title=excluded.title, body_markdown=excluded.body_markdown,"
        " source_hash=excluded.source_hash, updated_at=now();"
        % (lit(a["slug"]), lit(lang), lit(title), lit(body), lit(a["h"])))


def batches(arts):
    cur, size, out = [], 0, []
    for a in arts:
        n = len(a["body_markdown"])
        if cur and size + n > BATCH_CHARS:
            out.append(cur)
            cur, size = [], 0
        cur.append(a)
        size += n
    if cur:
        out.append(cur)
    return out


_fail = {}
_counts = {}


def help_job(job):
    lang, arts = job
    try:
        reply = call_claude(help_prompt(lang, arts))
    except Exception as e:  # noqa: BLE001
        msg = str(e)[:300]
        log("help call failed", lang, [a["slug"] for a in arts][:3], msg)
        if "limit" in msg.lower() or "credit" in msg.lower():
            time.sleep(300)
        return arts
    got = {m.group(1): (m.group(2).strip(), m.group(3).strip()) for m in ART_RE.finditer(reply)}
    left = []
    for a in arts:
        pair = got.get(a["slug"])
        if not pair:
            log("help reject", lang, a["slug"], "not returned")
            left.append(a)
            continue
        title = fix_quotes(lang, fix_punct(lang, pair[0]))
        body = fix_title_heading(a, title, normalize(lang, pair[1]))
        why = article_problem(lang, a, title, body)
        if why:
            log("help reject", lang, a["slug"], why)
            _fail[(a["slug"], lang)] = why
            left.append(a)
            continue
        upsert_article(a, lang, title, body)
        _fail.pop((a["slug"], lang), None)
        with _log_lock:
            _counts[lang] = _counts.get(lang, 0) + 1
    log("help", lang, "+%d of %d" % (len(arts) - len(left), len(arts)), "total this run %s" % _counts.get(lang, 0))
    return left


def help_categories(langs, force):
    cats = sql("select slug, name, md5(name) h from public.help_categories order by order_index")
    have = {(r["slug"], r["lang"]): r["source_hash"] for r in sql("select slug, lang, source_hash from public.help_category_translations")}

    def work(lang):
        todo = [c for c in cats if force or have.get((c["slug"], lang)) != c["h"]]
        for attempt in range(3):
            if not todo:
                return
            table = app_labels(lang)
            items = []
            for c in todo:
                # The apps show only what follows the dash in these, under one
                # shared heading, and take the whole string if there is no dash.
                # So only that part is stored and no dash ever reaches the table.
                name = c["name"].split("—")[-1].strip() if c["slug"].startswith("apps-and-devices-") else c["name"]
                e = {"slug": c["slug"], "en": name}
                hit = table.get(name.lower())
                if hit:
                    e["app_already_uses"] = hit[1]
                items.append(e)
            prompt = (
                "Translate these Help Centre section names of a migraine tracking app. Short, like a menu heading.\n"
                + house_rules(lang) +
                "Where app_already_uses is given, return exactly that.\n"
                'Return ONLY a JSON array of {"slug":..., "name":...}.\n\n' + json.dumps(items, ensure_ascii=False))
            try:
                out = {o["slug"]: o["name"] for o in call_json(prompt)}
            except Exception as e:  # noqa: BLE001
                log("category retry", lang, str(e)[:200])
                continue
            left, stmts = [], []
            for c in todo:
                n = (out.get(c["slug"]) or "").strip()
                if not n or "—" in n or (lang == "zh" and SIMPLIFIED_ONLY & set(n)):
                    log("category reject", lang, c["slug"], n)
                    left.append(c)
                    continue
                stmts.append(
                    "insert into public.help_category_translations (slug, lang, name, source_hash) values (%s,%s,%s,%s)"
                    " on conflict (slug, lang) do update set name=excluded.name, source_hash=excluded.source_hash, updated_at=now();"
                    % (lit(c["slug"]), lit(lang), lit(n), lit(c["h"])))
            if stmts:
                sql("\n".join(stmts))
            todo = left
            log("categories", lang, "+%d" % len(stmts))
        if todo:
            log("category FAILED", lang, [c["slug"] for c in todo])

    run_pool(langs, work)


def help_main(langs, slugs, force, passes=3):
    help_categories(langs, force)
    arts = sql("select slug, title, body_markdown, md5(title || e'\\x1f' || body_markdown) h from public.help_articles order by order_index, slug")
    if slugs:
        arts = [a for a in arts if a["slug"] in slugs]
    have = {(r["slug"], r["lang"]): r["source_hash"] for r in sql("select slug, lang, source_hash from public.help_article_translations")}
    todo = {lang: [a for a in arts if force or have.get((a["slug"], lang)) != a["h"]] for lang in langs}
    total = sum(len(v) for v in todo.values())
    log("help: %d translations to do" % total, {k: len(v) for k, v in todo.items()})
    for n in range(passes):
        jobs = []
        for lang, rows in todo.items():
            # Second pass on: one article per call, so one awkward article cannot
            # hold its neighbours back, and the failure reason is its own.
            jobs += [(lang, b) for b in (batches(rows) if n == 0 else [[r] for r in rows])]
        if not jobs:
            break
        results = {}

        def work(job):
            results[(job[0], job[1][0]["slug"])] = help_job(job)

        run_pool(jobs, work)
        todo = {lang: [] for lang in langs}
        for (lang, _), left in results.items():
            todo[lang] += left
    left = {k: [a["slug"] for a in v] for k, v in todo.items() if v}
    if left:
        log("help: NOT translated after %d passes:" % passes, json.dumps(left))
        for (slug, lang), why in _fail.items():
            log("  ", lang, slug, why)
    else:
        log("help: all done")


def help_normalize():
    """Re-apply the mechanical fixes to rows already stored. The hash stays: the
    English has not moved."""
    n = 0
    for r in sql("select slug, lang, title, body_markdown from public.help_article_translations"):
        t, b = fix_quotes(r["lang"], fix_punct(r["lang"], r["title"])), normalize(r["lang"], r["body_markdown"])
        if t != r["title"] or b != r["body_markdown"]:
            sql("update public.help_article_translations set title=%s, body_markdown=%s, updated_at=now() where slug=%s and lang=%s;"
                % (lit(t), lit(b), lit(r["slug"]), lit(r["lang"])))
            n += 1
    log("normalized %d rows" % n)


def status():
    print("shop items    ", sql("select lang, count(*) n from public.shop_item_translations group by 1 order by 1"))
    print("shop groups   ", sql("select lang, count(*) n from public.shop_group_translations group by 1 order by 1"))
    print("bios          ", sql("select lang, count(*) n from public.practitioner_bios group by 1 order by 1"))
    print("offers        ", sql("select lang, count(*) n from public.practitioner_offers group by 1 order by 1"))
    print("sections      ", sql("select lang, count(*) n from public.practitioner_sections group by 1 order by 1"))
    print("help cats     ", sql("select lang, count(*) n from public.help_category_translations group by 1 order by 1"))
    print("help articles ", sql("select lang, count(*) n from public.help_article_translations group by 1 order by 1"))
    print("help stale    ", sql(
        "select t.lang, count(*) n from public.help_article_translations t join public.help_articles a using (slug)"
        " where t.source_hash <> md5(a.title || e'\\x1f' || a.body_markdown) group by 1 order by 1"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["shop", "practitioners", "help", "help-normalize", "status"])
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--slugs", default="")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    if a.what == "shop":
        shop()
    elif a.what == "practitioners":
        practitioners()
    elif a.what == "help":
        help_main([x for x in a.langs.split(",") if x], [x for x in a.slugs.split(",") if x], a.force)
    elif a.what == "help-normalize":
        help_normalize()
    status()


if __name__ == "__main__":
    main()
