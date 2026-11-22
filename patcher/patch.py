#!/usr/bin/env python3
"""
Inject the bridge-hook loader into a Phoenix Android APK.

Requires: apktool on PATH, a compiled loader.dex, and a signing tool
(apksigner preferred, jarsigner fallback for v1-only).

Usage:
  python3 patch.py \
      --apk /path/to/input.apk \
      --loader-dex /path/to/loader.dex \
      --out /path/to/patched-unsigned.apk \
      --work /tmp/release-patch
"""

import argparse
import os
import shutil
import subprocess
import sys

GUEST_HOST = os.path.join(
    "smali", "com", "discord", "nativeapp", "bridge", "GuestHost.smali"
)
NATIVE_APP = os.path.join("smali", "com", "discord", "nativeapp", "NativeApp.smali")

PATCHES = {
    "applyOps([BI)V": (
        "    invoke-static {p1, p2}, Lcom/discord/loader/Loader;->onOps([BI)[B\n"
        "    move-result-object p1\n",
        GUEST_HOST,
    ),
    "invoke([B)[B": (
        "    invoke-static {p1}, Lcom/discord/loader/Loader;->onInvoke([B)[B\n"
        "    move-result-object p1\n",
        GUEST_HOST,
    ),
    "log(ILjava/lang/String;)V": (
        "    invoke-static {p1, p2}, Lcom/discord/loader/Loader;->onLog(ILjava/lang/String;)V\n",
        GUEST_HOST,
    ),
}

SUPER_ANCHOR = "invoke-super {p0}, Landroid/app/Application;->onCreate()V"
INIT_CALL = "    invoke-static {p0}, Lcom/discord/loader/Loader;->init(Landroid/content/Context;)V"


def run(cmd, **kw):
    print("+", " ".join(cmd), flush=True)
    subprocess.check_call(cmd, **kw)


def method_bounds(text, signature):
    start = text.find(".method public " + signature)
    if start < 0:
        raise RuntimeError("method not found: " + signature)
    end = text.find(".end method", start)
    if end < 0:
        raise RuntimeError("no .end method for: " + signature)
    return start, end


def insert_after_locals(text, signature, snippet):
    start, end = method_bounds(text, signature)
    block = text[start:end]
    lines = block.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.strip().startswith(".locals"):
            indent = line[: len(line) - len(line.lstrip())]
            lines.insert(
                i + 1, snippet.replace("\n", "\n" + indent).rstrip("\n") + "\n"
            )
            return text[:start] + "".join(lines) + text[end:]
    raise RuntimeError("no .locals in " + signature)


def patch_file(path, edits):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    before = text
    for signature, snippet in edits:
        text = insert_after_locals(text, signature, snippet)
    if text == before:
        raise RuntimeError("nothing changed in " + path)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("patched", path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--loader-dex", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", default="/tmp/release-patch")
    ap.add_argument("--apktool", default=shutil.which("apktool") or "apktool")
    ap.add_argument(
        "--aapt2",
        default=None,
        help="path to a native aapt2 (apktool 3.x bundles x86-64 only; needed on arm64 hosts)",
    )
    args = ap.parse_args()

    if not os.path.isfile(args.loader_dex):
        sys.exit("loader dex not found: " + args.loader_dex)

    decoded = os.path.join(args.work, "decoded")
    if os.path.isdir(decoded):
        shutil.rmtree(decoded)
    os.makedirs(args.work, exist_ok=True)

    run([args.apktool, "d", "-f", "-q", "-o", decoded, args.apk])

    guest_path = os.path.join(decoded, GUEST_HOST)
    app_path = os.path.join(decoded, NATIVE_APP)
    for p in (guest_path, app_path):
        if not os.path.isfile(p):
            sys.exit("expected smali file missing (ABI/build changed?): " + p)

    patch_file(guest_path, [(sig, snip) for sig, (snip, _) in PATCHES.items()])

    with open(app_path, "r", encoding="utf-8") as f:
        app = f.read()
    if app.count(SUPER_ANCHOR) != 1:
        sys.exit(
            "expected exactly one super.onCreate anchor, found %d"
            % app.count(SUPER_ANCHOR)
        )
    app = app.replace(SUPER_ANCHOR, SUPER_ANCHOR + "\n" + INIT_CALL, 1)
    with open(app_path, "w", encoding="utf-8") as f:
        f.write(app)
    print("patched", app_path)

    shutil.copyfile(args.loader_dex, os.path.join(decoded, "classes2.dex"))
    print("added classes2.dex")

    if os.path.exists(args.out):
        os.remove(args.out)
    build_cmd = [args.apktool, "b", "-q"]
    if args.aapt2:
        build_cmd += ["--aapt", args.aapt2]
    build_cmd += ["-o", args.out, decoded]
    run(build_cmd)
    print("built unsigned APK:", args.out)
    print("NEXT: sign the APK, then adb install -r <signed.apk>")


if __name__ == "__main__":
    main()
