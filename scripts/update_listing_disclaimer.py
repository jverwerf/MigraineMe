#!/usr/bin/env python3
"""Append the medical disclaimer to the Play store listing (all languages).

Run AFTER the app is reinstated (the API returns 403 "The app is suspended"
until then):  ~/.playpub/bin/python scripts/update_listing_disclaimer.py
"""
import os
import httplib2
import google_auth_httplib2
from google.oauth2 import service_account
from googleapiclient.discovery import build

KEY = os.environ.get("PLAY_KEY", os.path.expanduser("~/keys/play-publisher.json"))
PACKAGE = os.environ.get("PACKAGE", "com.migraineme")

DISCLAIMER = (
    "MEDICAL DISCLAIMER: MigraineMe is an informational, self-tracking tool. "
    "It is not a medical device and does not diagnose, treat, cure, or prevent "
    "migraine or any other medical condition, and it does not provide medical "
    "advice. Always consult a qualified healthcare professional for medical "
    "advice, diagnosis, or treatment, and before making any decisions about "
    "your health."
)

creds = service_account.Credentials.from_service_account_file(
    KEY, scopes=["https://www.googleapis.com/auth/androidpublisher"])
authed = google_auth_httplib2.AuthorizedHttp(creds, http=httplib2.Http(timeout=300))
svc = build("androidpublisher", "v3", http=authed, cache_discovery=False)

edit_id = svc.edits().insert(packageName=PACKAGE, body={}).execute()["id"]
listings = svc.edits().listings().list(
    packageName=PACKAGE, editId=edit_id).execute().get("listings", [])

changed = False
for l in listings:
    desc = l.get("fullDescription", "")
    if "not a medical device" in desc:
        print("%s: disclaimer already present, skipping" % l["language"])
        continue
    new_desc = (desc.rstrip() + "\n\n" + DISCLAIMER)
    if len(new_desc) > 4000:
        # Play caps fullDescription at 4000 chars; trim the body, keep the disclaimer.
        new_desc = desc.rstrip()[: 4000 - len(DISCLAIMER) - 2] + "\n\n" + DISCLAIMER
    l["fullDescription"] = new_desc
    svc.edits().listings().update(
        packageName=PACKAGE, editId=edit_id, language=l["language"], body=l).execute()
    print("%s: disclaimer appended (%d chars)" % (l["language"], len(new_desc)))
    changed = True

if changed:
    svc.edits().commit(packageName=PACKAGE, editId=edit_id).execute()
    print("Committed. Listing update is live/pending review.")
else:
    svc.edits().delete(packageName=PACKAGE, editId=edit_id).execute()
    print("Nothing to change; edit discarded.")
