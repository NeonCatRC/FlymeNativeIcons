# SPDX-FileCopyrightText: 2026 NeonCatRC
# SPDX-License-Identifier: AGPL-3.0-only
"""Developer helpers for a connected phone.

Uses the same settings as build.py (build.local.json / FI_* variables): adb, device, build_tools
and the work folder. Nothing machine-specific lives here. Output goes to <work>/dev.

  python -I tools/devtool.py shot <name>      screenshot -> <work>/dev/<name>.png
  python -I tools/devtool.py dump             "now / day / night" previews of every unstyled or
                                              styled app, plus contact sheets (sheets need Pillow)
  python -I tools/devtool.py pack [--night]   build the icon pack on the phone, pull it and check
                                              the signature (apksigner) and the alignment
  python -I tools/devtool.py logs             the app's log lines (tag FlymeIcons)
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# build.py is the single source of the configuration
_spec = importlib.util.spec_from_file_location("fi_build", os.path.join(ROOT, "build.py"))
B = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(B)

PKG = B.PKG
OUT = os.path.join(B.WORK, "dev")
REMOTE_FILES = f"/sdcard/Android/data/{PKG}/files"
TAG = "FlymeIcons"


def adb(*args, check=False):
    r = subprocess.run(B.adb(*args), capture_output=True)
    if check and r.returncode:
        raise SystemExit(r.stderr.decode("utf-8", "replace").strip() or f"adb {' '.join(args)} failed")
    return r


def sh(cmd):
    r = adb("shell", cmd)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def start(extras=""):
    # DevActivity requires android.permission.DUMP: the adb shell has it, other apps do not
    sh(f"am start -S -n {PKG}/.DevActivity {extras}")


def wait_for(predicate, timeout=120, step=2):
    end = time.time() + timeout
    while time.time() < end:
        if predicate():
            return True
        time.sleep(step)
    return False


def cmd_shot(name):
    os.makedirs(OUT, exist_ok=True)
    data = adb("exec-out", "screencap", "-p", check=True).stdout
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        f.write(data)
    print(path)


def cmd_dump():
    remote = REMOTE_FILES + "/dump"
    sh(f"rm -rf {remote}")
    start("--ez fi_dump true")
    if not wait_for(lambda: "index.tsv" in sh(f"ls {remote}")):
        raise SystemExit("no dump after 2 minutes: is storage access granted and the app installed?")
    local = os.path.join(OUT, "dump")
    shutil.rmtree(local, ignore_errors=True)
    os.makedirs(local)
    adb("pull", remote + "/.", local, check=True)
    print("previews:", local)
    try:
        sheets = contact_sheets(local)
    except ImportError:
        print("install Pillow for contact sheets (pip install pillow)")
        return
    for s in sheets:
        print("sheet:", s)


def contact_sheets(folder, per_sheet=24, cols=4, cell=110):
    from PIL import Image, ImageDraw, ImageFont

    with open(os.path.join(folder, "index.tsv"), encoding="utf-8") as f:
        rows = [l.split("\t") for l in f.read().splitlines() if l]
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 16)
    except OSError:
        try:
            font = ImageFont.truetype("segoeui.ttf", 16)
        except OSError:
            font = ImageFont.load_default()
    out = []
    backs = [(128, 128, 128), (245, 245, 245), (11, 11, 12)]
    for s in range(0, len(rows), per_sheet):
        chunk = rows[s:s + per_sheet]
        lines = (len(chunk) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * (3 * cell + 30), lines * (cell + 60)), (60, 60, 64))
        d = ImageDraw.Draw(sheet)
        for i, r in enumerate(chunk):
            x0 = (i % cols) * (3 * cell + 30) + 10
            y0 = (i // cols) * (cell + 60) + 6
            for k, suffix in enumerate(("_0now", "_1day", "_2night")):
                d.rectangle((x0 + k * cell, y0, x0 + k * cell + cell - 4, y0 + cell - 4), fill=backs[k])
                p = os.path.join(folder, r[0] + suffix + ".png")
                if os.path.exists(p):
                    icon = Image.open(p).convert("RGBA").resize((cell - 24, cell - 24), Image.LANCZOS)
                    sheet.paste(icon, (x0 + k * cell + 10, y0 + 10), icon)
            d.text((x0, y0 + cell), (r[1] if len(r) > 1 else r[0])[:22], fill=(255, 255, 255), font=font)
            if len(r) > 2:
                d.text((x0, y0 + cell + 20), " ".join(r[2:5])[:46], fill=(200, 200, 200), font=font)
        path = os.path.join(OUT, f"sheet{s // per_sheet}.png")
        sheet.save(path)
        out.append(path)
    return out


def cmd_pack(night):
    adb("logcat", "-c")
    sh(f"rm -f {REMOTE_FILES}/pack.apk")
    start("--ez fi_pack_dump true" + (" --ez night true" if night else ""))
    line = []

    def done():
        log = adb("logcat", "-d", "-s", TAG + ":*").stdout.decode("utf-8", "replace")
        line[:] = [l for l in log.splitlines() if "pack dump" in l]
        return bool(line)

    if not wait_for(done):
        raise SystemExit("the app did not report the pack build")
    print(line[-1].split(TAG + ":", 1)[-1].strip())
    if "failed" in line[-1]:
        raise SystemExit(1)
    os.makedirs(OUT, exist_ok=True)
    apk = os.path.join(OUT, "pack.apk")
    adb("pull", REMOTE_FILES + "/pack.apk", apk, check=True)
    signer = os.path.join(B.BT, "lib", "apksigner.jar")
    v = subprocess.run(["java", "-jar", signer, "verify", "-v", apk], capture_output=True, text=True)
    print("\n".join(l for l in v.stdout.splitlines() if l.startswith(("Verifies", "Verified using v2"))) or v.stderr)
    a = subprocess.run([B.tool("zipalign"), "-c", "4", apk], capture_output=True)
    print("alignment:", "OK" if a.returncode == 0 else "FAILED")
    print(apk)


def cmd_logs():
    print(adb("logcat", "-d", "-s", TAG + ":*").stdout.decode("utf-8", "replace"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    c = sys.argv[1]
    if c == "shot" and len(sys.argv) > 2:
        cmd_shot(sys.argv[2])
    elif c == "dump":
        cmd_dump()
    elif c == "pack":
        cmd_pack("--night" in sys.argv)
    elif c == "logs":
        cmd_logs()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
