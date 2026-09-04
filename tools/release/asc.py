import json, time, sys, urllib.request, urllib.parse
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes
import base64
KEY_ID="49GTV8CYDL"; ISS="c339f867-098c-4f00-95ae-c58b988b2426"; APP="6760654324"
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def token():
    key=serialization.load_pem_private_key(open("/Users/jordyverwerft/dev/migraineme-ios/AuthKey_49GTV8CYDL.p8","rb").read(), None)
    h=b64(json.dumps({"alg":"ES256","kid":KEY_ID,"typ":"JWT"}).encode()); now=int(time.time())
    p=b64(json.dumps({"iss":ISS,"iat":now,"exp":now+1100,"aud":"appstoreconnect-v1"}).encode())
    sig=key.sign(f"{h}.{p}".encode(), ec.ECDSA(hashes.SHA256()))
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
    r,s=decode_dss_signature(sig); raw=r.to_bytes(32,"big")+s.to_bytes(32,"big")
    return f"{h}.{p}.{b64(raw)}"
def request(method, path, body=None):
    req=urllib.request.Request("https://api.appstoreconnect.apple.com"+path, data=json.dumps(body).encode() if body else None, method=method)
    req.add_header("Authorization","Bearer "+token()); req.add_header("Content-Type","application/json")
    try:
        with urllib.request.urlopen(req) as r: return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e: return {"error":e.code,"body":e.read().decode()[:800]}
if __name__=="__main__":
    b=request("GET",f"/v1/builds?filter[app]={APP}&sort=-uploadedDate&limit=5&fields[builds]=version,uploadedDate,processingState,expired")
    for x in b.get("data",[]): print("build", x["attributes"]["version"], x["attributes"]["uploadedDate"][:16], x["attributes"]["processingState"], "expired" if x["attributes"]["expired"] else "")
    v=request("GET",f"/v1/apps/{APP}/appStoreVersions?limit=4&fields[appStoreVersions]=versionString,appStoreState,createdDate")
    for x in v.get("data",[]): print("version", x["attributes"]["versionString"], x["attributes"]["appStoreState"], x["attributes"]["createdDate"][:10], x["id"])
    if "error" in b: print(b)
