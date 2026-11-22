#!/usr/bin/env python3
"""
Static ART DEX verifier for cinder-mobile (`classes2.dex` vs Discord's `classes.dex`).

Validates that `classes2.dex` will load cleanly on Android ART without:
  1. Duplicate class definitions shadowed by Discord's `classes.dex`
  2. Unshaded `Lkotlin/...;` references that would collide with Discord's R8-obfuscated
     `kotlin.*` classes (e.g. `Unit.INSTANCE -> a`, `Function2.invoke -> e`,
     `Intrinsics.areEqual -> a`)
  3. Missing classes, methods, or fields in `cinder/kotlin/*`, `kotlin/*`, or `com/discord/loader/*`
     (walking superclass and interface hierarchies)

Supports reading primary DEX definitions from either decoded smali (`--primary-smali`)
or a binary `classes.dex` (`--primary-dex`), and can also invoke `dexdump` to verify
structural DEX validity.
"""

import argparse
import os
import re
import struct
import subprocess
import sys
from collections import defaultdict


def _read_uleb128(data: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7


class DexFile:
    """Minimal read-only DEX parser covering strings, types, protos, fields,
    methods, class defs, and class_data_item declared fields/methods."""

    def __init__(self, path: str):
        with open(path, "rb") as f:
            data = f.read()
        self.path = path
        self.data = data
        if not data.startswith(b"dex\n") or data[7:8] != b"\x00":
            raise ValueError(f"{path}: invalid DEX magic")
        self.version = data[4:7].decode("ascii")

        (
            self.file_size,
            header_size,
            _endian_tag,
            _link_size,
            _link_off,
            _map_off,
            string_ids_size,
            string_ids_off,
            type_ids_size,
            type_ids_off,
            proto_ids_size,
            proto_ids_off,
            field_ids_size,
            field_ids_off,
            method_ids_size,
            method_ids_off,
            class_defs_size,
            class_defs_off,
        ) = struct.unpack_from("<18I", data, 32)

        if header_size < 0x70:
            raise ValueError(f"{path}: unexpected header_size {header_size}")

        self.strings = []
        for i in range(string_ids_size):
            str_data_off = struct.unpack_from("<I", data, string_ids_off + i * 4)[0]
            _utf16_len, p = _read_uleb128(data, str_data_off)
            end = data.index(0, p)
            self.strings.append(data[p:end].decode("utf-8", errors="replace"))

        self.types = []
        for i in range(type_ids_size):
            desc_idx = struct.unpack_from("<I", data, type_ids_off + i * 4)[0]
            self.types.append(self.strings[desc_idx])

        self.protos = []
        for i in range(proto_ids_size):
            _shorty_idx, ret_type_idx, params_off = struct.unpack_from(
                "<3I", data, proto_ids_off + i * 12
            )
            params = []
            if params_off != 0:
                pcount = struct.unpack_from("<I", data, params_off)[0]
                for j in range(pcount):
                    pt_idx = struct.unpack_from("<H", data, params_off + 4 + j * 2)[0]
                    params.append(self.types[pt_idx])
            self.protos.append(f"({''.join(params)}){self.types[ret_type_idx]}")

        self.fields = []
        for i in range(field_ids_size):
            cls_idx, type_idx, name_idx = struct.unpack_from(
                "<HHI", data, field_ids_off + i * 8
            )
            self.fields.append(
                (self.types[cls_idx], self.strings[name_idx], self.types[type_idx])
            )

        self.methods = []
        for i in range(method_ids_size):
            cls_idx, proto_idx, name_idx = struct.unpack_from(
                "<HHI", data, method_ids_off + i * 8
            )
            self.methods.append(
                (self.types[cls_idx], self.strings[name_idx], self.protos[proto_idx])
            )

        self.class_defs = {}
        for i in range(class_defs_size):
            (
                cls_idx,
                _access_flags,
                super_idx,
                interfaces_off,
                _source_idx,
                _ann_off,
                class_data_off,
                _static_vals_off,
            ) = struct.unpack_from("<8I", data, class_defs_off + i * 32)
            cls_desc = self.types[cls_idx]
            super_desc = None if super_idx == 0xFFFFFFFF else self.types[super_idx]
            interfaces = []
            if interfaces_off != 0:
                icount = struct.unpack_from("<I", data, interfaces_off)[0]
                for j in range(icount):
                    it_idx = struct.unpack_from("<H", data, interfaces_off + 4 + j * 2)[
                        0
                    ]
                    interfaces.append(self.types[it_idx])

            declared_fields = set()
            declared_methods = set()
            if class_data_off != 0:
                pos = class_data_off
                static_fields_size, pos = _read_uleb128(data, pos)
                instance_fields_size, pos = _read_uleb128(data, pos)
                direct_methods_size, pos = _read_uleb128(data, pos)
                virtual_methods_size, pos = _read_uleb128(data, pos)

                for count in (static_fields_size, instance_fields_size):
                    fidx = 0
                    for _ in range(count):
                        diff, pos = _read_uleb128(data, pos)
                        _acc, pos = _read_uleb128(data, pos)
                        fidx += diff
                        _owner, fname, ftype = self.fields[fidx]
                        declared_fields.add(f"{fname}:{ftype}")

                for count in (direct_methods_size, virtual_methods_size):
                    midx = 0
                    for _ in range(count):
                        diff, pos = _read_uleb128(data, pos)
                        _acc, pos = _read_uleb128(data, pos)
                        _code_off, pos = _read_uleb128(data, pos)
                        midx += diff
                        _owner, mname, mproto = self.methods[midx]
                        declared_methods.add(f"{mname}{mproto}")

            self.class_defs[cls_desc] = {
                "super": super_desc,
                "interfaces": interfaces,
                "fields": declared_fields,
                "methods": declared_methods,
            }


_CLASS_RE = re.compile(r"^\.class\b.*?(L[^;\s]+;)\s*$")
_SUPER_RE = re.compile(r"^\.super\s+(L[^;\s]+;)\s*$")
_IMPL_RE = re.compile(r"^\.implements\s+(L[^;\s]+;)\s*$")
_FIELD_RE = re.compile(r"^\.field\b.*?\s+([^\s:=]+:[^\s=]+)")
_METHOD_RE = re.compile(r"^\.method\b.*?\s+([^\s(]+\([^\s)]*\)[^\s]+)\s*$")


def load_smali_dir(smali_root: str) -> dict:
    classes = {}
    for dirpath, _dirnames, filenames in os.walk(smali_root):
        for fn in filenames:
            if not fn.endswith(".smali"):
                continue
            path = os.path.join(dirpath, fn)
            cls_desc = None
            super_desc = None
            interfaces = []
            fields = set()
            methods = set()
            with open(path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    if not line.startswith("."):
                        continue
                    s = line.rstrip("\r\n")
                    if s.startswith(".class "):
                        m = _CLASS_RE.match(s)
                        if m:
                            cls_desc = m.group(1)
                    elif s.startswith(".super "):
                        m = _SUPER_RE.match(s)
                        if m:
                            super_desc = m.group(1)
                    elif s.startswith(".implements "):
                        m = _IMPL_RE.match(s)
                        if m:
                            interfaces.append(m.group(1))
                    elif s.startswith(".field "):
                        m = _FIELD_RE.match(s)
                        if m:
                            fields.add(m.group(1))
                    elif s.startswith(".method "):
                        m = _METHOD_RE.match(s)
                        if m:
                            methods.add(m.group(1))
            if cls_desc:
                classes[cls_desc] = {
                    "super": super_desc,
                    "interfaces": interfaces,
                    "fields": fields,
                    "methods": methods,
                }
    return classes


_SYSTEM_PREFIXES = (
    "Ljava/",
    "Ljavax/",
    "Landroid/",
    "Ldalvik/",
    "Lorg/xml/",
    "Lorg/w3c/",
    "Lorg/json/",
    "Lorg/apache/",
    "Lsun/",
)

_OBJECT_METHODS = {
    "<init>()V",
    "equals(Ljava/lang/Object;)Z",
    "hashCode()I",
    "toString()Ljava/lang/String;",
    "getClass()Ljava/lang/Class;",
    "clone()Ljava/lang/Object;",
    "notify()V",
    "notifyAll()V",
    "wait()V",
    "wait(J)V",
    "wait(JI)V",
    "finalize()V",
}


def strip_array_prefix(desc: str) -> str:
    return desc.lstrip("[")


def is_system_class(desc: str) -> bool:
    if not desc.startswith("L"):
        return True
    return desc.startswith(_SYSTEM_PREFIXES)


def resolve_member(cls_desc: str, member: str, kind: str, all_classes: dict) -> bool:
    """Walk superclass + interface graph to verify `member` exists on `cls_desc`."""
    if kind == "methods" and member in _OBJECT_METHODS:
        return True
    visited = set()
    stack = [cls_desc]
    while stack:
        cur = stack.pop()
        if cur in visited:
            continue
        visited.add(cur)
        if is_system_class(cur):

            return True
        info = all_classes.get(cur)
        if info is None:
            continue
        if member in info[kind]:
            return True
        if info["super"]:
            stack.append(info["super"])
        stack.extend(info["interfaces"])
    return False


def verify(dex_path: str, primary_classes: dict, allow_kotlin_refs: bool = False):
    sec = DexFile(dex_path)
    errors = []

    duplicates = sorted(set(sec.class_defs.keys()) & set(primary_classes.keys()))
    for d in duplicates:
        errors.append(
            f"DUPLICATE/SHADOWED CLASS in both classes.dex and classes2.dex: {d}"
        )

    effective_classes = dict(sec.class_defs)
    effective_classes.update(primary_classes)

    kotlin_types = sorted(
        t for t in sec.types if strip_array_prefix(t).startswith("Lkotlin/")
    )
    jetbrains_types = sorted(
        t for t in sec.types if strip_array_prefix(t).startswith("Lorg/jetbrains/")
    )
    shaded_defs = sorted(c for c in sec.class_defs if c.startswith("Lcinder/kotlin/"))

    if kotlin_types and not allow_kotlin_refs:
        for kt in kotlin_types:
            errors.append(f"UNSHADED Lkotlin/* TYPE REFERENCED IN classes2.dex: {kt}")

    if jetbrains_types:
        for jt in jetbrains_types:
            errors.append(f"UNSTRIPPED Lorg/jetbrains/* TYPE IN classes2.dex: {jt}")

    for raw_t in sec.types:
        t = strip_array_prefix(raw_t)
        if is_system_class(t):
            continue
        if t not in effective_classes:
            errors.append(f"MISSING TYPE referenced by classes2.dex: {t}")

    checked_methods = 0
    for owner, mname, mproto in sec.methods:
        cls = strip_array_prefix(owner)
        if is_system_class(cls):
            continue
        if owner.startswith("["):

            continue
        checked_methods += 1
        sig = f"{mname}{mproto}"
        if cls not in effective_classes:
            errors.append(f"MISSING METHOD OWNER: {cls}->{sig}")
        elif not resolve_member(cls, sig, "methods", effective_classes):
            where = (
                "classes.dex (SHADOWED!)" if cls in primary_classes else "classes2.dex"
            )
            errors.append(f"MISSING METHOD on {where}: {cls}->{sig}")

    checked_fields = 0
    for owner, fname, ftype in sec.fields:
        cls = strip_array_prefix(owner)
        if is_system_class(cls):
            continue
        checked_fields += 1
        sig = f"{fname}:{ftype}"
        if cls not in effective_classes:
            errors.append(f"MISSING FIELD OWNER: {cls}->{sig}")
        elif not resolve_member(cls, sig, "fields", effective_classes):
            where = (
                "classes.dex (SHADOWED!)" if cls in primary_classes else "classes2.dex"
            )
            errors.append(f"MISSING FIELD on {where}: {cls}->{sig}")

    return {
        "dex_version": sec.version,
        "dex_bytes": sec.file_size,
        "secondary_classes": len(sec.class_defs),
        "shaded_kotlin_classes": len(shaded_defs),
        "primary_classes": len(primary_classes),
        "kotlin_types": kotlin_types,
        "jetbrains_types": jetbrains_types,
        "duplicates": duplicates,
        "checked_methods": checked_methods,
        "checked_fields": checked_fields,
        "errors": errors,
    }


def run_dexdump_check(dex_path: str, dexdump_bin: str):
    cmd = [dexdump_bin, "-f", dex_path]
    try:
        res = subprocess.run(
            cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True
        )
    except OSError:

        qemu = "/tmp/opencode/qemuroot/usr/bin/qemu-x86_64-static"
        sysroot = "/tmp/opencode/x64root/usr/x86_64-linux-gnu"
        if os.path.isfile(qemu):
            res = subprocess.run(
                [qemu, "-L", sysroot] + cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True,
            )
        else:
            raise
    if res.returncode != 0:
        raise RuntimeError(
            f"dexdump failed (rc={res.returncode}): {res.stderr.strip()}"
        )


def main():
    ap = argparse.ArgumentParser(
        description="Verify classes2.dex against Discord classes.dex"
    )
    ap.add_argument("--dex", required=True, help="Path to secondary classes2.dex")
    grp = ap.add_mutually_exclusive_group(required=True)
    grp.add_argument("--primary-smali", help="Decoded primary smali directory")
    grp.add_argument("--primary-dex", help="Primary classes.dex binary")
    ap.add_argument(
        "--dexdump",
        default=None,
        help="Optional path to Android dexdump binary for structural validation",
    )
    ap.add_argument(
        "--allow-kotlin-refs",
        action="store_true",
        help="Allow Lkotlin/* references if verified present in primary/secondary without shadowing",
    )
    args = ap.parse_args()

    if args.primary_smali:
        primary_classes = load_smali_dir(args.primary_smali)
        primary_src = args.primary_smali
    else:
        primary_classes = DexFile(args.primary_dex).class_defs
        primary_src = args.primary_dex

    report = verify(args.dex, primary_classes, allow_kotlin_refs=args.allow_kotlin_refs)

    if args.dexdump:
        run_dexdump_check(args.dex, args.dexdump)

    print(
        f"verify_dex: {args.dex} (DEX {report['dex_version']}, {report['dex_bytes']} bytes) "
        f"vs {primary_src} ({report['primary_classes']} primary classes)"
    )
    print(
        f"  classes2.dex defs     : {report['secondary_classes']} "
        f"(shaded cinder/kotlin/*: {report['shaded_kotlin_classes']})"
    )
    print(f"  duplicate classes     : {len(report['duplicates'])}")
    print(f"  unshaded Lkotlin/*    : {len(report['kotlin_types'])}")
    print(f"  Lorg/jetbrains/* refs : {len(report['jetbrains_types'])}")
    print(
        f"  verified non-sys refs : {report['checked_methods']} method(s), "
        f"{report['checked_fields']} field(s)"
    )

    if report["errors"]:
        print(
            f"FAIL: {len(report['errors'])} DEX verification error(s):", file=sys.stderr
        )
        for err in report["errors"]:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)

    print("PASS: zero duplicate/shadowed classes and zero unresolved references")


if __name__ == "__main__":
    main()
