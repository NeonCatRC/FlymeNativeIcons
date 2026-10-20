# SPDX-FileCopyrightText: 2026 NeonCatRC
# SPDX-License-Identifier: AGPL-3.0-only
"""Build "Значки Flyme" (ru.neoncatrc.flymeicons) on top of the Flyme 12.6 Calculator.

The Calculator APK is used only as a carrier of the Flyme 12 UI library (flyme.support,
com.meizu.common, androidx) and its resource table. R8 inlined the resource ids into the
library code, so the base resources must keep their ids: we decode with apktool, replace the
manifest, add our own resources (prefix fi_), rebuild, generate R.java from the built APK and
compile our Java code into an extra classesN.dex.

  python -I build.py [--install]

Paths come from environment variables (FI_<KEY>), then build.local.json next to this script (see
build.local.example.json), then defaults: the Android SDK from ANDROID_HOME / ANDROID_SDK_ROOT,
adb from PATH, build output and the signing key inside this folder (build/, keys/ - both ignored
by git). If the base APK is not found it is pulled from the connected phone
(/system/MzApp/FlymeCalculator).
"""
import datetime
import glob
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
PATH_KEYS = ("build_tools", "android_jar", "apktool", "keys", "base_apk", "work")


def _config():
    local = {}
    p = os.path.join(HERE, "build.local.json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            local = json.load(f)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    defaults = {
        "build_tools": os.path.join(sdk, "build-tools", "36.0.0") if sdk else "",
        "android_jar": os.path.join(sdk, "platforms", "android-36", "android.jar") if sdk else "",
        "apktool": "",
        "keys": "keys",
        "base_apk": os.path.join("build", "FlymeCalculator.apk"),
        "work": "build",
        "adb": "adb",
        "device": "",
        # subject of a newly generated signing certificate
        "key_dname": "CN=Flyme Icons",
    }
    cfg = {k: os.environ.get("FI_" + k.upper(), local.get(k, v)) for k, v in defaults.items()}
    for k in PATH_KEYS:
        if cfg[k] and not os.path.isabs(cfg[k]):
            cfg[k] = os.path.normpath(os.path.join(HERE, cfg[k]))
    return cfg


def check_config():
    missing = [k for k in ("build_tools", "android_jar", "apktool") if not cfg_exists(CFG[k])]
    if missing:
        raise SystemExit("set " + ", ".join(missing) + " in build.local.json (see build.local.example.json)"
                         " or as FI_<KEY> environment variables")


def cfg_exists(p):
    return bool(p) and os.path.exists(p)


def adb(*args):
    """adb command line for the configured device (or the only connected one)."""
    return [ADB] + (["-s", CFG["device"]] if CFG["device"] else []) + list(args)


CFG = _config()
BT = CFG["build_tools"]
ANDROID_JAR = CFG["android_jar"]
APKTOOL = CFG["apktool"]
KEYS = CFG["keys"]
BASE_APK = CFG["base_apk"]
WORK = CFG["work"]
BASE_DIR = os.path.join(WORK, "base")
SRC_DIR = os.path.join(WORK, "src")
OUT_APK = os.path.join(WORK, "flymeicons.apk")
ADB = CFG["adb"]

PKG = "ru.neoncatrc.flymeicons"
VERSION_CODE = 9
# the build stamp tells every build apart; VERSION_CODE is raised by hand for real updates.
# No colon in it: apktool / aapt2 cut the version name there.
VERSION_NAME = "Release[" + datetime.datetime.now().strftime("%Y%m%d-%H%M") + "]"
# API 33: monochrome icon layers, typed getParcelableExtra, registerReceiver flags
MIN_SDK = 33
TARGET_SDK = 34

# Flyme 12.6.0.0A system Calculator 12.0.17: the R8 names in java/stubs belong to this build.
BASE_DEVICE_PATH = "/system/MzApp/FlymeCalculator/FlymeCalculator.apk"
BASE_SHA256 = "fa1f884a1b41cf0d37971a04c1d5438792953cb98a07e6e8056f6c0b96b86bdb"


def tool(name):
    """A build-tools executable (aapt2.exe / d8.bat on Windows, plain names elsewhere)."""
    if os.name == "nt":
        name += ".bat" if name in ("d8", "apksigner") else ".exe"
    return os.path.join(BT, name)


def run(cmd, **kw):
    r = subprocess.run(cmd, **kw)
    if r.returncode:
        raise SystemExit(f"failed ({r.returncode}): {' '.join(map(str, cmd))[:300]}")
    return r


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


# --------------------------------------------------------------------------- base

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_base():
    """Decode the base APK into build/base; decoded again whenever the APK itself changes."""
    stamp = os.path.join(WORK, "base.sha256")
    decoded = os.path.exists(os.path.join(BASE_DIR, "apktool.yml")) and os.path.exists(stamp)
    apk = BASE_APK if os.path.exists(BASE_APK) else os.path.join(WORK, "FlymeCalculator.apk")
    if not os.path.exists(apk):
        if decoded:
            return  # decoded earlier, the APK is gone since
        os.makedirs(WORK, exist_ok=True)
        print("base APK not found, pulling it from the phone:", BASE_DEVICE_PATH)
        run(adb("pull", BASE_DEVICE_PATH, apk))
    digest = sha256(apk)
    if decoded and read(stamp).strip() == digest:
        return
    if digest != BASE_SHA256:
        print(f"WARNING: base APK sha256 {digest} differs from the tested build; R8 names in "
              "java/stubs may not match (the link check will tell)")
    run(["java", "-jar", APKTOOL, "d", "-s", "-f", "-o", BASE_DIR, apk])
    write(stamp, digest + "\n")


def prepare_src():
    if os.path.exists(SRC_DIR):
        shutil.rmtree(SRC_DIR)
    shutil.copytree(BASE_DIR, SRC_DIR)
    # Calculator assets (tax tables, html) are not ours; keep the icon fonts the library needs.
    assets = os.path.join(SRC_DIR, "assets")
    for name in os.listdir(assets):
        p = os.path.join(assets, name)
        if os.path.isfile(p) and not name.lower().endswith((".ttf", ".otf")):
            os.remove(p)
        elif os.path.isdir(p):
            shutil.rmtree(p)
    shutil.rmtree(os.path.join(SRC_DIR, "original", "META-INF"), ignore_errors=True)


def patch_apktool_yml():
    p = os.path.join(SRC_DIR, "apktool.yml")
    t = read(p)
    t = re.sub(r"minSdkVersion: *'?\d+'?", f"minSdkVersion: {MIN_SDK}", t)
    t = re.sub(r"targetSdkVersion: *'?\d+'?", f"targetSdkVersion: {TARGET_SDK}", t)
    t = re.sub(r"versionCode: *'?\d+'?", f"versionCode: {VERSION_CODE}", t)
    t = re.sub(r"versionName: *[^\n]+", f"versionName: '{VERSION_NAME}'", t)
    t = t.replace("apkFileName: FlymeCalculator.apk", "apkFileName: flymeicons.apk")
    write(p, t)


def write_manifest():
    t = read(os.path.join(HERE, "AndroidManifest.xml")).replace("{pkg}", PKG)
    write(os.path.join(SRC_DIR, "AndroidManifest.xml"), t)


def add_resources():
    src = os.path.join(HERE, "res")
    n = 0
    for d, _, files in os.walk(src):
        for f in files:
            rel = os.path.relpath(os.path.join(d, f), src)
            dst = os.path.join(SRC_DIR, "res", rel)
            if f.endswith(".xml") and os.path.basename(d).startswith("values"):
                merge_values(os.path.join(d, f), dst)
            else:
                if os.path.exists(dst):
                    raise SystemExit(f"resource clash: {rel}")
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(os.path.join(d, f), dst)
            n += 1
    print(f"resources: {n} files")


def merge_values(src, dst):
    """Our values go into a separate fi_*.xml file in the same folder (apktool merges by folder)."""
    out = os.path.join(os.path.dirname(dst), "fi_" + os.path.basename(src))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    shutil.copyfile(src, out)


# --------------------------------------------------------------------------- R.java

def dump_resources(apk):
    out = run([tool("aapt2"), "dump", "resources", apk], capture_output=True).stdout
    out = out.decode("utf-8", "replace")
    res = {}
    for m in re.finditer(r"resource 0x([0-9a-f]{8}) ([\w$]+)/([\w.$]+)", out):
        rid, typ, name = int(m.group(1), 16), m.group(2), m.group(3)
        res.setdefault(typ, {})[name.replace(".", "_")] = rid
    return res


def write_r_java(res, gen_dir):
    lines = [f"package {PKG};", "", "public final class R {"]
    for typ in sorted(res):
        lines.append(f"    public static final class {typ} {{")
        for name, rid in sorted(res[typ].items()):
            lines.append(f"        public static final int {name} = 0x{rid:08x};")
        lines.append("    }")
    lines.append("}")
    write(os.path.join(gen_dir, *PKG.split("."), "R.java"), "\n".join(lines) + "\n")


# --------------------------------------------------------------------------- java

def sources(d):
    return sorted(glob.glob(os.path.join(d, "**", "*.java"), recursive=True))


def jar_dir(src_dir, jar_path):
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in glob.glob(os.path.join(src_dir, "**", "*.class"), recursive=True):
            z.write(f, os.path.relpath(f, src_dir).replace(os.sep, "/"))


def build_dex(gen_dir):
    work = os.path.join(WORK, "java")
    if os.path.exists(work):
        shutil.rmtree(work)
    stubs_cls, src_cls = os.path.join(work, "stubs"), os.path.join(work, "classes")
    stubs_jar, src_jar = os.path.join(work, "stubs.jar"), os.path.join(work, "classes.jar")
    common = ["javac", "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8",
              "-bootclasspath", ANDROID_JAR + os.pathsep + os.path.join(BT, "core-lambda-stubs.jar"), "-nowarn"]
    run(common + ["-d", stubs_cls] + sources(os.path.join(HERE, "java", "stubs")))
    jar_dir(stubs_cls, stubs_jar)
    run(common + ["-cp", stubs_jar, "-d", src_cls]
        + sources(os.path.join(HERE, "java", "src")) + sources(gen_dir))
    jar_dir(src_cls, src_jar)
    dex_dir = os.path.join(work, "dex")
    os.makedirs(dex_dir)
    run([tool("d8"), "--release", "--min-api", str(MIN_SDK), "--lib", ANDROID_JAR,
         "--classpath", stubs_jar, "--output", dex_dir, src_jar])
    dex = os.path.join(work, "app.dex")
    shutil.copyfile(os.path.join(dex_dir, "classes.dex"), dex)
    return dex


# --------------------------------------------------------------------------- link check

def dex_model(dex_paths):
    """class -> (super, interfaces, {name+sig}, {field name:type})"""
    model = {}
    for p in dex_paths:
        out = run([tool("dexdump"), p], capture_output=True).stdout.decode("utf-8", "replace")
        cls = None
        section = None
        pending_name = None
        for line in out.splitlines():
            m = re.match(r"\s*Class descriptor\s*:\s*'(L[^']+;)'", line)
            if m:
                cls = m.group(1)
                model[cls] = [None, [], set(), set()]
                continue
            if cls is None:
                continue
            m = re.match(r"\s*Superclass\s*:\s*'(L[^']+;)'", line)
            if m:
                model[cls][0] = m.group(1)
                continue
            m = re.match(r"\s*#\d+\s*:\s*'(L[^']+;)'", line)
            if m and section == "interfaces":
                model[cls][1].append(m.group(1))
                continue
            if re.match(r"\s*Interfaces\s*-", line):
                section = "interfaces"
            elif re.match(r"\s*(Static|Instance) fields\s*-", line):
                section = "fields"
            elif re.match(r"\s*(Direct|Virtual) methods\s*-", line):
                section = "methods"
            m = re.match(r"\s*name\s*:\s*'([^']+)'", line)
            if m:
                pending_name = m.group(1)
                continue
            m = re.match(r"\s*type\s*:\s*'([^']+)'", line)
            if m and pending_name:
                if section == "methods":
                    model[cls][2].add(pending_name + m.group(1))
                elif section == "fields":
                    model[cls][3].add(pending_name + ":" + m.group(1))
                pending_name = None
    return model


def link_check(app_dex):
    base = dex_model(glob.glob(os.path.join(SRC_DIR, "classes*.dex")))
    ours = dex_model([app_dex])
    dis = run([tool("dexdump"), "-d", app_dex], capture_output=True).stdout.decode("utf-8", "replace")
    meth_refs = set(re.findall(r"(L[\w/$]+;)\.([\w$<>]+):(\([^\s]*\)[^\s]+)", dis))
    field_refs = set(re.findall(r"(L[\w/$]+;)\.([\w$]+):(\[*L[\w/$]+;|\[*[ZBSCIJFD])\b", dis))
    model = dict(base)
    model.update(ours)

    def lookup(owner, key, kind):
        seen = set()
        todo = [owner]
        reached_framework = False
        while todo:
            c = todo.pop()
            if c in seen:
                continue
            seen.add(c)
            if c not in model:
                reached_framework = True  # android.jar / java: checked by javac
                continue
            sup, ifaces, methods, fields = model[c]
            if key in (methods if kind == "m" else fields):
                return True
            if sup:
                todo.append(sup)
            todo.extend(ifaces)
        return reached_framework

    missing = []
    for owner, name, sig in meth_refs:
        if owner in ours and owner not in base:
            # our class: only matters when resolution walks into the library
            if not lookup(owner, name + sig, "m"):
                missing.append(f"{owner}.{name}{sig}")
            continue
        if owner in base and not lookup(owner, name + sig, "m"):
            missing.append(f"{owner}.{name}{sig}")
    for owner, name, typ in field_refs:
        if owner in base and not lookup(owner, name + ":" + typ, "f"):
            missing.append(f"{owner}.{name}:{typ}")
    if missing:
        print("LINK CHECK: library members missing in base dex (stripped by R8?):")
        for m in sorted(missing):
            print("   ", m)
        raise SystemExit(1)
    print(f"link check: {len(meth_refs)} method refs, {len(field_refs)} field refs OK")


# --------------------------------------------------------------------------- package

def inject_dex(apk, dex):
    with zipfile.ZipFile(apk) as z:
        n = 1 + sum(1 for x in z.namelist() if re.fullmatch(r"classes\d*\.dex", x))
    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, f"classes{n}.dex")
    print(f"injected classes{n}.dex")


def ensure_key():
    """The signing key and its password file; the password never goes on a command line."""
    ks = os.path.join(KEYS, "flyme-icons.jks")
    pw_file = os.path.join(KEYS, "flyme-icons.pass")
    if os.path.exists(ks):
        if not os.path.exists(pw_file):
            raise SystemExit(f"{ks} exists but its password file {pw_file} is missing: put the "
                             "password there (one line) or move the keystore away to make a new key")
        return ks, pw_file
    os.makedirs(KEYS, exist_ok=True)
    pw = secrets.token_urlsafe(18)
    write(pw_file, pw)
    env = dict(os.environ, FI_KS_PASS=pw)
    r = subprocess.run(["keytool", "-genkeypair", "-keystore", ks, "-storepass:env", "FI_KS_PASS",
                        "-keypass:env", "FI_KS_PASS", "-alias", "fi", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "36500", "-dname", CFG["key_dname"]], env=env, capture_output=True, text=True)
    if r.returncode:
        os.remove(pw_file)
        raise SystemExit("keytool failed:\n" + (r.stderr or r.stdout).strip())
    print("new signing key:", ks)
    return ks, pw_file


# --------------------------------------------------------------------------- icon pack template

PACK_SLOTS = 512


def tiny_png(seed):
    """A distinct 1x1 RGBA PNG (placeholder for one icon slot)."""
    import struct
    import zlib

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    raw = b"\x00" + bytes([seed & 255, (seed >> 8) & 255, 7, 0])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def build_pack_template():
    """ADW icon pack with PACK_SLOTS placeholder drawables; the app fills them on the phone."""
    src = os.path.join(HERE, "pack")
    d = os.path.join(WORK, "pack")
    shutil.rmtree(d, ignore_errors=True)
    shutil.copytree(os.path.join(src, "res"), os.path.join(d, "res"))
    nodpi = os.path.join(d, "res", "drawable-nodpi")
    os.makedirs(nodpi)
    for i in range(PACK_SLOTS):
        with open(os.path.join(nodpi, f"fi_{i:03d}.png"), "wb") as f:
            f.write(tiny_png(i))
    with open(os.path.join(nodpi, "ic.png"), "wb") as f:
        f.write(tiny_png(PACK_SLOTS))
    aapt2 = tool("aapt2")
    flat = os.path.join(d, "res.zip")
    run([aapt2, "compile", "--no-crunch", "--dir", os.path.join(d, "res"), "-o", flat])
    apk = os.path.join(d, "pack.apk")
    run([aapt2, "link", "-I", ANDROID_JAR, "--manifest", os.path.join(src, "AndroidManifest.xml"),
         "--min-sdk-version", "28", "--target-sdk-version", str(TARGET_SDK),
         "--version-code", "1", "--version-name", "1.0", "-o", apk, flat])
    cls = os.path.join(d, "classes")
    run(["javac", "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn", "-bootclasspath", ANDROID_JAR,
         "-d", cls] + sources(os.path.join(src, "java")))
    jar = os.path.join(d, "classes.jar")
    jar_dir(cls, jar)
    run([tool("d8"), "--release", "--min-api", "28", "--lib", ANDROID_JAR, "--output", d, jar])
    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(d, "classes.dex"), "classes.dex")
    with zipfile.ZipFile(apk) as z:
        slots = sum(1 for n in z.namelist() if re.search(r"/fi_\d{3}\.png$", n))
    if slots != PACK_SLOTS:
        raise SystemExit(f"pack template: {slots} slots in the APK, expected {PACK_SLOTS}")
    dst = os.path.join(SRC_DIR, "assets", "pack_template.apk")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(apk, dst)
    print(f"pack template: {PACK_SLOTS} slots, {os.path.getsize(dst) // 1024} KB")


def main():
    check_config()
    ensure_base()
    prepare_src()
    patch_apktool_yml()
    write_manifest()
    add_resources()
    build_pack_template()

    unsigned = os.path.join(WORK, "unsigned.apk")
    run(["java", "-jar", APKTOOL, "b", "-f", "-o", unsigned, SRC_DIR])

    gen = os.path.join(WORK, "gen")
    shutil.rmtree(gen, ignore_errors=True)
    write_r_java(dump_resources(unsigned), gen)

    dex = build_dex(gen)
    link_check(dex)
    inject_dex(unsigned, dex)

    aligned = os.path.join(WORK, "aligned.apk")
    run([tool("zipalign"), "-f", "-p", "4", unsigned, aligned])
    ks, pw_file = ensure_key()
    # no --key-pass: the key shares the keystore password, and a second file: spec of the same
    # file would make apksigner read its second line
    run([tool("apksigner"), "sign", "--ks", ks, "--ks-key-alias", "fi",
         "--ks-pass", "file:" + pw_file, "--out", OUT_APK, aligned])
    os.remove(unsigned)
    os.remove(aligned)
    print("OK ->", OUT_APK, f"{os.path.getsize(OUT_APK) / 1e6:.1f} MB")

    if "--install" in sys.argv:
        run(adb("push", OUT_APK, "/data/local/tmp/fi.apk"))
        run(adb("shell", "pm install -r /data/local/tmp/fi.apk"))


if __name__ == "__main__":
    main()
