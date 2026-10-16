#!/usr/bin/env python3
"""Bundle web builds into the APK:  python3 tools/bundle_app.py path/to/foocraft-web-1.2.3.zip [...]

Unpacks each zip into app/src/main/assets/apps/<id>/ and writes the manifest.json the launcher expects.
README/HOSTING/.htaccess/_headers are dropped (licenses and notices are kept)."""
import io, json, os, re, shutil, sys, zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "apps")
DROP = {"README.md", "HOSTING.md", ".htaccess", "_headers"}

def bundle(zpath):
    base = os.path.basename(zpath)
    m = re.match(r"^(.+?)-web-v?(\d[0-9A-Za-z.+_-]*?)(?:\.zip)?$", base)
    if not m:
        sys.exit(f"{base}: expected a name like <app>-web-<version>.zip")
    app_id, version = m.group(1).lower(), m.group(2)
    with zipfile.ZipFile(zpath) as z0:  # the folder inside the zip usually has the authoritative name
        for n in z0.namelist():
            fm = re.match(r"^([^/]+?)-web-v?(\d[0-9A-Za-z.+_-]*)/", n)
            if fm:
                app_id, version = fm.group(1).lower(), fm.group(2)
                break
    out = os.path.join(ROOT, app_id)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    with zipfile.ZipFile(zpath) as z:
        names = [n for n in z.namelist() if not n.startswith("__MACOSX")]
        tops = {n.split("/")[0] for n in names if "/" in n}
        strip = tops.pop() + "/" if len(tops) == 1 and not any("/" not in n for n in names) else ""
        for n in names:
            rel = n[len(strip):] if strip and n.startswith(strip) else n
            if not rel or rel.endswith("/") or os.path.basename(rel) in DROP:
                continue
            dest = os.path.normpath(os.path.join(out, rel))
            if not dest.startswith(os.path.normpath(out) + os.sep):
                sys.exit("unsafe path in zip: " + n)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(n) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
    html = open(os.path.join(out, "index.html"), encoding="utf-8").read()
    t = re.search(r"<title>\s*([^<]*?)\s*</title>", html, re.I | re.S)
    manifest = {"id": app_id, "name": t.group(1) if t else app_id, "version": version}
    for pat in (r'<link[^>]*\brel=["\']icon["\'][^>]*\bhref=["\']([^"\']+)["\']',
                r'<link[^>]*\bhref=["\']([^"\']+)["\'][^>]*\brel=["\']icon["\']'):
        mm = re.search(pat, html, re.I)
        if mm and os.path.isfile(os.path.join(out, mm.group(1).removeprefix("./"))):
            manifest["icon"] = mm.group(1).removeprefix("./")
            break
    json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=2)
    print("bundled", manifest)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for z in sys.argv[1:]:
        bundle(z)
