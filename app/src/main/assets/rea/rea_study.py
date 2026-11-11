#!/usr/bin/env python3
"""Runs REA (github.com/morluto/rea) against one target and writes a compact evidence report.

Usage:
  rea_study.py apk  /path/app.apk       OUT_DIR
  rea_study.py web  https://site.example OUT_DIR   (a Chrome with --remote-debugging-port=9222 must be running on that URL)

Writes OUT_DIR/rea-evidence.md (short, for the model), OUT_DIR/raw/*.json (full REA Evidence) and,
for websites, OUT_DIR/screenshot.png. Never invents data: a step that fails is listed as failed.
"""
import base64
import json
import os
import re
import subprocess
import sys
import time

MODE, TARGET, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
RAW = os.path.join(OUT, "raw")
os.makedirs(RAW, exist_ok=True)
REA = os.environ.get("REA_BIN", "rea")
CDP = os.environ.get("REA_CDP", "http://127.0.0.1:9222")
BUDGET = int(os.environ.get("REA_BUDGET_SECONDS", "420"))
START = time.time()
failed = []
limits = []


def left():
    return BUDGET - (time.time() - START)


def rea(name, *args, timeout=240):
    """Runs one REA command with --json; returns its normalized_result, or None when it failed."""
    try:
        p = subprocess.run([REA, *args, "--json"], capture_output=True, text=True, timeout=timeout)
    except Exception as e:  # timeout or missing binary
        failed.append(f"{name}: {type(e).__name__}")
        return None
    try:
        d = json.loads(p.stdout)
    except Exception:
        failed.append(f"{name}: no JSON output ({(p.stderr or p.stdout).strip()[-160:]})")
        return None
    with open(os.path.join(RAW, re.sub(r"[^A-Za-z0-9_.-]", "_", name) + ".json"), "w") as f:
        json.dump(d, f)
    if "normalized_result" not in d:
        failed.append(f"{name}: {d.get('code', 'error')} - {str(d.get('message', ''))[:160]}")
        return None
    for l in d.get("limitations") or []:
        if l not in limits:
            limits.append(l)
    return d["normalized_result"]


def version():
    try:
        return subprocess.run([REA, "--version"], capture_output=True, text=True, timeout=30).stdout.strip().splitlines()[-1]
    except Exception:
        return "unknown"


out = []
w = out.append


def cap(text, n):
    text = text or ""
    return text if len(text) <= n else text[:n] + "\n… (cut)"


# ------------------------------------------------------------------------------------------------- APK
def study_apk(apk):
    w(f"# REA evidence for `{os.path.basename(apk)}`")
    w(f"Produced by REA {version()} (github.com/morluto/rea) with its JADX provider. The APK was read, never run.")
    pkg = rea("inspect-android-package", "inspect-android-package", apk)
    package = ""
    components = []
    if pkg:
        package = pkg.get("package_name") or ""
        w("\n## Package")
        w(f"- package `{package}`, version {pkg.get('version_name')} ({pkg.get('version_code')}), minSdk {pkg.get('min_sdk')}, targetSdk {pkg.get('target_sdk')}")
        w(f"- classes in APK: {pkg.get('class_count')}, resources: {pkg.get('resource_count')}")
        perms = pkg.get("permissions") or []
        w(f"- permissions ({len(perms)}): " + ", ".join(p.replace('android.permission.', '') for p in perms[:40]))
        components = (pkg.get("components") or {}).get("items") or []
        w(f"\n## Components declared in the manifest ({len(components)})")
        for c in components[:60]:
            extra = []
            if c.get("exported"):
                extra.append("exported")
            if c.get("actions"):
                extra.append("actions: " + ", ".join(a.split('.')[-1] for a in c["actions"][:4]))
            if c.get("authorities"):
                extra.append("authorities: " + ", ".join(c["authorities"][:2]))
            w(f"- {c.get('kind')}: `{c.get('name')}`" + (f" ({'; '.join(extra)})" if extra else ""))

    sig = rea("inspect-apk-signature", "inspect-apk-signature", apk, timeout=120)
    if sig:
        w("\n## Signing")
        for scheme, v in (sig.get("schemes") or {}).items():
            if isinstance(v, dict) and v.get("present"):
                for s in (v.get("signers") or [])[:2]:
                    cert = s.get("certificate") or {}
                    subject = (cert.get("subject") or "").replace("\n", ", ")
                    w(f"- {scheme}: {subject} (SHA-256 {str(cert.get('sha256_fingerprint'))[:16]}…)")

    fl = rea("identify-flutter-build", "identify-flutter-build", apk, timeout=120)
    if fl:
        w("\n## Runtime")
        if fl.get("flutter_detected"):
            w(f"- **Flutter app** (ABIs: {', '.join(fl.get('abis') or [])}). The app's logic is compiled Dart in libapp.so; Java/Kotlin classes are only the thin shell.")
        else:
            w("- Not a Flutter app (no Dart snapshot found).")

    # The app's own classes: search by package name, then by the packages of its components.
    prefixes = []
    if package:
        prefixes.append(package)
    for c in components:
        n = c.get("name") or ""
        if n and not n.startswith(("android.", "androidx.", "com.google.", "kotlin")):
            p = n.rsplit(".", 1)[0]
            root = ".".join(p.split(".")[:3])
            if root and root not in prefixes and not any(root.startswith(x) for x in prefixes):
                prefixes.append(root)
    own = []
    total = None
    for i, prefix in enumerate(prefixes[:3]):
        if left() < 120:
            break
        res = rea(f"search-android-classes-{i}", "search-android-classes", apk, prefix)
        if res:
            total = res.get("total_classes")
            for m in res.get("matches") or []:
                if m not in own:
                    own.append(m)
    plain = [c for c in own if "$" not in c]
    if own:
        w(f"\n## The app's own classes ({len(plain)} top-level under {', '.join('`'+p+'`' for p in prefixes[:3])}; {total} classes in the whole APK)")
        short = sum(1 for c in plain if len(c.rsplit('.', 1)[-1]) <= 2)
        if plain and short > len(plain) * 0.5:
            w("- Most class names are 1-2 letters: the code is obfuscated, so names carry little meaning.")
        w(cap(", ".join(c[len(prefixes[0]) + 1:] if c.startswith(prefixes[0] + '.') else c for c in plain[:220]), 3000))

    # Rank: manifest components first, then classes whose names suggest screens, state and data.
    comp_names = [c.get("name") for c in components if c.get("name") in own or (c.get("name") or "").startswith(tuple(prefixes) or ("\0",))]
    launcher = next((c.get("name") for c in components if "android.intent.category.LAUNCHER" in (c.get("categories") or [])), None)
    keys = ("Activity", "Fragment", "Screen", "ViewModel", "Repository", "Service", "Api", "Client", "Manager", "Database", "Dao", "Navigation", "Application")
    ranked = []
    for c in ([launcher] if launcher else []) + comp_names + [c for c in plain if any(k in c.rsplit('.', 1)[-1] for k in keys)] + plain:
        if c and c not in ranked and not c.startswith(("android.", "androidx.", "com.google.")):
            ranked.append(c)

    inspected = 0
    blocks = []
    for c in ranked:
        if left() < 75 or inspected >= 14:
            break
        res = rea(f"inspect-android-class-{inspected}", "inspect-android-class", apk, c)
        inspected += 1
        if not res:
            continue
        methods = [m.get("reported_signature") for m in res.get("methods") or []
                   if m.get("name") and not m["name"].startswith(("$r8$", "<clinit>", "access$")) and "$lambda$" not in m["name"]]
        fields = [f"{f.get('name')}: {f.get('reported_type')}" for f in res.get("fields") or [] if not str(f.get("name")).startswith("$")]
        def brief(sig):  # drop package names inside signatures to save space
            return re.sub(r"\b(?:[a-z_][a-z0-9_]*\.)+([A-Z])", r"\1", sig or "")
        blocks.append(f"### `{c}`\n" + (f"- fields: {cap(', '.join(brief(x) for x in fields[:14]), 380)}\n" if fields else "")
                      + f"- methods ({len(methods)}): {cap('; '.join(brief(x) for x in methods[:22]), 620)}")
    if launcher and left() > 45:
        res = rea("inspect-android-method-launcher", "inspect-android-method", apk, launcher, "onCreate")
        if res and (res.get("source") or {}).get("text"):
            w(f"\n## Decompiled entry point: `{launcher}.onCreate`")
            w("```java\n" + cap(res["source"]["text"], 2200) + "\n```")

    if blocks:
        used = sum(len(x) for x in out)
        keep = []
        for blk in blocks:
            if used + len(blk) > 10500:
                break
            keep.append(blk)
            used += len(blk)
        w(f"\n## Members of {len(keep)} key classes")
        out.extend(keep)


# ------------------------------------------------------------------------------------------------- Website
def study_web(url):
    w(f"# REA evidence for {url}")
    w(f"Produced by REA {version()} (github.com/morluto/rea) attached to a real Chrome that loaded the page and ran its JavaScript.")
    targets = rea("list-browser-targets", "list-browser-targets", CDP, timeout=60)
    pages = (targets or {}).get("targets") or []
    if not pages:
        w("\nThe browser did not expose a page for this URL, so nothing could be observed.")
        return
    t = pages[0]
    tid = t.get("target_id")
    w(f"\n## Page\n- title: {t.get('title')}\n- final URL: {t.get('url')}")
    host = re.sub(r"^https?://", "", url).split("/")[0].lower()
    final_host = re.sub(r"^https?://", "", t.get("url") or "").split("/")[0].lower()
    if final_host and host.split(":")[0].lstrip("www.") not in final_host:
        w(f"- The site redirected to `{final_host}` (often a sign-in page): only this public page was observed.")

    # 1. Requests made while loading + real colours, fonts and visible controls (browser capture, not REA).
    cap_file = os.path.join(RAW, "browser-capture.json")
    capture = None
    try:
        subprocess.run(["node", os.path.join(os.path.dirname(os.path.abspath(__file__)), "capture.mjs"), CDP, tid, cap_file, "10"],
                       capture_output=True, text=True, timeout=90)
        capture = json.load(open(cap_file))
    except Exception as e:
        failed.append(f"browser capture: {type(e).__name__}")
    time.sleep(2)

    # 2. REA: page structure, accessibility tree, scripts, resources, storage.
    page = rea("inspect-web-page", "inspect-web-page", CDP, tid, "--observation-ms", "3000",
               "--include-accessibility-text", "--include-json-body-shapes", "--include-storage-keys", timeout=150)
    if page:
        nodes = (page.get("accessibility") or {}).get("nodes") or []
        keep = ("button", "link", "textbox", "searchbox", "combobox", "checkbox", "radio", "tab", "menuitem", "heading",
                "switch", "slider", "dialog", "navigation", "banner", "main", "grid", "gridcell", "columnheader", "img", "listitem", "option")
        seen = set()
        lines = []
        for n in nodes:
            role, name = n.get("role"), (n.get("name") or "").strip()
            if n.get("ignored") or role not in keep or (not name and role not in ("navigation", "banner", "main", "grid", "dialog")):
                continue
            key = (role, name)
            if key in seen:
                continue
            seen.add(key)
            lines.append(f"- {role}: {name[:90]}" if name else f"- {role}")
        w(f"\n## What is on screen (REA accessibility tree: {len(nodes)} nodes, {len(lines)} distinct controls shown)")
        w(cap("\n".join(lines[:170]), 6500))
        dom = (page.get("dom") or {}).get("nodes") or []
        tags = {}
        for n in dom:
            if n.get("node_type") == 1:
                tags[n.get("node_name")] = tags.get(n.get("node_name"), 0) + 1
        top = sorted(tags.items(), key=lambda x: -x[1])[:22]
        w(f"\n## Page structure (REA DOM: {(page.get('dom') or {}).get('total_nodes')} nodes)")
        w("- elements: " + ", ".join(f"{k.lower()}×{v}" for k, v in top))
        scripts = (page.get("scripts") or {}).get("items") or []
        w(f"\n## Scripts and resources (REA: {len(scripts)} scripts)")
        for s in sorted(scripts, key=lambda s: -(s.get("length") or 0))[:14]:
            if s.get("url"):
                w(f"- script {s['url'][:140]} ({s.get('length')} chars{', has source map' if s.get('source_map_url') else ''})")
        res_types = {}
        for r in page.get("resources") or []:
            res_types[r.get("type")] = res_types.get(r.get("type"), 0) + 1
        if res_types:
            w("- other resources: " + ", ".join(f"{k}×{v}" for k, v in res_types.items()))
        st = page.get("storage") or {}
        keys = (st.get("local_storage_keys") or []) + (st.get("session_storage_keys") or [])
        if keys or st.get("indexed_db_names"):
            w(f"- browser storage: keys {', '.join(map(str, keys[:25]))}; IndexedDB {', '.join(map(str, st.get('indexed_db_names') or []))}")
        reqs = (page.get("network") or {}).get("requests") or []
        shaped = [r for r in reqs if r.get("json_body_shape") or r.get("response_json_shape")]
        if shaped:
            w(f"- JSON responses seen after load: {len(shaped)}")

    # 3. REA: static analysis of the page's JavaScript bundle (routes, endpoints, source maps).
    bundle = rea("analyze-web-bundle", "analyze-web-bundle", CDP, tid, timeout=200) if left() > 60 else None
    if bundle:
        obs = bundle.get("observations") or {}
        routes = [r.get("value") or r.get("path") or str(r)[:80] for r in obs.get("routes") or []]
        endpoints = obs.get("endpoints") or []
        capt = bundle.get("capture") or {}
        w(f"\n## JavaScript bundle (REA static analysis: {capt.get('scripts_analyzed')} of {capt.get('scripts_observed')} scripts parsed)")
        if routes:
            w(f"- routes ({len(routes)}): " + cap(", ".join(map(str, routes[:60])), 1800))
        if endpoints:
            seen = []
            for e in endpoints:
                v = f"{e.get('value')} [{e.get('mechanism')}]"
                if v not in seen:
                    seen.append(v)
            w(f"- endpoints referenced in code ({len(seen)}): " + cap(", ".join(seen[:70]), 2600))
        if not routes and not endpoints:
            w("- No routes or endpoints could be read from the code (minified bundles often hide them).")

    # 4. REA: screenshot of the rendered page.
    shot = rea("capture-web-screenshot", "capture-web-screenshot", CDP, tid, timeout=90) if left() > 20 else None
    if shot and (shot.get("artifact") or {}).get("data_base64"):
        with open(os.path.join(OUT, "screenshot.png"), "wb") as f:
            f.write(base64.b64decode(shot["artifact"]["data_base64"]))
        vp = shot.get("viewport") or {}
        w(f"\n## Screenshot\n- saved as screenshot.png ({vp.get('width')}×{vp.get('height')})")
        # keep the raw file small: the image is stored once, as the PNG
        try:
            p = os.path.join(RAW, "capture-web-screenshot.json")
            d = json.load(open(p))
            d["normalized_result"]["artifact"]["data_base64"] = "(saved as screenshot.png)"
            json.dump(d, open(p, "w"))
        except Exception:
            pass

    if capture:
        p = capture.get("page") or {}
        if "error" not in p:
            w("\n## Look and feel (read from the rendered page by a browser script, not by REA)")
            w(f"- body: background {p.get('bodyBackground')}, text {p.get('bodyColor')}, font {str(p.get('bodyFont'))[:80]}, size {p.get('bodyFontSize')}")
            w(f"- background colours by use: {', '.join(p.get('backgrounds') or [])}")
            w(f"- text colours by use: {', '.join(p.get('textColors') or [])}")
            w(f"- fonts by use: {', '.join(p.get('fonts') or [])}; sizes: {', '.join(p.get('fontSizes') or [])}")
            if p.get("frameworks"):
                w(f"- framework signs: {', '.join(p['frameworks'])}")
            if p.get("landmarks"):
                w("- layout regions (size at position, 1280px wide window): " + cap("; ".join(p["landmarks"][:40]), 1800))
            if p.get("headings"):
                w("- headings: " + cap(" | ".join(p["headings"]), 900))
            if p.get("buttons"):
                w("- buttons: " + cap(" | ".join(dict.fromkeys(p["buttons"])), 1800))
            if p.get("inputs"):
                w("- inputs: " + cap(" | ".join(p["inputs"]), 900))
            if p.get("links"):
                w("- links: " + cap(" | ".join(p["links"][:50]), 1800))
            if p.get("localStorageKeys"):
                w("- localStorage keys: " + ", ".join(p["localStorageKeys"]))
            if p.get("visibleText"):
                w("\n### Visible text (first part)\n" + cap(p["visibleText"], 2200))
        reqs = capture.get("requests") or []
        if reqs:
            api = [r for r in reqs if r.get("type") in ("Fetch", "XHR", "WebSocket", "EventSource")]
            w(f"\n## Requests while the page loaded ({len(reqs)} total, {len(api)} data calls; browser capture)")
            seen = []
            for r in api[:120]:
                u = re.sub(r"\?.*$", "?…", r.get("url") or "")
                line = f"- {r.get('method')} {u[:150]} -> {r.get('status')} {r.get('mime') or ''}"
                if line not in seen:
                    seen.append(line)
            w(cap("\n".join(seen[:45]), 4200) if seen else "- No data calls: the page loaded only documents, scripts, styles and images.")
            hosts = {}
            for r in reqs:
                h = re.sub(r"^[a-z]+://", "", r.get("url") or "").split("/")[0]
                hosts[h] = hosts.get(h, 0) + 1
            w("- hosts contacted: " + ", ".join(f"{h}×{n}" for h, n in sorted(hosts.items(), key=lambda x: -x[1])[:15]))


try:
    if MODE == "apk":
        study_apk(TARGET)
    else:
        study_web(TARGET)
except Exception as e:  # never lose what was already collected
    failed.append(f"study stopped early: {type(e).__name__}: {e}")

w("\n## Limits of this evidence")
if MODE == "web":
    w("- Only what a signed-out visitor sees was observed; pages behind a login, and logic that runs on the site's servers, are not in this evidence.")
else:
    w("- Server-side logic is not inside an APK and is not in this evidence.")
for l in limits[:4]:
    w("- " + (l if len(l) <= 240 else l[:240] + "…"))
if failed:
    w("\n## Steps that did not complete")
    for f in failed[:15]:
        w("- " + f)
w(f"\n(Collected in {int(time.time() - START)} s.)")

with open(os.path.join(OUT, "rea-evidence.md"), "w") as f:
    f.write("\n".join(out) + "\n")
print("\n".join(out))
