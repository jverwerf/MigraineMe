import json, time, base64, urllib.request, urllib.parse, sys
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import padding
SA=json.load(open("/Users/jordyverwerft/keys/play-publisher.json")); PKG="app.migraineme"
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
_tok=None
def token():
    global _tok
    if _tok: return _tok
    key=serialization.load_pem_private_key(SA["private_key"].encode(), None); now=int(time.time())
    h=b64(json.dumps({"alg":"RS256","typ":"JWT"}).encode())
    p=b64(json.dumps({"iss":SA["client_email"],"scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","iat":now,"exp":now+3000}).encode())
    sig=key.sign(f"{h}.{p}".encode(), padding.PKCS1v15(), hashes.SHA256())
    data=urllib.parse.urlencode({"grant_type":"urn:ietf:params:oauth:grant-type:jwt-bearer","assertion":f"{h}.{p}.{b64(sig)}"}).encode()
    _tok=json.loads(urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token",data=data)).read())["access_token"]; return _tok
def req(method, path, body=None, raw=None, ctype="application/json"):
    r=urllib.request.Request("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"+PKG+path, data=raw if raw is not None else (json.dumps(body).encode() if body else None), method=method)
    r.add_header("Authorization","Bearer "+token()); r.add_header("Content-Type",ctype)
    try:
        with urllib.request.urlopen(r, timeout=600) as x: return json.loads(x.read() or b"{}")
    except urllib.error.HTTPError as e: return {"error":e.code,"body":e.read().decode()[:600]}
if __name__=="__main__":
    e=req("POST","/edits",{}); eid=e.get("id"); print("edit", eid or e)
    for t in ["production","internal","beta"]:
        print(t, json.dumps(req("GET",f"/edits/{eid}/tracks/{t}").get("releases",[{}])[:2])[:400])
    req("DELETE",f"/edits/{eid}")
