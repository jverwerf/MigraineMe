"""Upload an AAB to a Play production release with localized release notes.
usage: play_release.py <aab> <notes.json> --name <versionName> [--pkg <package>] [--vc <versionCode>] [--dry]
Pass --vc to set notes on an already-uploaded versionCode instead of uploading <aab>."""
import json, sys, socket, urllib.request
sys.path.insert(0, "/Users/jordyverwerft/dev/MigraineMe/tools/release")
import play
from play import req, token
socket.setdefaulttimeout(900)
aab, notes_path = sys.argv[1], sys.argv[2]; dry = "--dry" in sys.argv
def arg(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default
name = arg("--name")
if not name: sys.exit("--name <versionName> is required")
play.PKG = arg("--pkg", play.PKG)
notes = json.load(open(notes_path))
ALIAS = {"pt-BR": "pt-PT", "es-419": "es-ES", "es-MX": "es-ES", "en-GB": "en-US", "it": "it-IT"}
for a, b in ALIAS.items():
    if b in notes: notes.setdefault(a, notes[b])
e = req("POST", "/edits", {}); eid = e["id"]; print("edit", eid, "pkg", play.PKG)
prod = req("GET", f"/edits/{eid}/tracks/production")
# Locales come from the store listings: a release with no prior notes must not silence the rest.
langs = [x["language"] for x in req("GET", f"/edits/{eid}/listings").get("listings", [])]
print("current production:", prod["releases"][0]["name"], prod["releases"][0]["versionCodes"], "listing langs:", langs)
if dry:
    req("DELETE", f"/edits/{eid}"); sys.exit(0)
vc_arg = arg("--vc")
if vc_arg:
    up = {"versionCode": int(vc_arg)}
    print("reusing uploaded versionCode", vc_arg)
    data = None
else:
    data = open(aab, "rb").read()
if data is not None:
  r = urllib.request.Request(f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{play.PKG}/edits/{eid}/bundles?uploadType=media",
                             data=data, method="POST")
  r.add_header("Authorization", "Bearer " + token()); r.add_header("Content-Type", "application/octet-stream")
  with urllib.request.urlopen(r, timeout=900) as x: up = json.loads(x.read())
  print("uploaded versionCode", up.get("versionCode"), up)
vc = up["versionCode"]
body = {"releases": [{"name": name, "versionCodes": [str(vc)], "status": "completed",
                      "releaseNotes": [{"language": l, "text": t} for l, t in notes.items() if l in langs or l == "en-US"]}]}
print("track update:", json.dumps(req("PUT", f"/edits/{eid}/tracks/production", body))[:300])
print("commit:", req("POST", f"/edits/{eid}:commit", {}))
