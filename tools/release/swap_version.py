import sys, time, asc
APP, OLD, NEW = sys.argv[1:4]
rs = asc.request("GET", f"/v1/apps/{APP}/reviewSubmissions?filter[state]=WAITING_FOR_REVIEW,IN_REVIEW,READY_FOR_REVIEW&limit=5")
for x in rs.get("data", []):
    r = asc.request("PATCH", f"/v1/reviewSubmissions/{x['id']}", {"data": {"type": "reviewSubmissions", "id": x["id"], "attributes": {"canceled": True}}})
    print("cancel", x["id"], x["attributes"]["state"], "->", r.get("data", {}).get("attributes", {}).get("state") or r)
time.sleep(5)
v = asc.request("GET", f"/v1/apps/{APP}/appStoreVersions?filter[versionString]={OLD}&fields[appStoreVersions]=versionString,appStoreState")
if not v.get("data"): sys.exit(f"{OLD} not found")
vid = v["data"][0]["id"]; print("old version", OLD, v["data"][0]["attributes"]["appStoreState"])
r = asc.request("PATCH", f"/v1/appStoreVersions/{vid}", {"data": {"type": "appStoreVersions", "id": vid, "attributes": {"versionString": NEW}}})
print("rename ->", r.get("data", {}).get("attributes", {}).get("versionString") or r)
