"""App Store: attach a build to a version, set What's New in every locale that exists, submit for review.
usage: asc_release.py --app <ascAppId> --version <versionString> --build <buildNumber> --notes <notes.json> [--dry]"""
import json, sys, time
sys.path.insert(0, "/Users/jordyverwerft/dev/MigraineMe/tools/release")
import asc
from asc import request
dry = "--dry" in sys.argv
def arg(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default
APP = arg("--app", asc.APP); VER = arg("--version"); BUILD = arg("--build"); NOTES = arg("--notes")
if not (VER and BUILD and NOTES): sys.exit("--version, --build and --notes are required")
notes = json.load(open(NOTES))
ALIAS = {"en-GB": "en-US", "en-AU": "en-US", "en-CA": "en-US", "es-MX": "es-ES", "es-419": "es-ES", "pt-BR": "pt-PT", "it": "it-IT"}
def note_for(loc): return notes.get(loc) or notes.get(ALIAS.get(loc, ""))
# 1. build processed?
b = None
for _ in range(40):
    r = request("GET", f"/v1/builds?filter[app]={APP}&filter[version]={BUILD}&fields[builds]=version,processingState,usesNonExemptEncryption")
    if r.get("data"):
        b = r["data"][0]; print(f"build {BUILD}:", b["attributes"]["processingState"], flush=True)
        if b["attributes"]["processingState"] == "VALID": break
    else: print(f"build {BUILD} not visible yet", flush=True)
    if dry: break
    time.sleep(60)
if dry: sys.exit(0)
assert b and b["attributes"]["processingState"] == "VALID", "build not processed"
bid = b["id"]
if b["attributes"].get("usesNonExemptEncryption") is None:
    print("encryption:", request("PATCH", f"/v1/builds/{bid}", {"data": {"type": "builds", "id": bid, "attributes": {"usesNonExemptEncryption": False}}}).get("data", {}).get("attributes", {}).get("usesNonExemptEncryption"))
# 2. version
v = request("GET", f"/v1/apps/{APP}/appStoreVersions?filter[versionString]={VER}&fields[appStoreVersions]=versionString,appStoreState")
if v.get("data"):
    vid = v["data"][0]["id"]; print("version exists", vid, v["data"][0]["attributes"]["appStoreState"])
else:
    c = request("POST", "/v1/appStoreVersions", {"data": {"type": "appStoreVersions", "attributes": {"platform": "IOS", "versionString": VER, "releaseType": "AFTER_APPROVAL"},
                                                   "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}})
    if "error" in c: sys.exit("version create failed: " + c["body"][:400])
    vid = c["data"]["id"]; print("version created", vid)
print("attach build:", request("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build", {"data": {"type": "builds", "id": bid}}) or "ok")
# 3. What's New for every locale the version already has
loc = request("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations?fields[appStoreVersionLocalizations]=locale,whatsNew&limit=50")
for x in loc.get("data", []):
    l = x["attributes"]["locale"]; text = note_for(l)
    if not text: print("notes", l, "SKIPPED (no translation)"); continue
    r = request("PATCH", f"/v1/appStoreVersionLocalizations/{x['id']}", {"data": {"type": "appStoreVersionLocalizations", "id": x["id"], "attributes": {"whatsNew": text}}})
    print("notes", l, "error" if "error" in r else "ok", r.get("body", "")[:200])
# 4. review submission
rs = request("POST", "/v1/reviewSubmissions", {"data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"}, "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}})
if "error" in rs:
    ex = request("GET", f"/v1/apps/{APP}/reviewSubmissions?filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES&limit=5")
    print("submission create failed:", rs["body"][:300], "existing:", [(x["id"], x["attributes"]["state"]) for x in ex.get("data", [])])
    rsid = ex["data"][0]["id"] if ex.get("data") else None
else:
    rsid = rs["data"]["id"]; print("submission", rsid)
if rsid:
    it = request("POST", "/v1/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems", "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": rsid}},
                                                                                                                  "appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}})
    print("item:", "error " + it["body"][:300] if "error" in it else it["data"]["id"])
    sub = request("PATCH", f"/v1/reviewSubmissions/{rsid}", {"data": {"type": "reviewSubmissions", "id": rsid, "attributes": {"submitted": True}}})
    print("submitted:", "error " + sub["body"][:400] if "error" in sub else sub["data"]["attributes"]["state"])
