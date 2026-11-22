#!/usr/bin/env python3
"""Unit and live-APK verification tests for cinder-mobile installer & elf_finder."""

import json
import os
import struct
import sys
import tempfile
import types
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import elf_finder
import install

VANILLA_APK = "/tmp/opencode/discord-mobile.apk"


def _enc_adrp(pc: int, target_page: int, rd: int) -> int:
    pages = (target_page - (pc & ~0xFFF)) >> 12
    imm = pages & 0x1FFFFF
    immlo = imm & 0x3
    immhi = (imm >> 2) & 0x7FFFF
    return 0x90000000 | (immlo << 29) | (immhi << 5) | (rd & 0x1F)


def _enc_add_imm(dst: int, rn: int, imm12: int) -> int:
    return 0x91000000 | ((imm12 & 0xFFF) << 10) | ((rn & 0x1F) << 5) | (dst & 0x1F)


def _enc_bl(pc: int, target: int) -> int:
    imm = ((target - pc) >> 2) & 0x03FFFFFF
    return 0x94000000 | imm


def _enc_cbz_w0(pc: int, target: int) -> int:
    imm = ((target - pc) >> 2) & 0x7FFFF
    return 0x34000000 | (imm << 5)


def _enc_tbz_w0_0(pc: int, target: int) -> int:
    imm = ((target - pc) >> 2) & 0x3FFF
    return 0x36000000 | (imm << 5)


def _enc_cbz_x0(pc: int, target: int) -> int:
    imm = ((target - pc) >> 2) & 0x7FFFF
    return 0xB4000000 | (imm << 5)


def _build_synthetic_elf(
    build_id_hex: str = "0123456789abcdef0123456789abcdef01234567",
    include_ambiguous_predicate: bool = False,
    bss_memsz: int = 0x310,
) -> bytearray:
    """Construct a minimal, structurally valid AArch64 ELF64 binary with:
    - PT_LOAD (r-x) at vaddr=0, off=0
    - PT_NOTE (Android note + GNU Build ID note)
    - PT_GNU_EH_FRAME (.eh_frame_hdr + CIE + FDEs)
    - Synthetic functions for staff.devtools (predicate + widget caller + Staff Settings caller)
      and experiment.release_access (variant helper)
    """
    buf = bytearray(0x6000)

    s_widget = 0x1000
    buf[s_widget : s_widget + 22] = b"Show Dev Tools Widget\x00"
    s_key = 0x1040
    buf[s_key : s_key + 25] = b"settings-show-dev-widget\x00"
    s_staff = 0x1080
    buf[s_staff : s_staff + 15] = b"Staff Settings\x00"
    s_exp = 0x1100
    buf[s_exp : s_exp + 27] = b"2026-11-release-app-access\x00"

    note_off = 0x200
    pos = note_off
    struct.pack_into("<III", buf, pos, 8, 4, 1)
    buf[pos + 12 : pos + 20] = b"Android\x00"
    struct.pack_into("<I", buf, pos + 20, 24)
    pos += 24
    bid_bytes = bytes.fromhex(build_id_hex)
    struct.pack_into("<III", buf, pos, 4, len(bid_bytes), 3)
    buf[pos + 12 : pos + 16] = b"GNU\x00"
    buf[pos + 16 : pos + 16 + len(bid_bytes)] = bid_bytes
    pos += 16 + ((len(bid_bytes) + 3) & ~3)
    note_size = pos - note_off

    fn_pred = 0x3000
    pred_insns = [
        0xD10103FF,
        0xA9027BFD,
        0x52800020,
        0xA9427BFD,
        0x910103FF,
        0xD65F03C0,
    ]
    for idx, w in enumerate(pred_insns):
        struct.pack_into("<I", buf, fn_pred + idx * 4, w)
    fn_pred_len = len(pred_insns) * 4

    fn_pred2 = 0x3040
    for idx, w in enumerate(pred_insns):
        struct.pack_into("<I", buf, fn_pred2 + idx * 4, w)

    fn_widget = 0x3100
    w_insns = [
        0xD10103FF,
        _enc_bl(fn_widget + 4, fn_pred),
        _enc_cbz_w0(fn_widget + 8, fn_widget + 0x30),
        (
            _enc_bl(fn_widget + 12, fn_pred2)
            if include_ambiguous_predicate
            else 0xD503201F
        ),
        (
            _enc_cbz_w0(fn_widget + 16, fn_widget + 0x30)
            if include_ambiguous_predicate
            else 0xD503201F
        ),
        _enc_adrp(fn_widget + 20, s_widget & ~0xFFF, 0),
        _enc_add_imm(0, 0, s_widget & 0xFFF),
        _enc_adrp(fn_widget + 28, s_key & ~0xFFF, 1),
        _enc_add_imm(1, 1, s_key & 0xFFF),
        0xD65F03C0,
    ]
    for idx, w in enumerate(w_insns):
        struct.pack_into("<I", buf, fn_widget + idx * 4, w)
    fn_widget_len = len(w_insns) * 4

    fn_staff = 0x3200
    st_insns = [
        0xD10103FF,
        _enc_bl(fn_staff + 4, fn_pred),
        _enc_tbz_w0_0(fn_staff + 8, fn_staff + 0x20),
        _enc_bl(fn_staff + 12, fn_pred2) if include_ambiguous_predicate else 0xD503201F,
        (
            _enc_tbz_w0_0(fn_staff + 16, fn_staff + 0x20)
            if include_ambiguous_predicate
            else 0xD503201F
        ),
        _enc_adrp(fn_staff + 20, s_staff & ~0xFFF, 8),
        _enc_add_imm(8, 8, s_staff & 0xFFF),
        0xD65F03C0,
    ]
    for idx, w in enumerate(st_insns):
        struct.pack_into("<I", buf, fn_staff + idx * 4, w)
    fn_staff_len = len(st_insns) * 4

    fn_exp = 0x3300

    exp_insns = [
        0xA9BF7BFD,
        _enc_adrp(fn_exp + 4, s_exp & ~0xFFF, 1),
        _enc_add_imm(1, 1, s_exp & 0xFFF),
        _enc_bl(fn_exp + 12, fn_pred),
        _enc_cbz_x0(fn_exp + 16, fn_exp + 32),
        0xF100051F,
        0x1A9F17E0,
        0x14000002,
        0x52800040,
        0xA8C17BFD,
        0xD65F03C0,
    ]
    for idx, w in enumerate(exp_insns):
        struct.pack_into("<I", buf, fn_exp + idx * 4, w)
    fn_exp_len = len(exp_insns) * 4

    cie_off = 0x2200

    cie_body = bytes([1]) + b"zR\x00" + bytes([4, 0x78, 30, 1, 0x1B, 0, 0, 0])
    struct.pack_into("<II", buf, cie_off, 4 + len(cie_body), 0)
    buf[cie_off + 8 : cie_off + 8 + len(cie_body)] = cie_body

    fde_cursor = cie_off + 4 + 4 + len(cie_body)
    fde_cursor = (fde_cursor + 3) & ~3

    fns = [
        (fn_pred, fn_pred_len),
        (fn_pred2, fn_pred_len),
        (fn_widget, fn_widget_len),
        (fn_staff, fn_staff_len),
        (fn_exp, fn_exp_len),
    ]
    eh_entries = []
    for fn_start, fn_len in fns:
        fde_off = fde_cursor

        struct.pack_into("<I", buf, fde_off, 16)
        struct.pack_into("<I", buf, fde_off + 4, (fde_off + 4) - cie_off)
        struct.pack_into("<i", buf, fde_off + 8, fn_start - (fde_off + 8))
        struct.pack_into("<I", buf, fde_off + 12, fn_len)
        struct.pack_into("<I", buf, fde_off + 16, 0)
        eh_entries.append((fn_start, fde_off))
        fde_cursor += 20

    hdr_off = 0x2000
    struct.pack_into(
        "<BBBBiI",
        buf,
        hdr_off,
        1,
        0x1B,
        0x03,
        0x3B,
        cie_off - (hdr_off + 4),
        len(eh_entries),
    )
    for idx, (fn_start, fde_off) in enumerate(eh_entries):
        struct.pack_into(
            "<ii", buf, hdr_off + 12 + idx * 8, fn_start - hdr_off, fde_off - hdr_off
        )
    hdr_size = 12 + len(eh_entries) * 8

    phoff = 64
    phnum = 4
    buf[0:4] = b"\x7fELF"
    buf[4] = 2
    buf[5] = 1
    buf[6] = 1
    struct.pack_into(
        "<HHIQQQIHHHHHH", buf, 16, 3, 183, 1, 0, phoff, 0, 0, 64, 56, phnum, 0, 0, 0
    )

    struct.pack_into("<IIQQQQQQ", buf, phoff, 1, 5, 0, 0, 0, len(buf), len(buf), 0x1000)

    struct.pack_into(
        "<IIQQQQQQ",
        buf,
        phoff + 56,
        1,
        6,
        0x5000,
        0x6000,
        0x6000,
        0x100,
        bss_memsz,
        0x1000,
    )

    struct.pack_into(
        "<IIQQQQQQ",
        buf,
        phoff + 112,
        4,
        4,
        note_off,
        note_off,
        note_off,
        note_size,
        note_size,
        4,
    )

    struct.pack_into(
        "<IIQQQQQQ",
        buf,
        phoff + 168,
        0x6474E550,
        4,
        hdr_off,
        hdr_off,
        hdr_off,
        hdr_size,
        hdr_size,
        4,
    )
    return buf


class TestInstallHelpers(unittest.TestCase):
    def test_meta_inf_selective_stripping(self):
        for sig in (
            "META-INF/MANIFEST.MF",
            "META-INF/CERT.SF",
            "META-INF/CERT.RSA",
            "META-INF/ANDROID.DSA",
            "META-INF/SIGN.EC",
        ):
            self.assertTrue(install.is_signature_meta_inf(sig), sig)

        for keep in (
            "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory",
            "META-INF/kotlinx-coroutines-core.kotlin_module",
            "META-INF/androidx.core_core.version",
            "META-INF/com/android/build/gradle/app-metadata.properties",
            "classes.dex",
        ):
            self.assertFalse(install.is_signature_meta_inf(keep), keep)

    def test_strip_zip_padding_preserves_tlv_ending_in_nul(self):
        real_tlv = struct.pack("<HH", 0x5455, 5) + b"\x01\x12\x34\x56\x00"
        android_pad = struct.pack("<HH", 0xD935, 4) + b"\x00\x00\x00\x00"
        raw_extra = real_tlv + android_pad + b"\x00\x00\x00\x00\x00"
        self.assertEqual(install.strip_zip_padding(raw_extra), real_tlv)

    def test_patch_returns_uses_actual_register(self):
        smali = (
            ".method public final a(Lzg;)[B\n"
            "    .locals 8\n"
            "    if-eqz p1, :err\n"
            "    return-object v6\n"
            "    :err\n"
            "    return-object v0\n"
            ".end method\n"
        )
        out = install.patch_returns(
            smali,
            "a(Lzg;)[B",
            "Lcom/discord/loader/Loader;->onResponse([B)[B",
        )
        self.assertIn(
            "invoke-static {v6}, Lcom/discord/loader/Loader;->onResponse([B)[B", out
        )
        self.assertIn("move-result-object v6", out)
        self.assertIn(
            "invoke-static {v0}, Lcom/discord/loader/Loader;->onResponse([B)[B", out
        )
        self.assertIn("move-result-object v0", out)

    def test_wrap_guest_host_invoke(self):
        smali = (
            ".class public final Lcom/discord/nativeapp/bridge/GuestHost;\n"
            ".super Ljava/lang/Object;\n"
            ".implements Lcom/discord/nativeapp/bridge/WasmRuntime$Bridge;\n"
            "\n"
            ".method public invoke([B)[B\n"
            "    .locals 9\n"
            "    return-object p1\n"
            ".end method\n"
        )
        out = install.wrap_guest_host_invoke(smali)
        self.assertIn(".implements Lcom/discord/loader/HostInvoker;", out)
        self.assertIn(
            "invoke-static {p0, p1}, Lcom/discord/loader/Loader;->dispatchInvoke(Lcom/discord/loader/HostInvoker;[B)[B",
            out,
        )
        self.assertIn(".method public invokeOrig([B)[B", out)
        self.assertEqual(out.count(".method public invoke([B)[B"), 1)
        with self.assertRaises(RuntimeError):
            install.wrap_guest_host_invoke(out)

    def test_patch_guest_host_apply_ops_resizes_length_when_replaced(self):
        smali = (
            ".method public applyOps([BI)V\n"
            "    .locals 3\n"
            "    return-void\n"
            ".end method\n"
        )
        out = install.patch_guest_host_apply_ops(smali)
        self.assertIn(
            "invoke-static {p0, p1, p2}, Lcom/discord/loader/Loader;->onOps(Lcom/discord/loader/HostInvoker;[BI)[B",
            out,
        )
        self.assertIn("move-result-object v0", out)
        self.assertIn("if-eq v0, p1, :loader_ops_same", out)
        self.assertIn("move-object p1, v0", out)
        self.assertIn("array-length p2, p1", out)
        self.assertIn(":loader_ops_same", out)

        smali_zero = (
            ".method public applyOps([BI)V\n"
            "    .locals 0\n"
            "    return-void\n"
            ".end method\n"
        )
        out_zero = install.patch_guest_host_apply_ops(smali_zero)
        self.assertIn(".locals 1", out_zero)

    def test_patch_guest_host_clinit_registry_discovers_obfuscated_table(self):
        smali = (
            ".method static constructor <clinit>()V\n"
            "    .locals 5\n"
            "    sget-object v0, Lxyz9;->q:[Ljava/lang/String;\n"
            "    sput-object v0, Lcom/discord/nativeapp/bridge/GuestHost;->p:[Ljava/lang/String;\n"
            "    return-void\n"
            ".end method\n"
        )
        out = install.patch_guest_host_clinit_registry(smali)
        self.assertIn("sget-object v0, Lxyz9;->q:[Ljava/lang/String;", out)
        self.assertIn(
            "invoke-static {v0}, Lcom/discord/loader/BridgeRegistry;->register([Ljava/lang/String;)V\n"
            "    return-void",
            out,
        )

        no_table = (
            ".method static constructor <clinit>()V\n"
            "    .locals 1\n"
            "    return-void\n"
            ".end method\n"
        )
        self.assertEqual(install.patch_guest_host_clinit_registry(no_table), no_table)

    def test_patch_smali_tree_avoids_obfuscated_response_hook(self):
        with tempfile.TemporaryDirectory() as td:
            app_path = os.path.join(td, install.APP_SMALI)
            host_path = os.path.join(td, install.HOST_SMALI)
            os.makedirs(os.path.dirname(app_path), exist_ok=True)
            os.makedirs(os.path.dirname(host_path), exist_ok=True)

            with open(app_path, "w", encoding="utf-8") as f:
                f.write(
                    ".method public final onCreate()V\n"
                    "    .locals 8\n"
                    "    invoke-super {p0}, Landroid/app/Application;->onCreate()V\n"
                    "    return-void\n"
                    ".end method\n"
                )
            with open(host_path, "w", encoding="utf-8") as f:
                f.write(
                    ".class public final Lcom/discord/nativeapp/bridge/GuestHost;\n"
                    ".super Ljava/lang/Object;\n"
                    ".implements Lcom/discord/nativeapp/bridge/WasmRuntime$Bridge;\n"
                    ".method static constructor <clinit>()V\n"
                    "    .locals 2\n"
                    "    sget-object v0, Lr83;->a:[Ljava/lang/String;\n"
                    "    return-void\n"
                    ".end method\n"
                    ".method public applyOps([BI)V\n"
                    "    .locals 3\n"
                    "    return-void\n"
                    ".end method\n"
                    ".method public invoke([B)[B\n"
                    "    .locals 4\n"
                    "    return-object p1\n"
                    ".end method\n"
                    ".method public log(ILjava/lang/String;)V\n"
                    "    .locals 2\n"
                    "    return-void\n"
                    ".end method\n"
                )

            dispatch_a = os.path.join(td, "smali", "q6.smali")
            dispatch_b = os.path.join(td, "smali", "l71.smali")
            wy3_path = os.path.join(td, "smali", "wy3.smali")
            with open(dispatch_a, "w", encoding="utf-8") as f:
                f.write(
                    ".class public final Lq6;\n"
                    ".method public run()V\n"
                    "    .locals 6\n"
                    "    invoke-static {v4, v5, v0}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
                    "    return-void\n"
                    ".end method\n"
                )
            with open(dispatch_b, "w", encoding="utf-8") as f:
                f.write(
                    ".class public final Ll71;\n"
                    ".method public drain([B)V\n"
                    "    .locals 10\n"
                    "    invoke-static {v8, v9, p1}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
                    "    return-void\n"
                    ".end method\n"
                )
            with open(wy3_path, "w", encoding="utf-8") as f:
                f.write(
                    ".class public abstract Lwy3;\n"
                    ".method public static b(I)Z\n"
                    "    .locals 1\n"
                    "    sget-object v0, Lwy3;->a:[Ljava/lang/String;\n"
                    "    sget-object v0, Lyr2;->a:[Ljava/lang/String;\n"
                    '    const-string v0, "` is not in prop_names.txt; dropped"\n'
                    "    return p0\n"
                    ".end method\n"
                )

            args = types.SimpleNamespace(arm=False, bridge_loader_dex="/tmp/fake.dex")
            install.patch_smali_tree(td, args)

            with open(host_path, encoding="utf-8") as f:
                host_out = f.read()
            with open(app_path, encoding="utf-8") as f:
                app_out = f.read()
            with open(dispatch_a, encoding="utf-8") as f:
                disp_a_out = f.read()
            with open(dispatch_b, encoding="utf-8") as f:
                disp_b_out = f.read()

            self.assertIn(".implements Lcom/discord/loader/HostInvoker;", host_out)
            self.assertIn(
                "Loader;->dispatchInvoke(Lcom/discord/loader/HostInvoker;[B)[B",
                host_out,
            )
            self.assertIn(".method public invokeOrig([B)[B", host_out)
            self.assertIn("array-length p2, p1", host_out)
            self.assertIn("BridgeRegistry;->register([Ljava/lang/String;)V", host_out)
            self.assertIn("sget-object v0, Lyr2;->a:[Ljava/lang/String;", host_out)
            self.assertIn("UiOps;->registerPropNames([Ljava/lang/String;)V", host_out)
            self.assertNotIn("a(Lzg;)[B", host_out)
            self.assertNotIn("onResponse", host_out)
            self.assertIn("Loader;->init(Landroid/content/Context;)V", app_out)
            self.assertIn(
                "invoke-static {v0}, Lcom/discord/loader/Loader;->onDispatch([B)[B",
                disp_a_out,
            )
            self.assertIn("if-eqz v0, :loader_skip_dispatch_0", disp_a_out)
            self.assertIn(
                "invoke-static {p1}, Lcom/discord/loader/Loader;->onDispatch([B)[B",
                disp_b_out,
            )
            self.assertIn("if-eqz p1, :loader_skip_dispatch_0", disp_b_out)

    def test_patch_native_dispatch_smali_variants_and_guards(self):
        smali = (
            ".class public Ltest;\n"
            ".method public foo([B)Z\n"
            "    .locals 20\n"
            "    invoke-static {v4, v5, v0}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
            "    invoke-static/range {v16 .. v18}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
            "    .line 99\n"
            "    move-result v1\n"
            "    return v1\n"
            ".end method\n"
        )
        patched, count = install.patch_native_dispatch_smali(smali)
        self.assertEqual(count, 2)
        self.assertIn(
            "    invoke-static {v0}, Lcom/discord/loader/Loader;->onDispatch([B)[B\n"
            "    move-result-object v0\n"
            "    if-eqz v0, :loader_skip_dispatch_0\n"
            "    invoke-static {v4, v5, v0}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
            "    :loader_skip_dispatch_0\n",
            patched,
        )
        self.assertIn(
            "    invoke-static/range {v18 .. v18}, Lcom/discord/loader/Loader;->onDispatch([B)[B\n"
            "    move-result-object v18\n"
            "    if-nez v18, :loader_run_dispatch_1\n"
            "    const/4 v1, 0x1\n"
            "    goto :loader_after_dispatch_1\n"
            "    :loader_run_dispatch_1\n"
            "    invoke-static/range {v16 .. v18}, Lcom/discord/nativeapp/bridge/WasmRuntime;->nativeDispatch(J[B)Z\n"
            "    .line 99\n"
            "    move-result v1\n"
            "    :loader_after_dispatch_1\n",
            patched,
        )

        with self.assertRaises(RuntimeError):
            install.patch_native_dispatch_smali(patched)

        with tempfile.TemporaryDirectory() as td:
            os.makedirs(os.path.join(td, "smali"))
            with open(
                os.path.join(td, "smali", "empty.smali"), "w", encoding="utf-8"
            ) as f:
                f.write(".class public Lempty;\n")
            with self.assertRaises(RuntimeError):
                install.patch_native_dispatch_sites(td)

    def test_discover_prop_names_field_ignores_self_class_and_fails_soft(self):
        with tempfile.TemporaryDirectory() as td:
            os.makedirs(os.path.join(td, "smali"))
            with open(
                os.path.join(td, "smali", "abc.smali"), "w", encoding="utf-8"
            ) as f:
                f.write(
                    ".class public abstract Labc;\n"
                    ".method static foo()V\n"
                    "    sget-object v1, Labc;->a:[Ljava/lang/String;\n"
                    "    sget-object v2, Lzz9;->k:[Ljava/lang/String;\n"
                    '    const-string v3, "` is not in prop_names.txt; dropped"\n'
                    "    return-void\n"
                    ".end method\n"
                )
            self.assertEqual(
                install.discover_prop_names_field(td),
                "Lzz9;->k:[Ljava/lang/String;",
            )
        with tempfile.TemporaryDirectory() as td:
            os.makedirs(os.path.join(td, "smali"))
            self.assertIsNone(install.discover_prop_names_field(td))

    def test_telemetry_dead_name_uses_colon_and_preserves_length(self):
        buf = bytearray(
            b"prefix /science middle release-errmon.disabled.tools suffix /science"
        )
        orig_len = len(buf)
        install.patch_telemetry_buf(buf)
        self.assertEqual(len(buf), orig_len)
        self.assertNotIn(b"/science", buf)
        self.assertNotIn(b"disabled.tools", buf)
        self.assertIn(b"release-errmon::c1nd3r.void.x", buf)

    def test_staff_hijack_guard_rejects_bad_table(self):
        buf = bytearray(0x39EC80)
        with self.assertRaises(RuntimeError):
            install.patch_staff_hijack_buf(buf, 0x38)
        struct.pack_into("<H", buf, 0x39EBFA, 0x193)
        struct.pack_into("<H", buf, 0x39EBFA + 2 * 0x38, 0x200)
        install.patch_staff_hijack_buf(buf, 0x38)
        self.assertEqual(struct.unpack_from("<H", buf, 0x39EBFA + 2 * 0x38)[0], 0x193)

    def test_zip_surgery_16k_alignment_and_meta_inf_preservation(self):
        with tempfile.TemporaryDirectory() as td:
            src = os.path.join(td, "vanilla.apk")
            dst = os.path.join(td, "patched.apk")
            real_tlv = struct.pack("<HH", 0x5455, 5) + b"\x01\xaa\xbb\xcc\x00"
            with zipfile.ZipFile(src, "w") as zf:
                zf.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
                zf.writestr("META-INF/CERT.RSA", b"FAKECERT")
                zf.writestr("META-INF/services/com.example.Svc", b"com.example.Impl\n")
                zf.writestr("classes.dex", b"dex\n035\x00" + b"A" * 64)

                zi_res = zipfile.ZipInfo("resources.arsc")
                zi_res.compress_type = zipfile.ZIP_STORED
                zi_res.extra = real_tlv + b"\x00\x00"
                zf.writestr(zi_res, b"ARSC" + b"\x01" * 32)

                zi_so = zipfile.ZipInfo(install.HOST_SO_ENTRY)
                zi_so.compress_type = zipfile.ZIP_STORED
                zf.writestr(zi_so, b"\x7fELF" + b"\x00" * 128)

            manifest_bytes = json.dumps(
                {"schema": 1, "build_id": "deadbeef", "patches": []}
            ).encode()
            install.zip_surgery(
                src,
                dst,
                replacements={
                    install.HOST_SO_ENTRY: (
                        b"\x7fELF" + b"\x99" * 128,
                        0,
                        install.SO_ALIGN,
                    ),
                    "classes.dex": (b"dex\n035\x00" + b"B" * 64, 8, 0),
                },
                additions=[
                    ("classes2.dex", b"dex\n035\x00" + b"C" * 32, 8, 0),
                    (
                        "lib/arm64-v8a/libcinder_mobile.so",
                        b"\x7fELF" + b"\x55" * 64,
                        0,
                        install.SO_ALIGN,
                    ),
                    (
                        install.PATCH_MANIFEST_ENTRY,
                        manifest_bytes,
                        0,
                        install.STORED_ALIGN,
                    ),
                ],
            )

            with zipfile.ZipFile(dst) as zf:
                names = set(zf.namelist())
                self.assertNotIn("META-INF/MANIFEST.MF", names)
                self.assertNotIn("META-INF/CERT.RSA", names)
                self.assertIn("META-INF/services/com.example.Svc", names)
                self.assertEqual(
                    zf.getinfo(install.PATCH_MANIFEST_ENTRY).compress_type,
                    zipfile.ZIP_STORED,
                )
                self.assertEqual(zf.read(install.PATCH_MANIFEST_ENTRY), manifest_bytes)
                self.assertEqual(zf.read("classes.dex"), b"dex\n035\x00" + b"B" * 64)
                self.assertEqual(zf.read("classes2.dex"), b"dex\n035\x00" + b"C" * 32)
                self.assertEqual(
                    zf.read(install.HOST_SO_ENTRY), b"\x7fELF" + b"\x99" * 128
                )
                self.assertEqual(zf.read("resources.arsc"), b"ARSC" + b"\x01" * 32)

                with open(dst, "rb") as raw_f:
                    info = zf.getinfo("resources.arsc")
                    raw_f.seek(info.header_offset)
                    lh = raw_f.read(30)
                    nl, el = struct.unpack_from("<HH", lh, 26)
                    raw_f.seek(info.header_offset + 30 + nl)
                    lextra = raw_f.read(el)
                    self.assertTrue(
                        lextra.startswith(real_tlv), f"TLV corrupted: {lextra!r}"
                    )


class TestElfFinder(unittest.TestCase):
    def test_synthetic_elf_build_id_eh_frame_and_finders(self):
        bid = "0000000000000000000000000000000000000000"
        raw = _build_synthetic_elf(build_id_hex=bid)
        elf = elf_finder.ElfImage(raw)

        self.assertEqual(elf.build_id, bid)
        self.assertIsNotNone(elf.eh_frame)
        self.assertEqual(elf.eh_frame.fde_count, 5)
        self.assertEqual(elf.find_function(0x3000), (0x3000, 0x3018))
        self.assertEqual(elf.find_function(0x3114), (0x3100, 0x3128))
        self.assertIsNone(elf.find_function(0x2FFF))

        expected_flag_va = 0x6310
        expected_stub = elf_finder.encode_adrp_ldrb_ret(0x3000, expected_flag_va)
        self.assertEqual(len(expected_stub), 12)
        self.assertEqual(
            elf_finder.decode_adrp_ldrb_ret(expected_stub, 0x3000), expected_flag_va
        )

        staff = elf_finder.find_staff_devtools(elf)
        self.assertEqual(len(staff.edits), 1)
        self.assertEqual(staff.edits[0].file_offset, 0x3000)
        self.assertEqual(len(staff.edits[0].orig), 12)
        self.assertEqual(staff.edits[0].patched, expected_stub)
        self.assertEqual(
            staff.runtime_flags, (("staff.devtools", expected_flag_va, 1),)
        )

        exp = elf_finder.find_release_access(elf)
        self.assertEqual(len(exp.edits), 2)
        self.assertEqual(exp.edits[0].file_offset, 0x3318)
        self.assertEqual(exp.edits[1].file_offset, 0x3320)

        args = types.SimpleNamespace(
            brand=None,
            devtools=True,
            staff_devtools=True,
            release_access=True,
            no_telemetry=False,
            staff_hijack="0",
            cinder_label=False,
        )
        patched, manifest = install.apply_host_so_patches(bytes(raw), args)
        self.assertEqual(manifest["build_id"], bid)
        tags = [p["tag"] for p in manifest["patches"]]
        self.assertEqual(
            tags,
            [
                "experiment.release_access.1",
                "experiment.release_access.2",
                "staff.devtools",
            ],
        )
        self.assertEqual(patched[0x3000:0x300C], expected_stub)
        self.assertEqual(
            manifest.get("runtime_flags"),
            {"staff.devtools": {"vaddr": expected_flag_va, "default": 1}},
        )
        self.assertEqual(patched[0x3318:0x331C], elf_finder.BYTES_MOV_W0_1)
        self.assertEqual(patched[0x3320:0x3324], elf_finder.BYTES_MOV_W0_1)

        elf_patched = elf_finder.ElfImage(patched)
        self.assertEqual(
            elf_finder.find_staff_devtools(elf_patched, allow_already_patched=True)
            .edits[0]
            .status,
            "ARMED",
        )
        self.assertEqual(
            [
                e.status
                for e in elf_finder.find_release_access(
                    elf_patched, allow_already_patched=True
                ).edits
            ],
            ["ARMED", "ARMED"],
        )
        with self.assertRaises(elf_finder.FinderError):
            elf_finder.find_staff_devtools(elf_patched, allow_already_patched=False)
        with self.assertRaises(elf_finder.FinderError):
            elf_finder.find_release_access(elf_patched, allow_already_patched=False)

    def test_synthetic_elf_fails_loudly_on_ambiguous_candidates(self):
        raw = _build_synthetic_elf(include_ambiguous_predicate=True)
        elf = elf_finder.ElfImage(raw)
        with self.assertRaises(elf_finder.FinderError) as ctx:
            elf_finder.find_staff_devtools(elf)
        self.assertIn("ambiguous", str(ctx.exception))

    def test_bss_flag_slot_fails_loudly_when_slack_insufficient(self):
        raw = _build_synthetic_elf(bss_memsz=0xFE0)
        elf = elf_finder.ElfImage(raw)
        with self.assertRaises(elf_finder.FinderError) as ctx:
            elf_finder.find_bss_flag_slot(elf, index=0)
        self.assertIn("slack", str(ctx.exception).lower())

    @unittest.skip("live internal-build APK fixture no longer supported")
    def test_live_apk_rel3567ab_auto_finders_match_known_offsets(self):
        with zipfile.ZipFile(VANILLA_APK) as zf:
            so = zf.read(install.HOST_SO_ENTRY)
        elf = elf_finder.ElfImage(so)
        self.assertTrue(elf.build_id)
        self.assertIsNotNone(elf.eh_frame)
        self.assertGreater(elf.eh_frame.fde_count, 200000)

        self.assertEqual(elf.find_function(0x1D57784), (0x1D57784, 0x1D57824))
        self.assertEqual(elf.find_function(0x2A7B40C), (0x2A7B3B0, 0x2A7B420))
        self.assertEqual(elf.find_function(0x39A6C24), (0x39A6B90, 0x39A6D68))

        results = elf_finder.find_all_active_patches(elf, allow_already_patched=False)

        staff_res, staff_err = results["staff.devtools"]
        self.assertIsNone(staff_err)
        self.assertEqual(len(staff_res.edits), 1)
        self.assertEqual(staff_res.edits[0].file_offset, 0x1D57784)
        self.assertEqual(staff_res.edits[0].orig.hex(), "ff0301d1fd7b02a9f31b00f9")
        self.assertEqual(staff_res.edits[0].patched.hex(), "681102f000414c39c0035fd6")
        self.assertEqual(staff_res.runtime_flags, (("staff.devtools", 0x5F86310, 1),))

        exp_res, exp_err = results["experiment.release_access"]
        self.assertIsNone(exp_err)
        self.assertEqual(len(exp_res.edits), 2)
        self.assertEqual(exp_res.edits[0].file_offset, 0x2A7B40C)
        self.assertEqual(exp_res.edits[0].orig.hex(), "e0179f1a")
        self.assertEqual(exp_res.edits[0].patched.hex(), "20008052")
        self.assertEqual(exp_res.edits[1].file_offset, 0x2A7B414)
        self.assertEqual(exp_res.edits[1].orig.hex(), "40008052")
        self.assertEqual(exp_res.edits[1].patched.hex(), "20008052")


if __name__ == "__main__":
    unittest.main()
