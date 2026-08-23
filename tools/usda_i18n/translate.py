#!/usr/bin/env python3
"""
Translate the distinct USDA food descriptions in usda_foods_en.json into the six
app languages (de/es/nl/fr/it/pt) with Claude Sonnet via the `claude` CLI.

Resumable: results land in translations.jsonl, one line per English description:
  {"en": "...", "de": "...", "es": "...", "nl": "...", "fr": "...", "it": "...", "pt": "..."}
Re-running skips anything already translated. Batches that come back malformed are
retried; anything still failing after MAX_TRIES is listed in failed.txt.

Usage:  python3 translate.py [--workers 6] [--batch 50] [--limit N] [--shard i/n]
Then:   python3 load.py <project-ref>   (writes usda_foods_i18n in Supabase)
"""
import argparse, json, os, subprocess, sys, threading, time
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "usda_foods_en.json")
OUT = os.path.join(HERE, "translations.jsonl")
FAILED = os.path.join(HERE, "failed.txt")
LANGS = ["de", "es", "nl", "fr", "it", "pt"]
MAX_TRIES = 3

PROMPT = """You translate food names for a European migraine-tracking app. The names come from the USDA FoodData Central database and use its comma-separated style: head noun first, then qualifiers (e.g. "Cheese, cheddar, sharp, sliced"; "Beef, ground, 85% lean meat / 15% fat, raw").

Translate each English name into German (de), Spanish (es), Dutch (nl), French (fr), Italian (it) and Portuguese (pt). Rules:
- Keep the comma-separated structure and order: head noun first, then qualifiers.
- Use the everyday word a native speaker would type when searching for that food (Belgian/Dutch Dutch, European Spanish, European Portuguese, standard German/French/Italian).
- Keep brand and restaurant names exactly as written (McDONALD'S, KELLOGG'S, ENFAMIL, Wagyu...). Keep numbers, percentages, units, fractions and abbreviations like NS, NFS unchanged.
- Keep the same register and level of detail; do not add or drop qualifiers; do not explain.
- Cooking states (raw, cooked, roasted, braised, canned, frozen, dried) and cuts of meat must be translated with the normal culinary term of that language.
- Capitalise only the first letter of the name (plus proper nouns), like the English.

Input is a JSON array of English strings. Output ONLY a JSON array of the same length and order, each element an object with keys de, es, nl, fr, it, pt. No markdown fences, no commentary.

INPUT:
"""

_lock = threading.Lock()


def load_done():
    done = {}
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                    done[o["en"]] = o
                except Exception:
                    pass
    return done


def call_claude(batch):
    prompt = PROMPT + json.dumps(batch, ensure_ascii=False)
    p = subprocess.run(
        ["claude", "-p", "--model", "sonnet", "--output-format", "json"],
        input=prompt, capture_output=True, text=True, timeout=600,
    )
    if p.returncode != 0:
        raise RuntimeError(f"claude exit {p.returncode}: {p.stderr[:300]}")
    env = json.loads(p.stdout)
    if env.get("is_error"):
        raise RuntimeError(f"claude error: {str(env.get('result'))[:300]}")
    text = env.get("result", "")
    s, e = text.find("["), text.rfind("]")
    if s < 0 or e < 0:
        raise RuntimeError("no JSON array in result: " + text[:200])
    arr = json.loads(text[s:e + 1])
    if not isinstance(arr, list) or len(arr) != len(batch):
        raise RuntimeError(f"length mismatch {len(arr) if isinstance(arr, list) else '?'} != {len(batch)}")
    rows = []
    for en, tr in zip(batch, arr):
        if not isinstance(tr, dict):
            raise RuntimeError("element not object")
        row = {"en": en}
        for lang in LANGS:
            v = tr.get(lang)
            if not isinstance(v, str) or not v.strip():
                raise RuntimeError(f"missing {lang} for {en!r}")
            row[lang] = v.strip()
        rows.append(row)
    return rows


def work(idx, batch):
    last = None
    for attempt in range(1, MAX_TRIES + 1):
        try:
            rows = call_claude(batch)
            with _lock, open(OUT, "a", encoding="utf-8") as f:
                for r in rows:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            return idx, len(rows), None
        except Exception as ex:  # noqa
            last = ex
            time.sleep(3 * attempt)
    return idx, 0, f"{last}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--batch", type=int, default=50)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--shard", default="0/1", help="i/n: only process batches where index %% n == i")
    a = ap.parse_args()
    shard_i, shard_n = (int(x) for x in a.shard.split("/"))

    src = json.load(open(SRC, encoding="utf-8"))
    names = sorted({r["description"] for r in src})
    done = load_done()
    todo = [n for n in names if n not in done]
    if a.limit:
        todo = todo[: a.limit]
    print(f"distinct={len(names)} done={len(done)} todo={len(todo)}", flush=True)
    batches = [todo[i:i + a.batch] for i in range(0, len(todo), a.batch)]
    batches = [b for k, b in enumerate(batches) if k % shard_n == shard_i]
    todo = [n for b in batches for n in b]
    t0 = time.time()
    n_ok = 0
    failures = []
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        futs = {ex.submit(work, i, b): b for i, b in enumerate(batches)}
        for k, fut in enumerate(as_completed(futs), 1):
            idx, n, err = fut.result()
            if err:
                failures.extend(futs[fut])
                print(f"[{k}/{len(batches)}] batch {idx} FAILED: {err}", flush=True)
            else:
                n_ok += n
                el = time.time() - t0
                rate = n_ok / el if el else 0
                eta = (len(todo) - n_ok) / rate / 60 if rate else 0
                print(f"[{k}/{len(batches)}] +{n} ({n_ok}/{len(todo)}) {rate:.1f}/s eta {eta:.0f}m", flush=True)
    if failures:
        with open(FAILED, "w", encoding="utf-8") as f:
            f.write("\n".join(failures) + "\n")
        print(f"{len(failures)} names failed; see failed.txt (re-run to retry)")
    print("done")


if __name__ == "__main__":
    main()
