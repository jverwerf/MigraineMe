#!/usr/bin/env python3
"""Upload a signed AAB to Google Play and roll it out.

Driven by env vars (all optional, sensible defaults):
  PLAY_KEY  service-account JSON      (default ~/keys/play-publisher.json)
  AAB       path to the .aab          (default app/build/outputs/bundle/release/app-release.aab)
  PACKAGE   app package name          (default com.migraineme)
  TRACK     Play track                (default production)
  NOTES     release notes text        (default "Minor improvements and fixes.")

Run with the dedicated venv:  ~/.playpub/bin/python scripts/play_publish.py
"""
import os
import httplib2
import google_auth_httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

KEY = os.environ.get("PLAY_KEY", os.path.expanduser("~/keys/play-publisher.json"))
PACKAGE = os.environ.get("PACKAGE", "com.migraineme")
AAB = os.environ.get("AAB", "app/build/outputs/bundle/release/app-release.aab")
TRACK = os.environ.get("TRACK", "production")
NOTES = os.environ.get("NOTES", "Minor improvements and fixes.")

creds = service_account.Credentials.from_service_account_file(
    KEY, scopes=["https://www.googleapis.com/auth/androidpublisher"])
authed = google_auth_httplib2.AuthorizedHttp(creds, http=httplib2.Http(timeout=600))
svc = build("androidpublisher", "v3", http=authed, cache_discovery=False)

print("Opening edit for %s ..." % PACKAGE)
edit_id = svc.edits().insert(packageName=PACKAGE, body={}).execute()["id"]

langs = []
try:
    ls = svc.edits().listings().list(packageName=PACKAGE, editId=edit_id).execute()
    langs = [l["language"] for l in ls.get("listings", [])]
except Exception as e:
    print("  (listings:", e, ")")
lang = "en-US" if "en-US" in langs else ("en-GB" if "en-GB" in langs else (langs[0] if langs else "en-US"))

print("Uploading %s (single-shot, up to 10 min)..." % AAB)
media = MediaFileUpload(AAB, mimetype="application/octet-stream", resumable=False)
resp = svc.edits().bundles().upload(
    packageName=PACKAGE, editId=edit_id, media_body=media).execute(num_retries=6)
vc = resp["versionCode"]
print("  uploaded versionCode:", vc)

print("Assigning versionCode %s to '%s' (full rollout)..." % (vc, TRACK))
svc.edits().tracks().update(
    packageName=PACKAGE, editId=edit_id, track=TRACK,
    body={"releases": [{"versionCodes": [str(vc)], "status": "completed",
                        "releaseNotes": [{"language": lang, "text": NOTES}]}]}).execute()

print("Committing...")
res = svc.edits().commit(packageName=PACKAGE, editId=edit_id).execute()
print("DONE - edit %s -> versionCode %s live on %s" % (res.get("id"), vc, TRACK))
