"""Upload the 5.0.18 AAB to Play production with localized release notes.
usage: play_release.py <aab> <notes.json> [--dry]"""
import json, sys, socket, urllib.request
sys.path.insert(0, "/Users/jordyverwerft/dev/MigraineMe/tools/release")
from play import req, token, PKG
socket.setdefaulttimeout(900)
aab, notes_path = sys.argv[1], sys.argv[2]; dry = "--dry" in sys.argv
notes = json.load(open(notes_path))
ALIAS = {"pt-BR": "pt-PT", "es-419": "es-ES", "es-MX": "es-ES", "en-GB": "en-US", "it": "it-IT"}
for a, b in ALIAS.items(): notes.setdefault(a, notes[b])
e = req("POST", "/edits", {}); eid = e["id"]; print("edit", eid)
prod = req("GET", f"/edits/{eid}/tracks/production")
langs = [n["language"] for n in prod["releases"][0].get("releaseNotes", [])]
print("current production:", prod["releases"][0]["name"], prod["releases"][0]["versionCodes"], "notes langs:", langs)
if dry:
    req("DELETE", f"/edits/{eid}"); sys.exit(0)
data = open(aab, "rb").read()
r = urllib.request.Request(f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}/edits/{eid}/bundles?uploadType=media",
                           data=data, method="POST")
r.add_header("Authorization", "Bearer " + token()); r.add_header("Content-Type", "application/octet-stream")
with urllib.request.urlopen(r, timeout=900) as x: up = json.loads(x.read())
print("uploaded versionCode", up.get("versionCode"), up)
vc = up["versionCode"]
body = {"releases": [{"name": "5.0.18", "versionCodes": [str(vc)], "status": "completed",
                      "releaseNotes": [{"language": l, "text": t} for l, t in notes.items() if l in langs or l == "en-US"]}]}
print("track update:", json.dumps(req("PUT", f"/edits/{eid}/tracks/production", body))[:300])
print("commit:", req("POST", f"/edits/{eid}:commit", {}))
