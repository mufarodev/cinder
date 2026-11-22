#!/usr/bin/env python3
"""
cinder-mobile installer — the APK equivalent of the original installer:
works on a COPY, never touches the vanilla build; plants the runtime dylib,
plugins, config path, patch manifest, and (optionally) the Java bridge-loader
companion.

  analyze : report native libs, ELF Build ID, and auto-resolved patch sites
  install : patch smali (fast --no-res path without aapt2 unless renaming pkg)
            -> splice via zip surgery (16 KB .so alignment) -> sign
  repatch : skip smali rebuild; reuse cached dex/libs -> zip surgery -> sign

Usage:
  python3 install.py install \\
      --apk /path/to/vanilla.apk \\
      --runtime-so /path/to/target/aarch64-linux-android/release/libcinder_mobile.so \\
      --plugins /path/to/target/.../release   (dir with libcinder_*.so) \\
      --out /path/to/Discord-Cinder.apk \\
      --keystore modloader.jks --ks-pass changeit
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import elf_finder

LIBDIR = os.path.join("lib", "arm64-v8a")
HOST_SO_ENTRY = "lib/arm64-v8a/liblibdiscord_wasm_host.so"
PATCH_MANIFEST_ENTRY = "assets/cinder-patches.json"
APP_SMALI = os.path.join("smali", "com", "discord", "nativeapp", "NativeApp.smali")
HOST_SMALI = os.path.join(
    "smali", "com", "discord", "nativeapp", "bridge", "GuestHost.smali"
)
SUPER_ANCHOR = "invoke-super {p0}, Landroid/app/Application;->onCreate()V"
BRIDGE_INTERFACE = ".implements Lcom/discord/nativeapp/bridge/WasmRuntime$Bridge;"
HOST_INVOKER_INTERFACE = ".implements Lcom/discord/loader/HostInvoker;"


SO_ALIGN = 16384
STORED_ALIGN = 4


_SIG_EXTS = (".sf", ".rsa", ".dsa", ".ec")


def is_signature_meta_inf(name: str) -> bool:
    if not name.startswith("META-INF/"):
        return False
    rest = name[len("META-INF/") :]
    if "/" in rest:
        return False
    low = rest.lower()
    return low == "manifest.mf" or low.endswith(_SIG_EXTS)


def strip_zip_padding(extra: bytes) -> bytes:
    """Parse ZIP extra-field TLV records and keep all real records while
    dropping alignment padding (trailing NULs and Android 0xD935 records).
    Unlike `extra.rstrip(b'\\x00')`, this never truncates a real TLV payload
    whose final bytes happen to be zero."""
    out = bytearray()
    pos = 0
    n = len(extra)
    while pos + 4 <= n:
        hdr_id, dlen = struct.unpack_from("<HH", extra, pos)
        end = pos + 4 + dlen
        if hdr_id == 0 and dlen == 0:

            pos += 4
            continue
        if end > n:

            break
        if hdr_id != 0xD935:
            out.extend(extra[pos:end])
        pos = end
    return bytes(out)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def run(cmd, **kw):
    print("+", " ".join(cmd), flush=True)
    subprocess.check_call(cmd, **kw)


def analyze(apk, apktool):
    print("== cinder-mobile analyze ==")
    with zipfile.ZipFile(apk) as zf:
        for info in sorted(zf.infolist(), key=lambda i: i.filename):
            if info.filename.startswith("lib/arm64-v8a/") and info.filename.endswith(
                ".so"
            ):
                name = os.path.basename(info.filename)
                print(f"  {name:44s} {info.file_size / (1 << 20):7.2f} MiB")
        names = set(zf.namelist())
        for probe in ("libcinder_mobile.so", "libhello_cinder_mobile.so"):
            present = f"lib/arm64-v8a/{probe}" in names
            print(f"  {probe}: {'PRESENT' if present else 'absent'}")

        if PATCH_MANIFEST_ENTRY in names:
            try:
                manifest = json.loads(zf.read(PATCH_MANIFEST_ENTRY).decode("utf-8"))
                print(
                    f"  {PATCH_MANIFEST_ENTRY}: PRESENT "
                    f"(build_id={manifest.get('build_id', '?')}, "
                    f"{len(manifest.get('patches', []))} patch(es))"
                )
            except Exception as exc:
                print(f"  {PATCH_MANIFEST_ENTRY}: PRESENT (parse error: {exc})")
        else:
            print(f"  {PATCH_MANIFEST_ENTRY}: absent")

        if HOST_SO_ENTRY not in names:
            print(f"  WARNING: {HOST_SO_ENTRY} not found in APK")
            return

        so_bytes = zf.read(HOST_SO_ENTRY)

    print("\n== host ELF analysis ==")
    elf = elf_finder.ElfImage(so_bytes)
    print(f"  ELF Build ID : {elf.build_id or 'MISSING'}")
    if elf.eh_frame is not None:
        print(
            f"  .eh_frame_hdr: {elf.eh_frame.fde_count} FDEs "
            f"(vaddr={elf.eh_frame.hdr_vaddr:#x}, table_enc={elf.eh_frame.table_enc:#x})"
        )
    else:
        print("  .eh_frame_hdr: MISSING")

    results = elf_finder.find_all_active_patches(elf, allow_already_patched=True)
    all_ok = True
    for name, (res, err) in results.items():
        if err is not None:
            all_ok = False
            print(f"  [FAIL] {name}: {err}")
            continue
        print(f"  [OK]   {name}: {res.detail}")
        for edit in res.edits:
            cur = so_bytes[edit.file_offset : edit.file_offset + len(edit.orig)]
            print(
                f"           {edit.tag} @ file {edit.file_offset:#x} (va {edit.vaddr:#x}): "
                f"cur={cur.hex()} -> patched={edit.patched.hex()} [{edit.status}]"
            )
    if not all_ok:
        sys.exit(1)


def config_smali(brand=None, verify_only=True):
    lines = [
        "log = info",
        "plugins = true",
        "verify_only = " + ("true" if verify_only else "false"),
    ]
    if brand:
        lines.append("version_prefix = " + brand)
    payload = "\\n".join(lines) + "\\n"
    return CONFIG_SMALI_TEMPLATE.replace("__CONFIG_PAYLOAD__", payload)


CONFIG_SMALI_TEMPLATE = """
    :cinder_try_start
    invoke-virtual {p0}, Landroid/app/Application;->getFilesDir()Ljava/io/File;
    move-result-object v1
    new-instance v2, Ljava/io/File;
    const-string v3, "cinder"
    invoke-direct {v2, v1, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    invoke-virtual {v2}, Ljava/io/File;->mkdirs()Z
    new-instance v3, Ljava/io/File;
    const-string v4, "cinder.toml"
    invoke-direct {v3, v2, v4}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    const-string v6, "__CONFIG_PAYLOAD__"
    new-instance v1, Ljava/io/FileOutputStream;
    invoke-direct {v1, v3}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V
    invoke-virtual {v6}, Ljava/lang/String;->getBytes()[B
    move-result-object v2
    invoke-virtual {v1, v2}, Ljava/io/FileOutputStream;->write([B)V
    invoke-virtual {v1}, Ljava/io/FileOutputStream;->close()V
    :cinder_after
    :cinder_try_end
    .catch Ljava/lang/Exception; {:cinder_try_start .. :cinder_try_end} :cinder_catch
    goto :cinder_done
    :cinder_catch
    move-exception v1
    :cinder_done
"""


def sanitize_resource_names(decoded):
    """apktool's aapt2 rejects resource names containing '$' (this build has
    four drawables named '$guild_profile_empty_state__N'). Rename the files
    and repoint references; resource IDs in public.xml stay unchanged, so
    compiled code referencing IDs is unaffected."""
    renamed = []
    res = os.path.join(decoded, "res")
    if not os.path.isdir(res):
        return
    for root, _, files in os.walk(res):
        for f in files:
            if "$" not in f:
                continue
            src = os.path.join(root, f)
            dst = os.path.join(root, f.replace("$", "_"))
            os.rename(src, dst)
            old_base = f[:-4] if f.endswith(".xml") else f
            new_base = old_base.replace("$", "_")
            renamed.append((old_base, new_base))
    if not renamed:
        return
    for root, _, files in os.walk(res):
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(root, f)
            with open(p, encoding="utf-8", errors="surrogateescape") as fh:
                text = fh.read()
            orig = text
            for old_base, new_base in renamed:
                text = text.replace(old_base, new_base)
            if text != orig:
                with open(p, "w", encoding="utf-8", errors="surrogateescape") as fh:
                    fh.write(text)
    print(f"sanitized {len(renamed)} resource names containing '$'")


def _replace_exact_buf(
    buf: bytearray, old: bytes, new: bytes, expect: int = 1, label: str = ""
):
    if len(old) != len(new):
        raise RuntimeError(f"{label}length mismatch: {len(old)} != {len(new)}")
    n = buf.count(old)
    if (expect is None and n == 0) or (expect is not None and n != expect):
        raise RuntimeError(
            f"{label}literal {old!r} found {n} times (expected {expect or '>0'})"
        )
    pos = 0
    replaced = 0
    while True:
        idx = buf.find(old, pos)
        if idx < 0:
            break
        buf[idx : idx + len(new)] = new
        pos = idx + len(new)
        replaced += 1
    return replaced


def _patch_at_buf(buf: bytearray, off: int, old: bytes, new: bytes, name: str):
    if len(old) != len(new):
        raise RuntimeError(f"{name}: length mismatch {len(old)} != {len(new)}")
    if off < 0 or off + len(old) > len(buf):
        raise RuntimeError(f"{name}: offset {off:#x} out of range (size {len(buf):#x})")
    cur = bytes(buf[off : off + len(old)])
    if cur != old:
        raise RuntimeError(
            f"{name} guard failed at {off:#x}: {cur.hex()} != {old.hex()}"
        )
    buf[off : off + len(new)] = new


def patch_host_literal_buf(buf: bytearray, old: bytes, new_prefix: bytes) -> bytes:
    """Byte-equal length rewrite of a literal inside the host .so buffer."""
    if not new_prefix or len(new_prefix) >= len(old):
        raise RuntimeError(f"brand must be shorter than the {len(old)}-byte literal")
    repl = bytearray(new_prefix)
    while len(repl) < len(old):
        repl += b".0" if len(repl) + 2 <= len(old) else b"0"
    repl_b = bytes(repl[: len(old)])
    _replace_exact_buf(buf, old, repl_b, expect=1, label="brand: ")
    print(f"static-patched host literal: {old!r} -> {repl_b!r}")
    return repl_b


def patch_experiment_buf(buf: bytearray, elf: elf_finder.ElfImage = None) -> list:
    """Auto-resolve and force experiment `2026-11-release-app-access` to variant 1."""
    if elf is None:
        elf = elf_finder.ElfImage(buf)
    res = elf_finder.find_release_access(elf, allow_already_patched=False)
    for edit in res.edits:
        _patch_at_buf(buf, edit.file_offset, edit.orig, edit.patched, edit.tag)
        print(
            f"static-patched {edit.tag} @ {edit.file_offset:#x}: "
            f"{edit.orig.hex()} -> {edit.patched.hex()}"
        )
    return list(res.edits)


def patch_telemetry_buf(buf: bytearray):
    """Dead-end analytics/error upload endpoints using equal-length swaps.
    The errmon host replacement includes `:` (dead-name design) so
    URL parsing rejects it locally with zero DNS egress."""
    errmon_old = b"release-errmon.disabled.tools"
    errmon_new = b"release-errmon::c1nd3r.void.x"
    assert len(errmon_old) == len(errmon_new)
    edits = [
        (b"/science", b"/scjence", None),
        (errmon_old, errmon_new, 1),
    ]
    for old, new, expect in edits:
        n = _replace_exact_buf(buf, old, new, expect=expect, label="telemetry: ")
        print(f"static-patched telemetry: {old!r} -> {new!r} ({n} sites)")


def patch_staff_hijack_buf(buf: bytearray, which: int):
    """[Experimental, build rel3567ab only; superseded by --staff-devtools]
    Redirect one settings section enum in the 16-bit dispatch table at
    0x39EBFA to the staff case (enum 0 -> 0x39FB150, off 0x193). Guarded by
    verifying table entry 0 == STAFF_OFF and bounds-checking `which`."""
    TB = 0x39EBFA
    ANCHOR = 0x39FAB04
    STAFF_OFF = (0x39FB150 - ANCHOR) >> 2
    MAX_SECTION = 0x80
    if which <= 0 or which >= MAX_SECTION:
        raise RuntimeError(
            f"staff-hijack section {which:#x} out of expected range (1..{MAX_SECTION - 1:#x})"
        )
    if TB + 2 * (which + 1) > len(buf):
        raise RuntimeError("staff-hijack table offset out of file bounds")
    entry0 = struct.unpack_from("<H", buf, TB)[0]
    if entry0 != STAFF_OFF:
        raise RuntimeError(
            f"staff-hijack table guard failed at {TB:#x}: entry[0]={entry0:#x} != {STAFF_OFF:#x}"
        )
    off = TB + 2 * which
    cur = struct.unpack_from("<H", buf, off)[0]
    if cur == STAFF_OFF:
        print(f"staff-hijack: section {which:#x} already points to staff case")
        return
    struct.pack_into("<H", buf, off, STAFF_OFF)
    print(
        f"static-patched staff hijack: section {which:#x} "
        f"(was case {ANCHOR + (cur << 2):#x}) -> staff case 0x39fb150"
    )


def patch_staff_devtools_buf(
    buf: bytearray, elf: elf_finder.ElfImage = None, runtime_flags_out: dict = None
) -> list:
    """Auto-resolve and patch the staff/devtools predicate with a 12-byte PIC
    ARM64 `.bss` flag reader (`adrp x8, page; ldrb w0, [x8, #off]; ret`) so
    `StaffDevToolsPlugin` can toggle Build Status, Staff Settings, Show Dev Tools,
    Experiment Overrides, Perf Monitor, and Playgrounds live at runtime via JNI."""
    if elf is None:
        elf = elf_finder.ElfImage(buf)
    res = elf_finder.find_staff_devtools(elf, allow_already_patched=False)
    for edit in res.edits:
        _patch_at_buf(buf, edit.file_offset, edit.orig, edit.patched, edit.tag)
        print(
            f"static-patched {edit.tag} @ {edit.file_offset:#x}: "
            f"{edit.orig.hex()} -> {edit.patched.hex()} ({res.detail})"
        )
    if runtime_flags_out is not None:
        for flag_name, flag_vaddr, default_val in res.runtime_flags:
            runtime_flags_out[flag_name] = {
                "vaddr": flag_vaddr,
                "default": default_val,
            }
    return list(res.edits)


def build_patch_manifest(
    build_id: str, edits: list, runtime_flags: dict = None
) -> dict:
    """Build the JSON manifest embedded at `assets/cinder-patches.json` for
    runtime Build-ID + byte verification and dynamic `.bss` runtime flag discovery."""
    manifest = {
        "schema": 1,
        "build_id": build_id or "",
        "patches": [e.to_manifest_entry() for e in edits],
    }
    if runtime_flags:
        manifest["runtime_flags"] = runtime_flags
    return manifest


def apply_host_so_patches(original: bytes, args) -> tuple:
    """Apply all requested equal-length static patches to the host .so in a
    single in-memory bytearray pass (shared by both `install` and `repatch`).
    Returns `(patched_so_bytes, manifest_dict)`."""
    buf = bytearray(original)
    applied_edits = []
    runtime_flags = {}

    want_staff_devtools = bool(getattr(args, "staff_devtools", False))
    if getattr(args, "devtools", False):
        print(
            "WARNING: --devtools is deprecated (0x15A3374 was audited as an unrelated "
            "media/DSP vtable method, not is_developer); applying --staff-devtools instead"
        )
        want_staff_devtools = True
    want_release_access = bool(getattr(args, "release_access", False))

    elf = None
    build_id = ""
    if len(original) >= 64 and original[:4] == elf_finder.ELF_MAGIC:
        elf = elf_finder.ElfImage(original)
        build_id = elf.build_id or ""
        if build_id:
            print(f"host ELF Build ID: {build_id}")

    if elf is not None and want_staff_devtools and want_release_access:
        elf.scan_string_xrefs(elf_finder.ALL_FINDER_ANCHORS)

    if args.brand:
        patch_host_literal_buf(buf, b"356.7.0.0.0.0.0.0.0.00", args.brand.encode())
    if want_release_access:
        applied_edits.extend(patch_experiment_buf(buf, elf=elf))
    if getattr(args, "no_telemetry", False):
        patch_telemetry_buf(buf)
    hijack = int(getattr(args, "staff_hijack", "0"), 0)
    if hijack:
        patch_staff_hijack_buf(buf, hijack)
    if want_staff_devtools:
        applied_edits.extend(
            patch_staff_devtools_buf(buf, elf=elf, runtime_flags_out=runtime_flags)
        )
    if getattr(args, "cinder_label", False):
        old_lit = "Native runtime · build ".encode("utf-8")
        new_lit = "Cinder runtime · build ".encode("utf-8")
        assert len(old_lit) == len(new_lit) == 24
        _replace_exact_buf(buf, old_lit, new_lit, expect=1, label="cinder-label: ")
        print(f"static-patched literal: {old_lit!r} -> {new_lit!r}")
    if len(buf) != len(original):
        raise RuntimeError(f"host .so changed length: {len(original)} -> {len(buf)}")
    manifest = build_patch_manifest(
        build_id, applied_edits, runtime_flags=runtime_flags
    )
    return bytes(buf), manifest


def insert_after(text, anchor, snippet, count=1):
    if text.count(anchor) != count:
        raise RuntimeError(
            f"anchor count {text.count(anchor)} != {count}: {anchor[:60]}"
        )
    return text.replace(anchor, anchor + "\n" + snippet, count)


def patch_returns(text, signature, call_target):
    """Insert `invoke-static {<reg>}, <call_target>; move-result-object <reg>`
    before every `return-object <reg>` in a method, parsing `<reg>` dynamically
    from each return instruction instead of hardcoding a register."""
    start = text.find(".method public final " + signature)
    if start < 0:
        start = text.find(".method public " + signature)
    if start < 0:
        raise RuntimeError("method not found: " + signature)
    end = text.find(".end method", start)
    block = text[start:end]
    lines = block.splitlines(keepends=True)
    out = []
    n = 0
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("return-object "):
            parts = stripped.split()
            if len(parts) != 2:
                raise RuntimeError(
                    f"unexpected return-object syntax in {signature}: {stripped!r}"
                )
            reg = parts[1]
            indent = line[: len(line) - len(line.lstrip())]
            out.append(f"{indent}invoke-static {{{reg}}}, {call_target}\n")
            out.append(f"{indent}move-result-object {reg}\n")
            n += 1
        out.append(line)
    if n == 0:
        raise RuntimeError("no return-object in " + signature)
    print(f"patched {n} return(s) in {signature}")
    return text[:start] + "".join(out) + text[end:]


def insert_after_locals(text, signature, snippet, min_locals=0):
    start = text.find(".method public final " + signature)
    if start < 0:
        start = text.find(".method public " + signature)
    if start < 0:
        raise RuntimeError("method not found: " + signature)
    end = text.find(".end method", start)
    block = text[start:end]
    lines = block.splitlines(keepends=True)
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(".locals "):
            if min_locals > 0:
                cur = int(stripped.split()[1])
                if cur < min_locals:
                    indent = line[: len(line) - len(line.lstrip())]
                    lines[i] = f"{indent}.locals {min_locals}\n"
            lines.insert(i + 1, snippet.rstrip("\n") + "\n")
            return text[:start] + "".join(lines) + text[end:]
    raise RuntimeError("no .locals in " + signature)


def wrap_guest_host_invoke(text: str) -> str:
    """Wrap public `GuestHost.invoke([B)[B` cleanly without hooking any
    R8-obfuscated method names:
    - Declares `.implements Lcom/discord/loader/HostInvoker;` on `GuestHost`.
    - Renames original `invoke([B)[B` to `invokeOrig([B)[B`.
    - Emits a thin `invoke([B)[B` delegating to `Loader.dispatchInvoke(this, frame)`,
      enabling request/response correlation, chaining, and short-circuiting."""
    if HOST_INVOKER_INTERFACE in text:
        raise RuntimeError("GuestHost already implements HostInvoker")
    text = insert_after(text, BRIDGE_INTERFACE, HOST_INVOKER_INTERFACE, count=1)

    pattern = re.compile(
        r"^\.method public (?:final )?invoke\(\[B\)\[B[ \t]*$", re.MULTILINE
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise RuntimeError(
            f"expected 1 invoke([B)[B method in GuestHost, found {len(matches)}"
        )
    m = matches[0]
    wrapper = (
        ".method public invoke([B)[B\n"
        "    .locals 1\n"
        "\n"
        "    invoke-static {p0, p1}, Lcom/discord/loader/Loader;->dispatchInvoke(Lcom/discord/loader/HostInvoker;[B)[B\n"
        "    move-result-object v0\n"
        "    return-object v0\n"
        ".end method\n"
        "\n"
        ".method public invokeOrig([B)[B"
    )
    print("wrapped GuestHost.invoke([B)[B -> Loader.dispatchInvoke + invokeOrig")
    return text[: m.start()] + wrapper + text[m.end() :]


NATIVE_DISPATCH_TARGET = (
    "Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z"
)
PROP_NAMES_MARKER = "` is not in prop_names.txt; dropped"


def patch_guest_host_apply_ops(text: str) -> str:
    """Hook `GuestHost.applyOps([BI)V` so plugins can mutate UI ops in place
    or return a resized replacement `byte[]` (updating length parameter `p2`
    only when a replacement array is returned), while also capturing the
    active `HostInvoker` handle (`p0`)."""
    snippet = (
        "    invoke-static {p0, p1, p2}, Lcom/discord/loader/Loader;->onOps(Lcom/discord/loader/HostInvoker;[BI)[B\n"
        "    move-result-object v0\n"
        "    if-eq v0, p1, :loader_ops_same\n"
        "    move-object p1, v0\n"
        "    array-length p2, p1\n"
        "    :loader_ops_same"
    )
    return insert_after_locals(text, "applyOps([BI)V", snippet, min_locals=1)


def _iter_smali_files(decoded: str):
    """Yield absolute paths to all `.smali` files across `smali*` trees."""
    for entry in sorted(os.listdir(decoded)):
        if entry == "smali" or entry.startswith("smali_"):
            root_dir = os.path.join(decoded, entry)
            if not os.path.isdir(root_dir):
                continue
            for dirpath, dirnames, filenames in os.walk(root_dir):
                dirnames.sort()
                for fn in sorted(filenames):
                    if fn.endswith(".smali"):
                        yield os.path.join(dirpath, fn)


def patch_native_dispatch_smali(text: str) -> tuple:
    """Intercept every `WasmRuntime.nativeDispatch(J[B)Z` invocation in a smali
    file by passing the `byte[]` buffer through `Loader.onDispatch([B)[B` first
    and skipping `nativeDispatch` whenever `null` is returned.

    Supports both `invoke-static {lo, hi, buf}` and `invoke-static/range {lo .. buf}`,
    as well as optional trailing `move-result vR` instructions.
    Returns `(patched_text, count)`.
    """
    if "Lcom/discord/loader/Loader;->onDispatch([B)[B" in text:
        raise RuntimeError("smali already patched for Loader.onDispatch")

    invoke_re = re.compile(
        r"^(?P<indent>[ \t]*)invoke-static(?P<range>/range)?\s+\{(?P<regs>[^}]+)\},\s*"
        + re.escape(NATIVE_DISPATCH_TARGET)
        + r"[ \t]*$"
    )
    move_res_re = re.compile(r"^[ \t]*move-result\s+([vp]\d+)[ \t]*$")

    lines = text.splitlines(keepends=True)
    out = []
    count = 0
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        m = invoke_re.match(line.rstrip("\r\n"))
        if not m:
            out.append(line)
            i += 1
            continue

        indent = m.group("indent")
        is_range = bool(m.group("range"))
        regs = m.group("regs").strip()
        if is_range:
            parts = [p.strip() for p in regs.split("..")]
            if len(parts) != 2:
                raise RuntimeError(f"unexpected invoke-static/range regs: {regs!r}")
            buf_reg = parts[1]
            call_on_dispatch = (
                f"{indent}invoke-static/range {{{buf_reg} .. {buf_reg}}}, "
                f"Lcom/discord/loader/Loader;->onDispatch([B)[B\n"
            )
        else:
            parts = [p.strip() for p in regs.split(",")]
            if len(parts) != 3:
                raise RuntimeError(
                    f"unexpected invoke-static regs for nativeDispatch: {regs!r}"
                )
            buf_reg = parts[2]
            call_on_dispatch = (
                f"{indent}invoke-static {{{buf_reg}}}, "
                f"Lcom/discord/loader/Loader;->onDispatch([B)[B\n"
            )

        j = i + 1
        while j < n and (
            lines[j].strip() == "" or lines[j].strip().startswith(".line")
        ):
            j += 1
        mr = move_res_re.match(lines[j].rstrip("\r\n")) if j < n else None

        idx = count
        count += 1
        if mr:
            res_reg = mr.group(1)
            out.append(call_on_dispatch)
            out.append(f"{indent}move-result-object {buf_reg}\n")
            out.append(f"{indent}if-nez {buf_reg}, :loader_run_dispatch_{idx}\n")
            out.append(f"{indent}const/4 {res_reg}, 0x1\n")
            out.append(f"{indent}goto :loader_after_dispatch_{idx}\n")
            out.append(f"{indent}:loader_run_dispatch_{idx}\n")
            out.append(line)
            for k in range(i + 1, j + 1):
                out.append(lines[k])
            out.append(f"{indent}:loader_after_dispatch_{idx}\n")
            i = j + 1
        else:
            out.append(call_on_dispatch)
            out.append(f"{indent}move-result-object {buf_reg}\n")
            out.append(f"{indent}if-eqz {buf_reg}, :loader_skip_dispatch_{idx}\n")
            out.append(line)
            out.append(f"{indent}:loader_skip_dispatch_{idx}\n")
            i += 1

    return "".join(out), count


def patch_native_dispatch_sites(decoded: str) -> int:
    """Scan all decoded smali trees for `WasmRuntime.nativeDispatch(J[B)Z` call
    sites and hook every one via `Loader.onDispatch([B)[B`. Raises if none found."""
    total = 0
    patched_files = []
    for path in _iter_smali_files(decoded):
        with open(path, encoding="utf-8") as f:
            content = f.read()
        if NATIVE_DISPATCH_TARGET not in content:
            continue
        updated, count = patch_native_dispatch_smali(content)
        if count > 0:
            with open(path, "w", encoding="utf-8") as f:
                f.write(updated)
            total += count
            patched_files.append(f"{os.path.relpath(path, decoded)}({count})")
    if total == 0:
        raise RuntimeError(
            f"expected >=1 call site for {NATIVE_DISPATCH_TARGET}, found 0"
        )
    print(
        f"patched {total} WasmRuntime.nativeDispatch call site(s): {', '.join(patched_files)}"
    )
    return total


def discover_prop_names_field(decoded: str):
    """Locate the ViewWire prop-name table static `String[]` field (e.g.
    `Lyr2;->a:[Ljava/lang/String;`) without hardcoding obfuscated class names,
    by finding the smali file containing `PROP_NAMES_MARKER` (`wy3`) and
    extracting its external static `String[]` field reference."""
    class_re = re.compile(r"^\.class\b.*?(L[^;\s]+;)\s*$", re.MULTILINE)
    sget_re = re.compile(
        r"sget-object\s+[vp]\d+,\s+(L[^;\s]+;)->([^:\s]+):\[Ljava/lang/String;"
    )
    for path in _iter_smali_files(decoded):
        with open(path, encoding="utf-8") as f:
            content = f.read()
        if PROP_NAMES_MARKER not in content:
            continue
        cm = class_re.search(content)
        self_cls = cm.group(1) if cm else None
        for sm in sget_re.finditer(content):
            cls_desc, field_name = sm.group(1), sm.group(2)
            if cls_desc != self_cls:
                return f"{cls_desc}->{field_name}:[Ljava/lang/String;"
    return None


def patch_guest_host_clinit_prop_names(text: str, field_ref: str) -> str:
    """Inject `UiOps.registerPropNames(<field_ref>)` before `return-void` in
    `GuestHost.<clinit>()`. Fail-soft if `field_ref` or `<clinit>()` is absent."""
    if not field_ref:
        print(
            "WARNING: ViewWire prop_names table not found; UiOps prop names will stay numeric"
        )
        return text
    start = text.find(".method static constructor <clinit>()V")
    if start < 0:
        print(
            "WARNING: GuestHost.<clinit>() not found; skipping UiOps.registerPropNames"
        )
        return text
    end = text.find(".end method", start)
    if end < 0:
        return text
    block = text[start:end]
    lines = block.splitlines(keepends=True)
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(".locals "):
            if int(stripped.split()[1]) < 1:
                indent = line[: len(line) - len(line.lstrip())]
                lines[i] = f"{indent}.locals 1\n"
            break
    injected = False
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip() == "return-void":
            indent = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
            lines.insert(
                i,
                f"{indent}sget-object v0, {field_ref}\n"
                f"{indent}invoke-static {{v0}}, Lcom/discord/loader/UiOps;->registerPropNames([Ljava/lang/String;)V\n",
            )
            injected = True
            break
    if not injected:
        print(
            "WARNING: no return-void in GuestHost.<clinit>(); skipping UiOps.registerPropNames"
        )
        return text
    print(f"patched GuestHost.<clinit> to register ViewWire prop names {field_ref}")
    return text[:start] + "".join(lines) + text[end:]


def patch_guest_host_clinit_registry(text: str) -> str:
    """Discover the interned symbol-table static `String[]` field referenced by
    `GuestHost.<clinit>()` (e.g. `Lr83;->a:[Ljava/lang/String;`, without
    hardcoding obfuscated names) and pass it to `BridgeRegistry.register([Ljava/lang/String;)V`.
    Fail-soft if `<clinit>()` layout changes in a future APK build."""
    start = text.find(".method static constructor <clinit>()V")
    if start < 0:
        print(
            "WARNING: GuestHost.<clinit>() not found; BridgeRegistry will use reflection fallback"
        )
        return text
    end = text.find(".end method", start)
    if end < 0:
        return text
    block = text[start:end]
    m = re.search(
        r"sget-object\s+v\d+,\s+(L[^;\s]+;->[^:\s]+:\[Ljava/lang/String;)", block
    )
    if not m:
        print(
            "WARNING: no String[] symbol table read in GuestHost.<clinit>(); using reflection fallback"
        )
        return text
    field_ref = m.group(1)

    lines = block.splitlines(keepends=True)

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(".locals "):
            if int(stripped.split()[1]) < 1:
                indent = line[: len(line) - len(line.lstrip())]
                lines[i] = f"{indent}.locals 1\n"
            break

    injected = False
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip() == "return-void":
            indent = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
            lines.insert(
                i,
                f"{indent}sget-object v0, {field_ref}\n"
                f"{indent}invoke-static {{v0}}, Lcom/discord/loader/BridgeRegistry;->register([Ljava/lang/String;)V\n",
            )
            injected = True
            break
    if not injected:
        print(
            "WARNING: no return-void in GuestHost.<clinit>(); using reflection fallback"
        )
        return text

    print(f"patched GuestHost.<clinit> to register symbol table {field_ref}")
    return text[:start] + "".join(lines) + text[end:]


def patch_smali_tree(decoded: str, args):
    """Apply NativeApp.onCreate and optional GuestHost smali hooks in `decoded`."""
    app_path = os.path.join(decoded, APP_SMALI)
    with open(app_path, encoding="utf-8") as f:
        app = f.read()

    verify_only = not getattr(args, "arm", False)
    cfg_smali = config_smali(None, verify_only=verify_only)
    app = insert_after(app, SUPER_ANCHOR, cfg_smali)
    load_snippet = (
        '    const-string v0, "cinder_mobile"\n'
        "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"
    )
    app = insert_after(app, SUPER_ANCHOR + "\n" + cfg_smali.rstrip("\n"), load_snippet)
    with open(app_path, "w", encoding="utf-8") as f:
        f.write(app)
    print(
        f'patched NativeApp.onCreate: loadLibrary("cinder_mobile") first (verify_only={verify_only})'
    )

    if args.bridge_loader_dex:
        host_path = os.path.join(decoded, HOST_SMALI)
        with open(host_path, encoding="utf-8") as f:
            host = f.read()
        host = patch_guest_host_apply_ops(host)
        host = wrap_guest_host_invoke(host)
        host = insert_after_locals(
            host,
            "log(ILjava/lang/String;)V",
            "    invoke-static {p1, p2}, Lcom/discord/loader/Loader;->onLog(ILjava/lang/String;)V",
        )
        host = patch_guest_host_clinit_registry(host)
        prop_field = discover_prop_names_field(decoded)
        host = patch_guest_host_clinit_prop_names(host, prop_field)
        with open(host_path, "w", encoding="utf-8") as f:
            f.write(host)
        patch_native_dispatch_sites(decoded)

        with open(app_path, encoding="utf-8") as f:
            app = f.read()
        bridge_anchor = (
            SUPER_ANCHOR + "\n" + cfg_smali.rstrip("\n") + "\n" + load_snippet
        )
        app = insert_after(
            app,
            bridge_anchor,
            "    invoke-static {p0}, Lcom/discord/loader/Loader;->init(Landroid/content/Context;)V",
        )
        with open(app_path, "w", encoding="utf-8") as f:
            f.write(app)
        print("patched GuestHost + NativeApp for bridge-loader companion")


def _dos_date_time(dt):
    year = max(1980, dt[0])
    date = ((year - 1980) << 9) | ((dt[1] & 0xF) << 5) | (dt[2] & 0x1F)
    time = ((dt[3] & 0x1F) << 11) | ((dt[4] & 0x3F) << 5) | ((dt[5] // 2) & 0x1F)
    return time, date


def zip_surgery(
    src_apk: str,
    dst_apk: str,
    replacements: dict,
    additions: list,
):
    """Rebuild an APK via raw ZIP surgery:
    - Strips only META-INF signature files (preserving META-INF/services/* etc.).
    - Raw-copies unmodified entries without re-compressing.
    - Splices `replacements` ({entry_name: (bytes, method, align)}) in place.
    - Appends `additions` ([(entry_name, bytes, method, align)]).
    - Aligns all stored lib/*.so to 16 KiB (SO_ALIGN) and other stored entries
      to 4 B (STORED_ALIGN) without corrupting real ZIP TLV extra fields.
    """
    zin = zipfile.ZipFile(src_apk)
    existing = set(zin.namelist())
    for add_name, _, _, _ in additions:
        if add_name in existing and add_name not in replacements:
            raise RuntimeError(
                f"APK already contains {add_name}; refusing duplicate entry"
            )

    if os.path.exists(dst_apk):
        os.remove(dst_apk)
    os.makedirs(os.path.dirname(os.path.abspath(dst_apk)), exist_ok=True)

    entries = []
    with open(src_apk, "rb") as src, open(dst_apk, "wb") as dst:

        def put(
            name_b,
            data,
            method,
            t,
            dt_,
            crc,
            verneed,
            flag,
            vermade,
            createsys,
            extattr,
            align=0,
            cen_extra=b"",
        ):
            blob = data
            if method == 8:
                comp = zlib.compressobj(6, zlib.DEFLATED, -15)
                blob = comp.compress(data) + comp.flush()
            extra = b""
            if method == 0 and align:
                base = dst.tell() + 30 + len(name_b)
                extra = b"\x00" * ((-base) % align)
            off = dst.tell()
            dst.write(
                struct.pack(
                    "<IHHHHHIIIHH",
                    0x04034B50,
                    verneed,
                    flag,
                    method,
                    t,
                    dt_,
                    crc,
                    len(blob),
                    len(data),
                    len(name_b),
                    len(extra),
                )
            )
            dst.write(name_b)
            dst.write(extra)
            dst.write(blob)
            entries.append(
                (
                    name_b,
                    method,
                    flag,
                    t,
                    dt_,
                    crc,
                    len(blob),
                    len(data),
                    off,
                    vermade,
                    createsys,
                    extattr,
                    cen_extra,
                )
            )

        for info in zin.infolist():
            name = info.filename
            if is_signature_meta_inf(name):
                continue
            src.seek(info.header_offset)
            lh = src.read(30)
            if struct.unpack_from("<I", lh, 0)[0] != 0x04034B50:
                raise RuntimeError(f"bad local header for {name}")
            verneed, flag, method, t, dt_ = struct.unpack_from("<HHHHH", lh, 4)
            flag &= ~0x8
            nl, el = struct.unpack_from("<HH", lh, 26)
            src.seek(info.header_offset + 30)
            name_b = src.read(nl)
            lextra = src.read(el)

            if name in replacements:
                data, r_method, r_align = replacements[name]
                crc = zlib.crc32(data) & 0xFFFFFFFF
                put(
                    name_b,
                    data,
                    r_method,
                    t,
                    dt_,
                    crc,
                    verneed,
                    flag,
                    info.create_version,
                    info.create_system,
                    info.external_attr,
                    align=r_align,
                    cen_extra=strip_zip_padding(info.extra),
                )
                continue

            src.seek(info.header_offset + 30 + nl + el)
            raw = src.read(info.compress_size)
            extra = lextra
            if method == 0:
                core = strip_zip_padding(lextra)
                align = (
                    SO_ALIGN
                    if (name.startswith("lib/") and name.endswith(".so"))
                    else STORED_ALIGN
                )
                base = dst.tell() + 30 + len(name_b) + len(core)
                extra = core + b"\x00" * ((-base) % align)
            off = dst.tell()
            dst.write(
                struct.pack(
                    "<IHHHHHIIIHH",
                    0x04034B50,
                    verneed,
                    flag,
                    method,
                    t,
                    dt_,
                    info.CRC,
                    info.compress_size,
                    info.file_size,
                    len(name_b),
                    len(extra),
                )
            )
            dst.write(name_b)
            dst.write(extra)
            dst.write(raw)
            entries.append(
                (
                    name_b,
                    method,
                    flag,
                    t,
                    dt_,
                    info.CRC,
                    info.compress_size,
                    info.file_size,
                    off,
                    info.create_version,
                    info.create_system,
                    info.external_attr,
                    info.extra,
                )
            )

        t, dt_ = _dos_date_time((2026, 11, 19, 0, 0, 0))
        for entry_name, data, method, align in additions:
            put(
                entry_name.encode("utf-8"),
                data,
                method,
                t,
                dt_,
                zlib.crc32(data) & 0xFFFFFFFF,
                20,
                0,
                20,
                3,
                0o644 << 16,
                align=align,
            )

        cd_off = dst.tell()
        for (
            name_b,
            method,
            flag,
            t,
            dt_,
            crc,
            csize,
            usize,
            off,
            vermade,
            createsys,
            extattr,
            cen_extra,
        ) in entries:
            dst.write(
                struct.pack(
                    "<IHHHHHHIIIHHHHHII",
                    0x02014B50,
                    vermade,
                    20,
                    flag,
                    method,
                    t,
                    dt_,
                    crc,
                    csize,
                    usize,
                    len(name_b),
                    len(cen_extra),
                    0,
                    0,
                    0,
                    extattr,
                    off,
                )
            )
            dst.write(name_b)
            dst.write(cen_extra)
        cd_size = dst.tell() - cd_off
        dst.write(
            struct.pack(
                "<IHHHHIIH",
                0x06054B50,
                0,
                0,
                len(entries),
                len(entries),
                cd_size,
                cd_off,
                0,
            )
        )
    zin.close()

    verify_apk_zip(dst_apk)


def verify_apk_zip(apk_path: str):
    with zipfile.ZipFile(apk_path) as check:
        bad = check.testzip()
        if bad is not None:
            raise RuntimeError(f"zip self-check failed on entry: {bad}")
        infos = check.infolist()
    with open(apk_path, "rb") as f:
        for i in infos:
            if i.compress_type != zipfile.ZIP_STORED:
                continue
            f.seek(i.header_offset)
            lh = f.read(30)
            nl, el = struct.unpack_from("<HH", lh, 26)
            data_off = i.header_offset + 30 + nl + el
            if i.filename.startswith("lib/") and i.filename.endswith(".so"):
                if data_off % SO_ALIGN != 0:
                    raise RuntimeError(
                        f"{i.filename} lost {SO_ALIGN}-byte alignment (off={data_off:#x})"
                    )
            else:
                if data_off % STORED_ALIGN != 0:
                    raise RuntimeError(
                        f"{i.filename} lost {STORED_ALIGN}-byte alignment (off={data_off:#x})"
                    )
    print(f"zip surgery ok: {len(infos)} entries (stored .so aligned to {SO_ALIGN} B)")


def sign_apk(args, unsigned_apk: str):
    signed = args.signed_out or unsigned_apk
    sign = [
        args.apksigner,
        "sign",
        "--ks",
        args.keystore,
        "--ks-key-alias",
        args.ks_alias,
        "--ks-pass",
        "pass:" + args.ks_pass,
        "--key-pass",
        "pass:" + args.ks_pass,
        "--v1-signing-enabled",
        "true",
        "--v2-signing-enabled",
        "true",
        "--out",
        signed,
        unsigned_apk,
    ]
    run(sign)
    run([args.apksigner, "verify", "--print-certs", signed])
    print("installed copy:", signed)


def _collect_plugin_libs(plugins_dir: str) -> list:
    libs = []
    if not plugins_dir:
        return libs
    for f in sorted(os.listdir(plugins_dir)):
        if (
            f.startswith(("libcinder_", "libmod_", "libhello_cinder"))
            and f.endswith(".so")
            and f != "libcinder_mobile.so"
        ):
            with open(os.path.join(plugins_dir, f), "rb") as fh:
                libs.append((f, fh.read()))
    return libs


def _write_cache(
    cache_dir: str,
    vanilla_apk: str,
    app_dex: bytes,
    loader_dex: bytes,
    runtime_so: bytes,
    plugin_libs: list,
):
    os.makedirs(cache_dir, exist_ok=True)
    with open(os.path.join(cache_dir, "classes.dex"), "wb") as f:
        f.write(app_dex)
    if loader_dex is not None:
        with open(os.path.join(cache_dir, "classes2.dex"), "wb") as f:
            f.write(loader_dex)
    with open(os.path.join(cache_dir, "libcinder_mobile.so"), "wb") as f:
        f.write(runtime_so)
    for name, data in plugin_libs:
        with open(os.path.join(cache_dir, name), "wb") as f:
            f.write(data)
    meta = {
        "vanilla_apk_sha256": sha256_file(vanilla_apk),
        "vanilla_apk_basename": os.path.basename(vanilla_apk),
        "has_loader_dex": loader_dex is not None,
    }
    with open(os.path.join(cache_dir, "cache-meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    print(f"updated cache dir: {cache_dir}")


def _plant_manifest(
    existing_entries: set, replacements: dict, additions: list, manifest: dict
):
    """Store `assets/cinder-patches.json` uncompressed (ZIP_STORED, 4B aligned)
    so the native runtime can read it straight from base.apk without zlib."""
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
    if PATCH_MANIFEST_ENTRY in existing_entries:
        replacements[PATCH_MANIFEST_ENTRY] = (manifest_bytes, 0, STORED_ALIGN)
    else:
        additions.append((PATCH_MANIFEST_ENTRY, manifest_bytes, 0, STORED_ALIGN))
    print(
        f"planted {PATCH_MANIFEST_ENTRY} "
        f"(build_id={manifest.get('build_id') or 'none'}, "
        f"{len(manifest.get('patches', []))} patch(es))"
    )


def install(args, apktool):
    work = os.path.abspath(args.work)
    decoded = os.path.join(work, "decoded")
    if os.path.isdir(decoded):
        shutil.rmtree(decoded)
    os.makedirs(work, exist_ok=True)

    with open(args.runtime_so, "rb") as f:
        runtime_so = f.read()
    plugin_libs = _collect_plugin_libs(args.plugins)
    loader_dex = (
        open(args.bridge_loader_dex, "rb").read() if args.bridge_loader_dex else None
    )

    if not args.package_suffix:

        run([apktool, "d", "-f", "-q", "--no-res", "-o", decoded, args.apk])
        patch_smali_tree(decoded, args)

        dex_apk = os.path.join(work, "dex-only.apk")
        if os.path.exists(dex_apk):
            os.remove(dex_apk)
        run([apktool, "b", "-q", "-o", dex_apk, decoded])
        with zipfile.ZipFile(dex_apk) as zf:
            patched_dex = zf.read("classes.dex")

        with zipfile.ZipFile(args.apk) as zin:
            existing = set(zin.namelist())
            original_so = zin.read(HOST_SO_ENTRY)
        patched_so, manifest = apply_host_so_patches(original_so, args)

        replacements = {
            HOST_SO_ENTRY: (patched_so, 0, SO_ALIGN),
            "classes.dex": (patched_dex, 8, 0),
        }
        additions = []
        if loader_dex is not None:
            additions.append(("classes2.dex", loader_dex, 8, 0))
        additions.append(("lib/arm64-v8a/libcinder_mobile.so", runtime_so, 0, SO_ALIGN))
        for pname, pdata in plugin_libs:
            additions.append((f"lib/arm64-v8a/{pname}", pdata, 0, SO_ALIGN))
            print("planted plugin", pname)
        _plant_manifest(existing, replacements, additions, manifest)

        zip_surgery(args.apk, args.out, replacements, additions)

        if args.cache_dir:
            _write_cache(
                args.cache_dir,
                args.apk,
                patched_dex,
                loader_dex,
                runtime_so,
                plugin_libs,
            )

        sign_apk(args, args.out)
        return

    run([apktool, "d", "-f", "-q", "-o", decoded, args.apk])
    sanitize_resource_names(decoded)

    lib = os.path.join(decoded, LIBDIR)
    os.makedirs(lib, exist_ok=True)
    with open(os.path.join(lib, "libcinder_mobile.so"), "wb") as f:
        f.write(runtime_so)
    print("planted libcinder_mobile.so")

    host_so_path = os.path.join(lib, "liblibdiscord_wasm_host.so")
    with open(host_so_path, "rb") as f:
        original_so = f.read()
    patched_so, manifest = apply_host_so_patches(original_so, args)
    with open(host_so_path, "wb") as f:
        f.write(patched_so)

    for pname, pdata in plugin_libs:
        with open(os.path.join(lib, pname), "wb") as f:
            f.write(pdata)
        print("planted plugin", pname)

    patch_smali_tree(decoded, args)
    if loader_dex is not None:
        with open(os.path.join(decoded, "classes2.dex"), "wb") as f:
            f.write(loader_dex)

    manifest_xml = os.path.join(decoded, "AndroidManifest.xml")
    with open(manifest_xml, encoding="utf-8") as f:
        m = f.read()
    old = "com.discord.release"
    new = old + args.package_suffix
    m = m.replace(f'package="{old}"', f'package="{new}"')
    if f'package="{new}"' not in m:
        raise RuntimeError("package rename: manifest package attribute not found")
    with open(manifest_xml, "w", encoding="utf-8") as f:
        f.write(m)
    print(
        f"WARNING: manifest renamed to {new}; authorities/providers are NOT rewritten "
        "— use only if you handle those yourself"
    )

    raw_built = os.path.join(work, "apktool-raw.apk")
    if os.path.exists(raw_built):
        os.remove(raw_built)
    build = [apktool, "b", "-q"]
    if args.aapt2:
        build += ["--aapt", args.aapt2]
    build += ["-o", raw_built, decoded]
    run(build)

    with zipfile.ZipFile(raw_built) as zf:
        raw_existing = set(zf.namelist())
    replacements = {}
    additions = []
    _plant_manifest(raw_existing, replacements, additions, manifest)
    zip_surgery(raw_built, args.out, replacements=replacements, additions=additions)
    sign_apk(args, args.out)


def repatch(args):
    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)
    cache = args.cache_dir

    meta_path = os.path.join(cache, "cache-meta.json")
    if os.path.isfile(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        cur_hash = sha256_file(args.apk)
        cached_hash = meta.get("vanilla_apk_sha256")
        if cached_hash and cached_hash != cur_hash:
            msg = (
                f"stale cache in {cache}: cached for APK sha256={cached_hash[:12]}…, "
                f"but input APK is {cur_hash[:12]}… (re-run `install --cache-dir {cache}`)"
            )
            if not args.allow_stale_cache:
                raise RuntimeError(msg)
            print("WARNING:", msg)

    with zipfile.ZipFile(args.apk) as zin:
        existing = set(zin.namelist())
        if "classes2.dex" in existing:
            raise RuntimeError(
                "vanilla APK already has classes2.dex; cache layout needs a bump"
            )
        original = zin.read(HOST_SO_ENTRY)

    patched_so, manifest = apply_host_so_patches(original, args)

    app_dex = open(os.path.join(cache, "classes.dex"), "rb").read()
    loader_dex_path = os.path.join(cache, "classes2.dex")
    loader_dex = (
        open(loader_dex_path, "rb").read() if os.path.isfile(loader_dex_path) else None
    )

    replacements = {
        HOST_SO_ENTRY: (patched_so, 0, SO_ALIGN),
        "classes.dex": (app_dex, 8, 0),
    }
    additions = []
    if loader_dex is not None:
        additions.append(("classes2.dex", loader_dex, 8, 0))
    for n in sorted(os.listdir(cache)):
        if n.startswith("lib") and n.endswith(".so"):
            additions.append(
                (
                    f"lib/arm64-v8a/{n}",
                    open(os.path.join(cache, n), "rb").read(),
                    0,
                    SO_ALIGN,
                )
            )
    _plant_manifest(existing, replacements, additions, manifest)

    zip_surgery(args.apk, args.out, replacements, additions)
    sign_apk(args, args.out)


def add_patch_args(parser):
    parser.add_argument(
        "--brand", default=None, help="version literal brand prefix, e.g. c1ndr"
    )
    parser.add_argument(
        "--cinder-label",
        action="store_true",
        help="add 'Cinder' to the login-screen native-runtime build label",
    )
    parser.add_argument(
        "--staff-devtools",
        action="store_true",
        help="auto-resolve and force the staff/devtools predicate to return true, unlocking Staff Settings & Dev Tools",
    )
    parser.add_argument(
        "--devtools",
        action="store_true",
        help="[DEPRECATED alias for --staff-devtools] 0x15A3374 was audited as an unrelated media/DSP vtable method",
    )
    parser.add_argument(
        "--release-access",
        action="store_true",
        help="auto-resolve and force 2026-11-release-app-access to variant 1 (static equal-length patch)",
    )
    parser.add_argument(
        "--no-telemetry",
        action="store_true",
        help="redirect every analytics channel (/science, errmon endpoint) to dead endpoints",
    )
    parser.add_argument(
        "--staff-hijack",
        default="0",
        help="[experimental, rel3567ab-only; superseded by --staff-devtools] settings section enum to redirect to the staff case (e.g. 0x38)",
    )


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--apktool", default=shutil.which("apktool") or "apktool")

    a = sub.add_parser("analyze", parents=[common])
    a.add_argument("--apk", required=True)

    i = sub.add_parser("install", parents=[common])
    i.add_argument("--apk", required=True)
    i.add_argument("--runtime-so", required=True)
    i.add_argument("--plugins")
    i.add_argument("--bridge-loader-dex")
    i.add_argument(
        "--cache-dir",
        default=None,
        help="if set, populate this cache dir for subsequent fast `repatch` runs",
    )
    i.add_argument("--out", required=True)
    i.add_argument("--signed-out")
    i.add_argument("--work", default="/tmp/cinder-mobile-install")
    i.add_argument("--aapt2")
    i.add_argument("--apksigner", default="apksigner")
    i.add_argument("--keystore", default="modloader.jks")
    i.add_argument("--ks-alias", default="mod")
    i.add_argument("--ks-pass", default="changeit")
    i.add_argument("--package-suffix", default=None)
    i.add_argument(
        "--arm", action="store_true", help="arm configured patches (verify_only=false)"
    )
    add_patch_args(i)

    r = sub.add_parser("repatch", parents=[common])
    r.add_argument("--apk", required=True)
    r.add_argument(
        "--cache-dir",
        default="build/cache",
        help="dir with patched classes.dex, loader classes2.dex, libcinder*.so",
    )
    r.add_argument(
        "--allow-stale-cache",
        action="store_true",
        help="warn instead of aborting if cache-meta.json does not match --apk",
    )
    r.add_argument("--out", required=True)
    r.add_argument("--signed-out")
    r.add_argument("--work", default="/tmp/cinder-repatch")
    r.add_argument("--apksigner", default="apksigner")
    r.add_argument("--keystore", default="modloader.jks")
    r.add_argument("--ks-alias", default="mod")
    r.add_argument("--ks-pass", default="changeit")
    add_patch_args(r)

    args = ap.parse_args()
    if args.cmd == "analyze":
        analyze(args.apk, args.apktool)
    elif args.cmd == "repatch":
        repatch(args)
    else:
        install(args, args.apktool)


if __name__ == "__main__":
    main()
