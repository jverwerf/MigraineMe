#!/usr/bin/env python3
"""
Upsert translations.jsonl into public.usda_foods_i18n of a Supabase project via the
management API (token in ~/keys/supabase-pat.txt).

Usage: python3 load.py <project-ref> [--chunk 1500] [--only-missing]
  MigraineMe:          qykflarpibofvffmzghi
  MeSeries/VertigoMe:  vpwnhpwiwxwoyjfytiye
"""
import argparse, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LANGS = ["de", "es", "nl", "fr", "it", "pt"]


def sql(ref, token, query):
    req = urllib.request.Request(
        f"https://api.supabase.com/v1/projects/{ref}/database/query",
        data=json.dumps({"query": query}).encode("utf-8"),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "curl/8.0"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode("utf-8"))


def lit(s):
    return "'" + s.replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("--chunk", type=int, default=1500)
    a = ap.parse_args()
    token = open(os.path.expanduser("~/keys/supabase-pat.txt")).read().strip()

    foods = json.load(open(os.path.join(HERE, "usda_foods_en.json"), encoding="utf-8"))
    tr = {}
    with open(os.path.join(HERE, "translations.jsonl"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                o = json.loads(line)
                tr[o["en"]] = o

    rows = []
    missing = 0
    for r in foods:
        t = tr.get(r["description"])
        if not t:
            missing += 1
            continue
        for lang in LANGS:
            rows.append((r["fdc_id"], lang, t[lang][:400]))
    print(f"foods={len(foods)} translated={len(foods)-missing} missing={missing} rows={len(rows)}", flush=True)

    for i in range(0, len(rows), a.chunk):
        chunk = rows[i:i + a.chunk]
        values = ",".join(f"({fid},{lit(lang)},{lit(desc)})" for fid, lang, desc in chunk)
        q = (
            "insert into public.usda_foods_i18n (fdc_id, lang, description) values "
            + values
            + " on conflict (fdc_id, lang) do update set description = excluded.description"
            " where public.usda_foods_i18n.description is distinct from excluded.description"
        )
        res = sql(a.ref, token, q)
        if isinstance(res, dict) and res.get("message"):
            print("ERROR", res["message"][:500])
            sys.exit(1)
        print(f"  {min(i + a.chunk, len(rows))}/{len(rows)}", flush=True)

    n = sql(a.ref, token, "select lang, count(*) n from public.usda_foods_i18n group by 1 order by 1")
    print(n)


if __name__ == "__main__":
    main()
