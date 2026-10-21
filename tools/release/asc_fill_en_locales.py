import sys, os, time, json, hashlib, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ascapi import request
VER="6e4c6247-40e2-432b-8c96-ca74b90f0dc5"; APP="6760654324"
# 1. wait until editable
for i in range(30):
    st=request("GET", f"/v1/appStoreVersions/{VER}")["data"]["attributes"]["appStoreState"]
    print("state", st); 
    if st in ("PREPARE_FOR_SUBMISSION","DEVELOPER_REJECTED","REJECTED","METADATA_REJECTED"): break
    time.sleep(10)
else: sys.exit("still not editable")
locs=request("GET", f"/v1/appStoreVersions/{VER}/appStoreVersionLocalizations?limit=20")["data"]
by={d["attributes"]["locale"]:d for d in locs}
gb=by["en-GB"]; a=gb["attributes"]
notes=json.load(open(os.path.expanduser("~/dev/MigraineMe/tools/release/release_notes_5028.json")))["en-US"]
# 2. download en-GB screenshots once
sets=request("GET", f"/v1/appStoreVersionLocalizations/{gb['id']}/appScreenshotSets?limit=10")["data"]
cache={}
os.makedirs("gbshots", exist_ok=True)
for s in sets:
    dt=s["attributes"]["screenshotDisplayType"]; files=[]
    shots=request("GET", f"/v1/appScreenshotSets/{s['id']}/appScreenshots?limit=20")["data"]
    for x in shots:
        img=x["attributes"]["imageAsset"]; url=img["templateUrl"].replace("{w}",str(img["width"])).replace("{h}",str(img["height"])).replace("{f}","png")
        p=f"gbshots/{dt}_{x['attributes']['fileName']}"
        if not os.path.exists(p): open(p,"wb").write(urllib.request.urlopen(url,timeout=120).read())
        files.append((x["attributes"]["fileName"], p))
    cache[dt]=files; print("cached", dt, len(files))
def upload(set_id, name, path):
    data=open(path,"rb").read()
    r=request("POST","/v1/appScreenshots",{"data":{"type":"appScreenshots","attributes":{"fileName":name,"fileSize":len(data)},"relationships":{"appScreenshotSet":{"data":{"type":"appScreenshotSets","id":set_id}}}}})
    if "error" in r: print("  reserve err", r); return
    sid=r["data"]["id"]
    for op in r["data"]["attributes"]["uploadOperations"]:
        q=urllib.request.Request(op["url"], data=data[op["offset"]:op["offset"]+op["length"]], method=op["method"])
        for h in op["requestHeaders"]: q.add_header(h["name"],h["value"])
        urllib.request.urlopen(q,timeout=300).read()
    request("PATCH", f"/v1/appScreenshots/{sid}", {"data":{"type":"appScreenshots","id":sid,"attributes":{"uploaded":True,"sourceFileChecksum":hashlib.md5(data).hexdigest()}}})
# 3. create + fill the three locales
for loc in ("en-US","en-CA","en-AU"):
    attrs={"locale":loc,"description":a["description"],"keywords":a["keywords"],"whatsNew":notes,"supportUrl":a["supportUrl"],"marketingUrl":a["marketingUrl"]}
    if a.get("promotionalText"): attrs["promotionalText"]=a["promotionalText"]
    if loc in by:
        lid=by[loc]["id"]; attrs.pop("locale")
        r=request("PATCH", f"/v1/appStoreVersionLocalizations/{lid}", {"data":{"type":"appStoreVersionLocalizations","id":lid,"attributes":attrs}})
    else:
        r=request("POST","/v1/appStoreVersionLocalizations",{"data":{"type":"appStoreVersionLocalizations","attributes":attrs,"relationships":{"appStoreVersion":{"data":{"type":"appStoreVersions","id":VER}}}}})
    if "error" in r: print(loc, "ERR", r); continue
    lid=r["data"]["id"]; print(loc, "loc", lid[:8], "desc", len(r["data"]["attributes"]["description"] or ""))
    have={s["attributes"]["screenshotDisplayType"] for s in request("GET", f"/v1/appStoreVersionLocalizations/{lid}/appScreenshotSets?limit=10")["data"]}
    for dt,files in cache.items():
        if dt in have: print("  ", dt, "exists, skip"); continue
        s=request("POST","/v1/appScreenshotSets",{"data":{"type":"appScreenshotSets","attributes":{"screenshotDisplayType":dt},"relationships":{"appStoreVersionLocalization":{"data":{"type":"appStoreVersionLocalizations","id":lid}}}}})
        for name,p in files: upload(s["data"]["id"], name, p)
        print("  ", dt, len(files), "uploaded")
# 4. verify
for d in request("GET", f"/v1/appStoreVersions/{VER}/appStoreVersionLocalizations?limit=20")["data"]:
    ss=request("GET", f"/v1/appStoreVersionLocalizations/{d['id']}/appScreenshotSets?limit=10")["data"]
    cnt=[(s["attributes"]["screenshotDisplayType"][-6:], len(request("GET", f"/v1/appScreenshotSets/{s['id']}/appScreenshots?limit=20")["data"])) for s in ss]
    print("VERIFY", d["attributes"]["locale"], "desc", len(d["attributes"]["description"] or ""), "wn", len(d["attributes"]["whatsNew"] or ""), cnt)
