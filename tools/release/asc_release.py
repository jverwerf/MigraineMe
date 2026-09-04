"""App Store: attach build 68 to a 5.0.18 version, set What's New in every locale, submit for review.
usage: asc_release.py [--dry]"""
import json, sys, time
sys.path.insert(0, "/Users/jordyverwerft/dev/MigraineMe/tools/release")
from asc import request, APP
dry = "--dry" in sys.argv
notes = json.load(open("/Users/jordyverwerft/dev/MigraineMe/tools/release/release_notes_5018.json"))
ALIAS = {"en-GB": "en-US", "es-MX": "es-ES", "pt-BR": "pt-PT", "it": "it-IT"}
def note_for(loc): return notes.get(loc) or notes[ALIAS[loc]]
# 1. build 68 processed?
b = None
for _ in range(40):
    r = request("GET", f"/v1/builds?filter[app]={APP}&filter[version]=68&fields[builds]=version,processingState,usesNonExemptEncryption")
    if r.get("data"):
        b = r["data"][0]; print("build 68:", b["attributes"]["processingState"])
        if b["attributes"]["processingState"] == "VALID": break
    else: print("build 68 not visible yet")
    if dry: break
    time.sleep(60)
if dry: sys.exit(0)
assert b and b["attributes"]["processingState"] == "VALID", "build not processed"
bid = b["id"]
if b["attributes"].get("usesNonExemptEncryption") is None:
    print("encryption:", request("PATCH", f"/v1/builds/{bid}", {"data": {"type": "builds", "id": bid, "attributes": {"usesNonExemptEncryption": False}}}).get("data", {}).get("attributes", {}).get("usesNonExemptEncryption"))
# 2. version 5.0.18
v = request("GET", f"/v1/apps/{APP}/appStoreVersions?filter[versionString]=5.0.18&fields[appStoreVersions]=versionString,appStoreState")
if v.get("data"):
    vid = v["data"][0]["id"]; print("version exists", vid, v["data"][0]["attributes"]["appStoreState"])
else:
    c = request("POST", "/v1/appStoreVersions", {"data": {"type": "appStoreVersions", "attributes": {"platform": "IOS", "versionString": "5.0.18", "releaseType": "AFTER_APPROVAL"},
                                                   "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}})
    vid = c["data"]["id"]; print("version created", vid)
print("attach build:", request("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build", {"data": {"type": "builds", "id": bid}}) or "ok")
# 3. What's New per locale
loc = request("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations?fields[appStoreVersionLocalizations]=locale,whatsNew&limit=20")
have = {x["attributes"]["locale"]: x["id"] for x in loc.get("data", [])}
for l in ["en-GB", "fr-FR", "de-DE", "es-ES", "es-MX", "nl-NL", "pt-BR", "it", "pt-PT"]:
    if l in have:
        r = request("PATCH", f"/v1/appStoreVersionLocalizations/{have[l]}", {"data": {"type": "appStoreVersionLocalizations", "id": have[l], "attributes": {"whatsNew": note_for(l)}}})
    else:
        r = request("POST", "/v1/appStoreVersionLocalizations", {"data": {"type": "appStoreVersionLocalizations", "attributes": {"locale": l, "whatsNew": note_for(l)},
                                                                    "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}})
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
