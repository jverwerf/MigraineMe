#!/usr/bin/env python3
"""
Translate the app tables into a language that is being added (ja ko tr pl zh).

Run from the repo root of either MM app:
  python3 tools/i18n/translate_new_lang.py ja ko tr pl zh [--limit N] [--threads 6]
  python3 tools/i18n/translate_new_lang.py ja ko tr pl zh --fix-overflows

What it covers: every key of strings.json plus every key already in de.json. The
second set matters because hand-added entries (extractor-blind strings) live only
in the tables.

Resumable: a key already in translations/<lang>.json is skipped, and the file is
saved after each batch. Items that fail validation are retried in later passes
and otherwise stay missing, so they render English and check_lengths reports them.

Existing de + fr go in as context, because the English alone is often ambiguous
("Back" = go back or back of the head). The key is never altered.

Polish plurals: the plain key holds the "many" form. Count strings also get
"<key>|few" and, where the key can be called with 1, "<key>|one". Strings.resolve
picks the form from the first numeric argument at runtime.

Hanzi, kana and hangul render about 1.8x as wide as a Latin letter, so the budget
handed to the model and enforced by check_lengths is in width units, not chars.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor

STRINGS = "tools/i18n/strings.json"
TRANS = "tools/i18n/translations"
MODEL = os.environ.get("TRANSLATE_MODEL", "sonnet")
CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
BATCH = 30

NAMES = {
    "ja": "Japanese",
    "ko": "Korean",
    "tr": "Turkish",
    "pl": "Polish",
    "zh": "Traditional Chinese as written in Taiwan (never Simplified characters, never mainland vocabulary)",
}

VOICE = {
    "ja": "Plain polite です・ます. Buttons and chips: short noun or verb forms. No katakana when a normal Japanese word exists.",
    "ko": "Polite 해요체. Buttons and chips: short noun forms. Prefer native everyday words over transliterations.",
    "tr": "Address the user as 'sen', warm and simple. Keep Turkish dotted and dotless i exact (i/İ, ı/I).",
    "pl": "Address the user as 'ty', warm and simple. Avoid gendered past-tense verbs about the user where possible, since the app does not know the user's gender.",
    "zh": "Address the user as 你. Taiwan usage: 資料 not 数据, 軟體 not 软件, 設定 not 设置, 影片 not 视频, 登入 not 登录.",
}

GLOSSARY = {
    "ja": "migraine=片頭痛; attack=発作; trigger=誘因; aura=前兆; prodrome=予兆; postdrome=後発症状; relief=緩和; symptom=症状; medicine=薬; insights=インサイト",
    "ko": "migraine=편두통; attack=발작; trigger=유발 요인; aura=조짐; prodrome=전구 증상; postdrome=후기 증상; relief=완화; symptom=증상; medicine=약; insights=인사이트",
    "tr": "migraine=migren; attack=atak; trigger=tetikleyici; aura=aura; prodrome=prodrom; postdrome=postdrom; relief=rahatlama; symptom=belirti; medicine=ilaç; insights=içgörüler",
    "pl": "migraine=migrena; attack=atak; trigger=wyzwalacz; aura=aura; prodrome=prodrom; postdrome=postdrom; relief=ulga; symptom=objaw; medicine=lek; insights=wnioski",
    "zh": "migraine=偏頭痛; attack=發作; trigger=誘因; aura=預兆; prodrome=前驅症狀; postdrome=後期症狀; relief=緩解; symptom=症狀; medicine=藥物; insights=洞察",
}

PH = re.compile(r"%(?:\d+\$)?[-+0,#.]*\d*(?:\.\d+)?(?:l|ll)?[sdfi@]|%%")
SUFFIX_PH = re.compile(r"[A-Za-z](%\d+\$s|%s)(?![A-Za-z])")
WIDE = re.compile(r"[ᄀ-ᇿ⺀-鿿가-힣豈-﫿＀-￯]")
SIMPLIFIED_ONLY = set("这个们为说时会对发动过还么关开进种学样经现长间问题头数据设软视录应")

lock = threading.Lock()


def width(s):
    """Width units: a wide character counts 1.8, anything else 1."""
    wide = len(WIDE.findall(s))
    return wide * 1.8 + (len(s) - wide)


def english_of(key):
    return key.split("##")[0]


def tokens(s):
    """Placeholders by index and type, so a reorder passes and a lost one does not."""
    out, nxt = [], 1
    for m in PH.finditer(s):
        tok = m.group(0)
        if tok == "%%":
            out.append("%%")
            continue
        pos = re.match(r"%(\d+)\$", tok)
        if pos:
            idx = int(pos.group(1))
        else:
            idx = nxt
            nxt += 1
        out.append(f"{idx}{tok[-1].replace('@', 's')}")
    return sorted(out)


def validate(lang, key, item):
    """Return a reason this translation is unusable, or None."""
    en = english_of(key)
    droppable = set()
    for m in SUFFIX_PH.finditer(en):
        pos = re.match(r"%(\d+)\$", m.group(1))
        droppable.add(f"{pos.group(1) if pos else 1}s")
    want = tokens(en)
    for form in [item.get("out"), item.get("few"), item.get("one")]:
        if form is None:
            continue
        if not isinstance(form, str) or not form.strip():
            return "empty"
        got = tokens(form)
        if got != want:
            if form != item.get("one") and sorted(t for t in want if t not in droppable) != sorted(t for t in got if t not in droppable):
                return f"placeholders {want} vs {got}"
        if lang == "zh" and SIMPLIFIED_ONLY & set(form):
            return "simplified characters"
    if item.get("out") is None:
        return "missing"
    return None


def prompt(lang, batch):
    items = []
    for i, b in enumerate(batch):
        e = {"id": i, "en": english_of(b["key"])}
        if b.get("de"):
            e["de"] = b["de"]
        if b.get("fr"):
            e["fr"] = b["fr"]
        e["max_width"] = b["max_len"]
        if b["hard"]:
            e["hard"] = True
        if english_of(b["key"]) != b["key"]:
            e["sense"] = b["key"].split("##")[1]
        items.append(e)
    plural = ""
    if lang == "pl":
        plural = (
            "\nPLURALS: if a placeholder is a count of something (for example '%s attacks', "
            "'%1$s of %2$s days'), 'out' must be the form for 5 ('%s ataków'), and add 'few' with "
            "the form for 2-4 ('%s ataki'). Add 'one' with the form for 1 only when the English "
            "could really be called with 1. Omit them for strings that hold no count. "
            "A placeholder glued to a word ('trigger%2$s') is an English 's' suffix: drop it."
        )
    wide = ""
    if lang in ("ja", "ko", "zh"):
        wide = "\nWIDTH: each character of your language counts as 1.8 toward max_width, so a max_width of 18 means 10 characters."
    return f"""Translate UI strings of MigraineMe, a migraine tracking health app, into {NAMES[lang]}.

Voice: {VOICE[lang]}
Fixed medical terms, use these exactly: {GLOSSARY[lang]}
Never translate: MigraineMe, Brainy, Garmin, Oura, WHOOP, Polar, Fitbit, Apple Health, Health Connect, Premium.
The de and fr fields show how a human translated the same string: use them to settle what an ambiguous English word means here. Do not translate from them.
'sense' says which meaning of the word is intended.

Rules:
- Keep every placeholder exactly as written (%s %1$s %2$s %d %.1f %@ %%). You may reorder them; positional forms must keep their numbers.
- Keep \\n line breaks, and any HTML or {{name}} tokens, unchanged.
- max_width is a hard limit on length in character widths when hard=true: choose a shorter natural word rather than abbreviating. Otherwise it is a guide.
- This is health information: do not add claims or soften or strengthen what the English says.
- Natural wording a native speaker would use in a phone app, not word-for-word.{plural}{wide}

Return ONLY a JSON array, with every id: [{{"id":0,"out":"..."}}, ...]

{json.dumps(items, ensure_ascii=False)}"""


def call(text):
    with tempfile.TemporaryDirectory() as tmp:
        r = subprocess.run(
            [CLAUDE, "-p", "--model", MODEL, "--output-format", "text"],
            input=text, capture_output=True, text=True, timeout=600, cwd=tmp,
        )
    if r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout)[:300])
    body = r.stdout.strip()
    # raw_decode stops at the end of the array, so a note after it does no harm.
    return json.JSONDecoder().raw_decode(body[body.find("["):])[0]


def save(path, table):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(table, fh, ensure_ascii=False, indent=1)


def run_batch(lang, batch, table, path, bad):
    try:
        res = {o["id"]: o for o in call(prompt(lang, batch))}
    except Exception as e:
        print(f"  {lang} batch failed: {e}", flush=True)
        return 0
    n = 0
    with lock:
        for i, b in enumerate(batch):
            item = res.get(i)
            if not item:
                continue
            why = validate(lang, b["key"], item)
            if why:
                bad.append((b["key"], why))
                continue
            table[b["key"]] = item["out"]
            if lang == "pl":
                if item.get("few"):
                    table[b["key"] + "|few"] = item["few"]
                if item.get("one"):
                    table[b["key"] + "|one"] = item["one"]
            n += 1
        save(path, table)
    return n


def fix_overflows(lang, spec, threads):
    """Re-word entries over their width budget. Shorter words, not abbreviations."""
    path = f"{TRANS}/{lang}.json"
    table = json.load(open(path, encoding="utf-8"))
    for attempt in range(4):
        over = []
        for k, v in table.items():
            e = spec.get(k.split("|")[0])
            if e and round(width(v)) > e["max_len"]:
                over.append({"key": k, "was": v, "max": e["max_len"]})
        print(f"{lang}: overflow pass {attempt + 1}, {len(over)} over", flush=True)
        if not over:
            return

        def work(batch):
            wide = " Each character of your language counts as 1.8 toward max_width." if lang in ("ja", "ko", "zh") else ""
            text = (
                f"These {NAMES[lang]} UI strings for a migraine app are too long for their space. "
                f"Rewrite each so its length is at most max_width.{wide} Keep the meaning and every "
                f"placeholder (%s %1$s %d %%). Pick shorter natural words or drop filler; use "
                f"abbreviations only if there is truly no other way. Fixed terms: {GLOSSARY[lang]}. "
                'Return ONLY a JSON array: [{"id":0,"out":"..."}].\n'
                + json.dumps([{"id": i, "en": english_of(b["key"].split("|")[0]), "current": b["was"],
                               "max_width": b["max"]} for i, b in enumerate(batch)], ensure_ascii=False)
            )
            try:
                res = {o["id"]: o for o in call(text)}
            except Exception as e:
                print(f"  batch failed: {e}", flush=True)
                return
            with lock:
                for i, b in enumerate(batch):
                    item = res.get(i)
                    if not item or not isinstance(item.get("out"), str):
                        continue
                    base = b["key"].split("|")[0]
                    if validate(lang, base, {"out": item["out"]}) is None:
                        table[b["key"]] = item["out"]
                save(path, table)

        with ThreadPoolExecutor(threads) as pool:
            list(pool.map(work, [over[i:i + BATCH] for i in range(0, len(over), BATCH)]))


def main():
    args = sys.argv[1:]
    limit = int(args[args.index("--limit") + 1]) if "--limit" in args else None
    threads = int(args[args.index("--threads") + 1]) if "--threads" in args else 6
    skip = {limit and str(limit), str(threads)}
    langs = [a for a in args if a in NAMES and a not in skip]

    with open(STRINGS, encoding="utf-8") as fh:
        spec = {e["en"]: e for e in json.load(fh)}
    de = json.load(open(f"{TRANS}/de.json", encoding="utf-8"))
    fr = json.load(open(f"{TRANS}/fr.json", encoding="utf-8"))
    keys = list(dict.fromkeys(list(spec) + list(de)))

    if "--fix-overflows" in args:
        for lang in langs:
            fix_overflows(lang, spec, threads)
        return

    for lang in langs:
        path = f"{TRANS}/{lang}.json"
        table = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
        for attempt in range(3):
            todo = [k for k in keys if k not in table and english_of(k).strip()]
            if limit:
                todo = todo[:limit]
            print(f"{lang}: pass {attempt + 1}, {len(todo)} to do", flush=True)
            if not todo:
                break
            items = []
            for k in todo:
                e = spec.get(k, {})
                items.append({
                    "key": k,
                    "de": de.get(k),
                    "fr": fr.get(k),
                    "max_len": e.get("max_len", max(60, len(k) * 2)),
                    "hard": e.get("class") == "hard",
                })
            batches = [items[i:i + BATCH] for i in range(0, len(items), BATCH)]
            bad, done = [], 0
            with ThreadPoolExecutor(threads) as pool:
                futures = [pool.submit(run_batch, lang, b, table, path, bad) for b in batches]
                for i, f in enumerate(futures, 1):
                    done += f.result()
                    if i % 10 == 0:
                        print(f"  {lang} {i}/{len(batches)} batches, {done} ok, {len(bad)} rejected", flush=True)
            print(f"{lang}: pass {attempt + 1} added {done}, rejected {len(bad)}", flush=True)
            for k, why in bad[:10]:
                print(f"  REJECT {why}: {k[:70]!r}", flush=True)
            if limit:
                break
    print("done", flush=True)


if __name__ == "__main__":
    main()
