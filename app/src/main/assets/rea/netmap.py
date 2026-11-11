# Builds a "Network, servers and secrets" section from a JADX-decompiled source tree, so a rebuilt
# app can target the same backend. Reads only decompiled text; connects to nothing.
#   netmap.py SRC_DIR PACKAGE   (PACKAGE e.g. com.aiapp; "" if unknown)
import os
import re
import sys

SRC = sys.argv[1]
PKG = (sys.argv[2] if len(sys.argv) > 2 else "").strip()
pkg_path = "/" + PKG.replace(".", "/") + "/" if PKG else None

# Hosts that are documentation, licences, build tooling or XML namespaces, never an app's backend.
NOISE = re.compile(r"(^|\.)(w3\.org|xmlpull\.org|schemas\.android\.com|opensource\.org|antlr\.org|smali\.org|"
                   r"apache\.org|slf4j\.org|kotlinlang\.org|kotl\.in|jetbrains\.com|googlesource\.com|"
                   r"gradle\.org|maven\.org|jitpack\.io|goo\.gle|github\.com|issuetracker\.google\.com|"
                   r"example\.com|localhost|127\.0\.0\.1|json-schema\.org|iana\.org|ietf\.org|unicode\.org)$", re.I)

url_re = re.compile(r'https?://[A-Za-z0-9._~:/?#@!$&()*+,;=%\-]+')
host_re = re.compile(r'https?://([^/"\s:]+)')
anno_re = re.compile(r'@(GET|POST|PUT|DELETE|PATCH|HEAD)\(\s*"([^"]*)"')
header_re = re.compile(r'"(Authorization|Proxy-Authorization|X-[A-Za-z-]+|x-[a-z-]+|api[-_]?key|anthropic-version|'
                       r'openai-[a-z-]+|Bearer [^"]{0,20})"')
# A constant that looks like a base URL or an endpoint path.
baseurl_re = re.compile(r'(?:val|var|const|String|final)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"(https?://[^"]+|/[A-Za-z0-9._/{}-]{2,})"')
# A candidate baked-in secret: a key-ish name assigned a long opaque literal.
secret_name_re = re.compile(r'([A-Za-z_][A-Za-z0-9_]*(?:KEY|TOKEN|SECRET|CLIENT_ID|CLIENT_SECRET|APP_ID|API)[A-Za-z0-9_]*)\s*=\s*"([^"]{12,120})"', re.I)
secret_val_ok = re.compile(r'^[A-Za-z0-9_\-./+=]{12,120}$')


def walk():
    app, lib = [], []
    for root, _dirs, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith((".java", ".kt", ".smali")):
                continue
            p = os.path.join(root, fn)
            (app if pkg_path and pkg_path in p.replace("\\", "/") else lib).append(p)
    # When the package is known, read its own files first and cap library files (they add noise and bytes).
    return (app + lib[:4000]) if pkg_path else lib[:4000]


hosts = {}
urls = {}
endpoints = {}
headers = {}
annos = {}
bases = {}
secrets = {}
app_hosts = set()
scanned = 0

for p in walk():
    is_app = bool(pkg_path) and pkg_path in p.replace("\\", "/")
    try:
        t = open(p, encoding="utf-8", errors="ignore").read()
    except Exception:
        continue
    scanned += 1
    for m in url_re.findall(t):
        u = m.rstrip('.,);"\'')
        h = host_re.match(u)
        host = h.group(1).lower() if h else None
        if not host or NOISE.search(host):
            continue
        hosts[host] = hosts.get(host, 0) + 1
        if is_app:
            app_hosts.add(host)
        if len(u) > len("https://" + host) + 1:
            urls[u] = urls.get(u, 0) + 1
    for verb, path in anno_re.findall(t):
        annos.setdefault(f"{verb} {path}", 0)
        annos[f"{verb} {path}"] += 1
    for h in header_re.findall(t):
        headers[h] = headers.get(h, 0) + 1
    for name, val in baseurl_re.findall(t):
        if val.startswith("http") and not NOISE.search(host_re.match(val).group(1).lower()):
            bases[f"{name} = {val}"] = 1
        elif val.startswith("/") and is_app and 2 < len(val) < 80:
            endpoints[val] = endpoints.get(val, 0) + 1
    if is_app or not pkg_path:
        for name, val in secret_name_re.findall(t):
            if secret_val_ok.match(val) and not val.lower().startswith(("http", "application/", "android.", "com.", "androidx.")):
                secrets[name] = (val[:6] + "…" + val[-4:]) if len(val) > 14 else "(found)"

out = []
w = out.append
w("## Network, servers and secrets (grep of the decompiled code)")
w("These are the servers and requests the original app has in its code. The new app should use the SAME base "
  "URLs and endpoints and the SAME request shape. Note: a server may still reject a third-party client that "
  "cannot pass the official app's signature check, certificate pinning or per-user login.")

ranked = sorted(hosts.items(), key=lambda kv: (kv[0] not in app_hosts, -kv[1]))
if ranked:
    w(f"\n### Backend hosts ({len(ranked)})")
    for host, n in ranked[:40]:
        tag = " — in the app's own code" if host in app_hosts else ""
        w(f"- `{host}` ({n}×){tag}")

full = sorted(urls.items(), key=lambda kv: (host_re.match(kv[0]).group(1).lower() not in app_hosts, -kv[1]))
if full:
    w(f"\n### Full URLs seen in code ({len(full)})")
    for u, _n in full[:45]:
        w(f"- {u[:160]}")

if bases:
    w(f"\n### Base-URL / endpoint constants ({len(bases)})")
    for b in list(bases)[:40]:
        w(f"- `{b[:160]}`")

if endpoints:
    ep = sorted(endpoints.items(), key=lambda kv: -kv[1])
    w(f"\n### Endpoint paths in the app's code ({len(ep)})")
    w(", ".join(f"`{p}`" for p, _ in ep[:60]))

if annos:
    w(f"\n### Retrofit-style endpoints ({len(annos)})")
    for a, _n in sorted(annos.items())[:60]:
        w(f"- `{a}`")

if headers:
    hs = sorted(headers.items(), key=lambda kv: -kv[1])
    w(f"\n### Request headers and auth scheme ({len(hs)})")
    w(", ".join(f"`{h}`×{n}" for h, n in hs[:30]))

if secrets:
    w(f"\n### Candidate baked-in keys ({len(secrets)}) — masked; the real value is in the APK")
    for name, masked in list(secrets.items())[:25]:
        w(f"- `{name}` = {masked}")
    w("A key found here is in the APK and can be read from it; a key fetched at runtime or hidden in native "
      "code is not here and usually cannot be recovered.")

if len(out) == 2:
    w("\n- No backend servers were found in the code (the app may use string-encrypted URLs, native networking, "
      "or contact no server).")

w(f"\n(Scanned {scanned} decompiled files.)")
print("\n".join(out))
