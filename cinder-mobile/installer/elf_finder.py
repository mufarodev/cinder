#!/usr/bin/env python3
"""Pure-Python AArch64 ELF64 analyzer and dynamic patch-site finder.

Parses `liblibdiscord_wasm_host.so` in-memory using only the Python standard
library (`struct`, `array`) — no external readelf/objdump dependencies:
  - ELF64 PT_LOAD segments (vaddr <-> file_offset translation)
  - PT_NOTE (NT_GNU_BUILD_ID extraction)
  - PT_GNU_EH_FRAME (.eh_frame_hdr binary search -> exact (fn_start, fn_end))
  - Fast single-pass AArch64 ADRP+ADD xref scanner
  - Update-proof auto-finders for active static patches (`staff.devtools`,
    `experiment.release_access`)
"""

from __future__ import annotations

import array
from dataclasses import dataclass
import struct
import sys
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
EM_AARCH64 = 183

PT_LOAD = 1
PT_NOTE = 4
PT_GNU_EH_FRAME = 0x6474E550

PF_X = 0x1
PF_W = 0x2
PF_R = 0x4

NT_GNU_BUILD_ID = 3


DW_EH_PE_OMIT = 0xFF
DW_EH_PE_ABSPTR = 0x00
DW_EH_PE_ULEB128 = 0x01
DW_EH_PE_UDATA2 = 0x02
DW_EH_PE_UDATA4 = 0x03
DW_EH_PE_UDATA8 = 0x04
DW_EH_PE_SLEB128 = 0x09
DW_EH_PE_SDATA2 = 0x0A
DW_EH_PE_SDATA4 = 0x0B
DW_EH_PE_SDATA8 = 0x0C

DW_EH_PE_PCREL = 0x10
DW_EH_PE_DATAREL = 0x30


INSN_MOV_W0_1 = 0x52800020
INSN_MOV_W0_2 = 0x52800040
INSN_CSET_W0_EQ = 0x1A9F17E0
INSN_RET = 0xD65F03C0
INSN_NOP = 0xD503201F
INSN_TST_W0_FF = 0x72001C1F

BYTES_MOV_W0_1 = struct.pack("<I", INSN_MOV_W0_1)
BYTES_NOP = struct.pack("<I", INSN_NOP)
BYTES_MOV_W0_2 = struct.pack("<I", INSN_MOV_W0_2)
BYTES_CSET_W0_EQ = struct.pack("<I", INSN_CSET_W0_EQ)
BYTES_MOV_W0_1_RET = struct.pack("<II", INSN_MOV_W0_1, INSN_RET)


ALL_FINDER_ANCHORS: Tuple[bytes, ...] = (
    b"Show Dev Tools Widget",
    b"settings-show-dev-widget",
    b"Staff Settings",
    b"Build Status",
    b"2026-11-release-app-access",
)


class FinderError(RuntimeError):
    """Raised when an auto-finder finds 0 or ambiguous (>1) candidates."""


@dataclass(frozen=True)
class Segment:
    flags: int
    offset: int
    vaddr: int
    filesz: int
    memsz: int

    @property
    def readable(self) -> bool:
        return bool(self.flags & PF_R)

    @property
    def writable(self) -> bool:
        return bool(self.flags & PF_W)

    @property
    def executable(self) -> bool:
        return bool(self.flags & PF_X)


@dataclass(frozen=True)
class Xref:
    adrp_pc: int
    add_pc: int
    target_vaddr: int
    fn_start: Optional[int]
    fn_end: Optional[int]


@dataclass(frozen=True)
class PatchEdit:
    tag: str
    file_offset: int
    vaddr: int
    orig: bytes
    patched: bytes
    status: str = "VANILLA"
    detail: str = ""

    def to_manifest_entry(self) -> Dict[str, str]:
        return {
            "tag": self.tag,
            "file_offset": f"{self.file_offset:#x}",
            "orig_hex": self.orig.hex(),
            "patched_hex": self.patched.hex(),
        }


@dataclass(frozen=True)
class PatchResolution:
    name: str
    fn_start: int
    fn_end: int
    edits: Tuple[PatchEdit, ...]
    detail: str = ""
    runtime_flags: Tuple[Tuple[str, int, int], ...] = ()


def _read_uleb128(buf: memoryview, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    n = len(buf)
    while pos < n:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, pos
        shift += 7
    raise ValueError("truncated ULEB128")


def _read_sleb128(buf: memoryview, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    n = len(buf)
    while pos < n:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if (b & 0x80) == 0:
            if shift < 64 and (b & 0x40):
                result |= -(1 << shift)
            return result, pos
    raise ValueError("truncated SLEB128")


def _read_eh_value(
    buf: memoryview,
    file_pos: int,
    enc: int,
    field_vaddr: int,
    datarel_base: int,
    apply_rel: bool = True,
) -> Tuple[Optional[int], int]:
    """Decode one DWARF `.eh_frame` / `.eh_frame_hdr` encoded pointer/value."""
    if enc == DW_EH_PE_OMIT:
        return None, file_pos
    fmt = enc & 0x0F
    rel = enc & 0x70

    if fmt in (DW_EH_PE_ABSPTR, DW_EH_PE_UDATA8):
        val = struct.unpack_from("<Q", buf, file_pos)[0]
        next_pos = file_pos + 8
    elif fmt == DW_EH_PE_SDATA8:
        val = struct.unpack_from("<q", buf, file_pos)[0]
        next_pos = file_pos + 8
    elif fmt == DW_EH_PE_UDATA4:
        val = struct.unpack_from("<I", buf, file_pos)[0]
        next_pos = file_pos + 4
    elif fmt == DW_EH_PE_SDATA4:
        val = struct.unpack_from("<i", buf, file_pos)[0]
        next_pos = file_pos + 4
    elif fmt == DW_EH_PE_UDATA2:
        val = struct.unpack_from("<H", buf, file_pos)[0]
        next_pos = file_pos + 2
    elif fmt == DW_EH_PE_SDATA2:
        val = struct.unpack_from("<h", buf, file_pos)[0]
        next_pos = file_pos + 2
    elif fmt == DW_EH_PE_ULEB128:
        val, next_pos = _read_uleb128(buf, file_pos)
    elif fmt == DW_EH_PE_SLEB128:
        val, next_pos = _read_sleb128(buf, file_pos)
    else:
        raise ValueError(f"unsupported DWARF pointer encoding {enc:#x}")

    if apply_rel and val != 0:
        if rel == DW_EH_PE_PCREL:
            val += field_vaddr
        elif rel == DW_EH_PE_DATAREL:
            val += datarel_base
        elif rel != 0:
            raise ValueError(f"unsupported DWARF relocation modifier {enc:#x}")
    return val, next_pos


def decode_adrp(word: int, pc: int) -> Optional[int]:
    """Decode AArch64 `ADRP Xd, label` -> target 4 KiB page address."""
    if (word & 0x9F000000) != 0x90000000:
        return None
    immlo = (word >> 29) & 0x3
    immhi = (word >> 5) & 0x7FFFF
    imm = (immhi << 2) | immlo
    if imm & 0x100000:
        imm -= 0x200000
    return (pc & ~0xFFF) + (imm << 12)


def decode_add_imm(word: int, base_reg: int) -> Optional[int]:
    """Decode AArch64 `ADD Xd, Xn, #imm12` (64-bit, S=0, sh=0) matching `rn == base_reg`."""
    if (word & 0xFFC00000) != 0x91000000:
        return None
    rn = (word >> 5) & 0x1F
    if rn != base_reg:
        return None
    return (word >> 10) & 0xFFF


def decode_bl(word: int, pc: int) -> Optional[int]:
    """Decode AArch64 `BL <imm26>` -> target vaddr."""
    if (word & 0xFC000000) != 0x94000000:
        return None
    imm = word & 0x03FFFFFF
    if imm & 0x02000000:
        imm -= 0x04000000
    return pc + (imm << 2)


def decode_cbz_x0(word: int, pc: int) -> Optional[int]:
    """Decode AArch64 `CBZ X0, <imm19>` -> branch target vaddr."""
    if (word & 0xFF00001F) != 0xB4000000:
        return None
    imm = (word >> 5) & 0x7FFFF
    if imm & 0x40000:
        imm -= 0x80000
    return pc + (imm << 2)


def is_cbz_w0(word: int) -> bool:
    """True if `word` is `CBZ W0, <label>`."""
    return (word & 0xFF00001F) == 0x34000000


def is_tbz_w0_bit0(word: int) -> bool:
    """True if `word` is `TBZ W0, #0, <label>`."""
    return (word & 0xFFF8001F) == 0x36000000


def is_cmp_x_imm1(word: int) -> bool:
    """True if `word` is `CMP Xn, #1` (`SUBS XZR, Xn, #1`, sh=0)."""
    return (word & 0xFFFFFC1F) == 0xF100041F


def is_standard_prologue(w0: int, w1: int) -> bool:
    """Check whether the first two AArch64 instructions form a standard frame prologue."""

    if (w0 & 0xFFC003FF) == 0xD10003FF and (w1 & 0xFFC07FFF) == 0xA9007BFD:
        return True

    if (w0 & 0xFFC07FFF) == 0xA9807BFD:
        if w1 == 0x910003FD:
            return True
        if (w1 & 0xFFC003E0) == 0xA90003E0 or (w1 & 0xFFC003E0) == 0xF90003E0:
            return True
    return False


def encode_adrp_ldrb_ret(pred_vaddr: int, flag_vaddr: int) -> bytes:
    """Encode a 12-byte position-independent AArch64 predicate stub reading a byte flag:
    adrp x8, #flag_page
    ldrb w0, [x8, #flag_off]
    ret
    """
    if (pred_vaddr & 3) != 0:
        raise FinderError(f"predicate vaddr {pred_vaddr:#x} is not 4-byte aligned")
    pred_page = pred_vaddr & ~0xFFF
    flag_page = flag_vaddr & ~0xFFF
    delta_pages = (flag_page - pred_page) >> 12
    if delta_pages < -0x100000 or delta_pages > 0x0FFFFF:
        raise FinderError(
            f"ADRP page delta out of signed 21-bit range: pred={pred_vaddr:#x} flag={flag_vaddr:#x}"
        )
    imm21 = delta_pages & 0x1FFFFF
    immlo = imm21 & 0x3
    immhi = (imm21 >> 2) & 0x7FFFF
    adrp = 0x90000000 | (immlo << 29) | (immhi << 5) | 8
    flag_off = flag_vaddr & 0xFFF
    ldrb = 0x39400000 | (flag_off << 10) | (8 << 5) | 0
    return struct.pack("<III", adrp, ldrb, INSN_RET)


def decode_adrp_ldrb_ret(stub: bytes, pred_vaddr: int) -> Optional[int]:
    """Decode a 12-byte `adrp x8, page; ldrb w0, [x8, #off]; ret` stub -> flag_vaddr, or None."""
    if len(stub) < 12:
        return None
    w0, w1, w2 = struct.unpack_from("<III", stub, 0)
    if w2 != INSN_RET:
        return None
    if (w0 & 0x9F00001F) != 0x90000008:
        return None
    if (w1 & 0xFFC003FF) != 0x39400100:
        return None
    page = decode_adrp(w0, pred_vaddr)
    if page is None:
        return None
    off = (w1 >> 10) & 0xFFF
    return page + off


def find_bss_flag_slot(elf: "ElfImage", index: int = 0, min_slack: int = 64) -> int:
    """Pick a 16-byte-aligned runtime flag slot in the `.bss` tail slack of the last
    writable `PT_LOAD` segment, strictly past `p_vaddr + p_memsz` and strictly inside
    the OS-mapped page (validated against both 4 KiB and 16 KiB Android page sizes).
    Fails loudly with `FinderError` if insufficient slack is available.
    """
    if index < 0:
        raise FinderError(f"bss flag index must be >= 0 (got {index})")
    writable = [seg for seg in elf.segments if seg.writable]
    if not writable:
        raise FinderError("ELF has no writable PT_LOAD segment for .bss runtime flags")
    last = writable[-1]
    seg_end = last.vaddr + last.memsz
    aligned_base = (seg_end + 0xF) & ~0xF
    page4_end = (seg_end + 0xFFF) & ~0xFFF
    page16_end = (seg_end + 0x3FFF) & ~0x3FFF

    avail_slack = page4_end - aligned_base
    if avail_slack < min_slack:
        raise FinderError(
            f"insufficient .bss page slack in last writable PT_LOAD "
            f"(end={seg_end:#x}, aligned={aligned_base:#x}, page4_end={page4_end:#x}, "
            f"slack={avail_slack}B < required {min_slack}B)"
        )
    slot = aligned_base + index * 16
    if slot + 16 > page4_end or slot + 16 > page16_end:
        raise FinderError(
            f".bss flag slot index={index} ({slot:#x}) exceeds mapped page end {page4_end:#x}"
        )
    return slot


class EhFrameIndex:
    """Binary-searchable `.eh_frame_hdr` table mapping PC -> `(fn_start, fn_end)`."""

    def __init__(self, elf: "ElfImage", hdr_offset: int, hdr_vaddr: int, hdr_size: int):
        self._elf = elf
        self._buf = elf.view
        self.hdr_offset = hdr_offset
        self.hdr_vaddr = hdr_vaddr
        self.hdr_size = hdr_size
        self._cie_fde_enc_cache: Dict[int, int] = {}

        if hdr_size < 12:
            raise ValueError(f".eh_frame_hdr too small ({hdr_size} bytes)")
        ver, eh_ptr_enc, fde_cnt_enc, tab_enc = struct.unpack_from(
            "<BBBB", self._buf, hdr_offset
        )
        if ver != 1:
            raise ValueError(f"unsupported .eh_frame_hdr version {ver}")

        pos = hdr_offset + 4
        _, pos = _read_eh_value(
            self._buf, pos, eh_ptr_enc, hdr_vaddr + (pos - hdr_offset), hdr_vaddr
        )
        fde_cnt, pos = _read_eh_value(
            self._buf,
            pos,
            fde_cnt_enc,
            hdr_vaddr + (pos - hdr_offset),
            hdr_vaddr,
            apply_rel=False,
        )
        if fde_cnt is None or fde_cnt < 0:
            raise ValueError("missing FDE count in .eh_frame_hdr")

        self.table_enc = tab_enc
        self.table_offset = pos
        self.fde_count = int(fde_cnt)

        if tab_enc == (DW_EH_PE_DATAREL | DW_EH_PE_SDATA4):
            self._entry_size = 8
            self._entry_fmt = "<ii"
            self._is_datarel = True
        elif tab_enc == (DW_EH_PE_DATAREL | DW_EH_PE_UDATA4):
            self._entry_size = 8
            self._entry_fmt = "<II"
            self._is_datarel = True
        elif tab_enc in (DW_EH_PE_ABSPTR, DW_EH_PE_UDATA8):
            self._entry_size = 16
            self._entry_fmt = "<QQ"
            self._is_datarel = False
        else:
            raise ValueError(f"unsupported .eh_frame_hdr table_enc {tab_enc:#x}")

        table_end = self.table_offset + self.fde_count * self._entry_size
        if table_end > hdr_offset + hdr_size or table_end > len(self._buf):
            raise ValueError(
                f".eh_frame_hdr table truncated: need {table_end:#x}, have {hdr_offset + hdr_size:#x}"
            )

    def _entry(self, idx: int) -> Tuple[int, int]:
        init_raw, fde_raw = struct.unpack_from(
            self._entry_fmt, self._buf, self.table_offset + idx * self._entry_size
        )
        if self._is_datarel:
            return self.hdr_vaddr + init_raw, self.hdr_vaddr + fde_raw
        return init_raw, fde_raw

    def _cie_fde_encoding(self, cie_vaddr: int) -> int:
        cached = self._cie_fde_enc_cache.get(cie_vaddr)
        if cached is not None:
            return cached
        cie_off = self._elf.vaddr_to_file_offset(cie_vaddr, 16)
        if cie_off is None:
            return DW_EH_PE_PCREL | DW_EH_PE_SDATA4

        length = struct.unpack_from("<I", self._buf, cie_off)[0]
        pos = cie_off + 4
        if length == 0xFFFFFFFF:
            length = struct.unpack_from("<Q", self._buf, pos)[0]
            pos += 8

        pos += 4
        ver = self._buf[pos]
        pos += 1

        aug_start = pos
        while pos < len(self._buf) and self._buf[pos] != 0:
            pos += 1
        aug = bytes(self._buf[aug_start:pos])
        pos += 1

        if ver >= 4:
            pos += 2

        _, pos = _read_uleb128(self._buf, pos)
        _, pos = _read_sleb128(self._buf, pos)

        if ver == 1:
            pos += 1
        else:
            _, pos = _read_uleb128(self._buf, pos)

        fde_enc = DW_EH_PE_ABSPTR
        if aug.startswith(b"z"):
            _, pos = _read_uleb128(self._buf, pos)
            for ch in aug[1:]:
                if ch == ord("L"):
                    pos += 1
                elif ch == ord("P"):
                    p_enc = self._buf[pos]
                    pos += 1
                    _, pos = _read_eh_value(
                        self._buf,
                        pos,
                        p_enc,
                        cie_vaddr + (pos - cie_off),
                        self.hdr_vaddr,
                        apply_rel=False,
                    )
                elif ch == ord("R"):
                    fde_enc = self._buf[pos]
                    break
                elif ch in (ord("S"), ord("B")):
                    continue
                else:
                    break

        self._cie_fde_enc_cache[cie_vaddr] = fde_enc
        return fde_enc

    def _fde_range(self, fde_vaddr: int) -> Optional[int]:
        fde_off = self._elf.vaddr_to_file_offset(fde_vaddr, 16)
        if fde_off is None:
            return None
        length = struct.unpack_from("<I", self._buf, fde_off)[0]
        pos = fde_off + 4
        if length == 0:
            return None
        if length == 0xFFFFFFFF:
            length = struct.unpack_from("<Q", self._buf, pos)[0]
            pos += 8
        cie_ptr_vaddr = fde_vaddr + (pos - fde_off)
        cie_ptr = struct.unpack_from("<I", self._buf, pos)[0]
        if cie_ptr == 0:
            return None
        cie_vaddr = cie_ptr_vaddr - cie_ptr
        pos += 4

        fde_enc = self._cie_fde_encoding(cie_vaddr)
        _, pos = _read_eh_value(
            self._buf, pos, fde_enc, fde_vaddr + (pos - fde_off), self.hdr_vaddr
        )

        pc_range, _ = _read_eh_value(
            self._buf,
            pos,
            fde_enc & 0x0F,
            fde_vaddr + (pos - fde_off),
            self.hdr_vaddr,
            apply_rel=False,
        )
        if pc_range is None or pc_range <= 0:
            return None
        return int(pc_range)

    def find_function(self, pc: int) -> Optional[Tuple[int, int]]:
        """Map any instruction vaddr `pc` to exact `(fn_start, fn_end)` via binary search."""
        if self.fde_count == 0:
            return None
        lo, hi = 0, self.fde_count - 1
        ans = -1
        while lo <= hi:
            mid = (lo + hi) // 2
            fn_start, _ = self._entry(mid)
            if fn_start <= pc:
                ans = mid
                lo = mid + 1
            else:
                hi = mid - 1
        if ans < 0:
            return None
        fn_start, fde_vaddr = self._entry(ans)
        pc_range = self._fde_range(fde_vaddr)
        if pc_range is not None:
            fn_end = fn_start + pc_range
        elif ans + 1 < self.fde_count:
            fn_end, _ = self._entry(ans + 1)
        else:
            return None
        if fn_start <= pc < fn_end:
            return (fn_start, fn_end)
        return None


class ElfImage:
    """In-memory AArch64 ELF64 image with PT_LOAD, PT_NOTE, .eh_frame_hdr, and xref scan."""

    def __init__(self, data: bytes | bytearray | memoryview):
        self._raw = data
        self.view = memoryview(data)
        if len(self.view) < 64 or self.view[:4] != ELF_MAGIC:
            raise ValueError("not an ELF binary")
        ei_class = self.view[4]
        ei_data = self.view[5]
        if ei_class != ELFCLASS64 or ei_data != ELFDATA2LSB:
            raise ValueError(
                f"expected ELF64 little-endian (got class={ei_class}, data={ei_data})"
            )
        e_machine = struct.unpack_from("<H", self.view, 18)[0]
        if e_machine != EM_AARCH64:
            raise ValueError(f"expected EM_AARCH64 ({EM_AARCH64}), got {e_machine}")

        e_phoff, e_shoff = struct.unpack_from("<QQ", self.view, 32)
        e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(
            "<HHHHH", self.view, 54
        )

        self.segments: List[Segment] = []
        self._note_headers: List[Tuple[int, int]] = []
        eh_hdr_info: Optional[Tuple[int, int, int]] = None

        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            if off + 56 > len(self.view):
                raise ValueError("program header table out of bounds")
            (
                p_type,
                p_flags,
                p_offset,
                p_vaddr,
                _p_paddr,
                p_filesz,
                p_memsz,
                _p_align,
            ) = struct.unpack_from("<IIQQQQQQ", self.view, off)
            if p_type == PT_LOAD:
                self.segments.append(
                    Segment(p_flags, p_offset, p_vaddr, p_filesz, p_memsz)
                )
            elif p_type == PT_NOTE:
                self._note_headers.append((p_offset, p_filesz))
            elif p_type == PT_GNU_EH_FRAME:
                eh_hdr_info = (p_offset, p_vaddr, p_filesz)

        self.build_id: Optional[str] = self._parse_build_id()
        self.eh_frame: Optional[EhFrameIndex] = (
            EhFrameIndex(self, *eh_hdr_info) if eh_hdr_info is not None else None
        )
        self._text_ranges: List[Tuple[int, int, int]] = self._discover_exec_ranges(
            e_shoff, e_shentsize, e_shnum, e_shstrndx
        )
        self._xref_cache: Dict[bytes, List[Xref]] = {}

    def _parse_build_id(self) -> Optional[str]:
        for note_off, note_size in self._note_headers:
            pos = note_off
            end = min(note_off + note_size, len(self.view))
            while pos + 12 <= end:
                namesz, descsz, ntype = struct.unpack_from("<III", self.view, pos)
                name_start = pos + 12
                name_end = name_start + namesz
                desc_start = name_start + ((namesz + 3) & ~3)
                desc_end = desc_start + descsz
                if desc_end > end:
                    break
                if ntype == NT_GNU_BUILD_ID and bytes(
                    self.view[name_start:name_end]
                ) in (
                    b"GNU\x00",
                    b"GNU",
                ):
                    return bytes(self.view[desc_start:desc_end]).hex()
                pos = desc_start + ((descsz + 3) & ~3)
        return None

    def _discover_exec_ranges(
        self, e_shoff: int, e_shentsize: int, e_shnum: int, e_shstrndx: int
    ) -> List[Tuple[int, int, int]]:
        """Prefer `.text` section bounds when section headers are present (faster
        scan when `--no-rosegment` merges `.rodata` + `.text`), falling back to
        executable `PT_LOAD` segments."""
        if e_shoff and e_shnum and e_shentsize >= 64 and e_shstrndx < e_shnum:
            shstr_off_hdr = e_shoff + e_shstrndx * e_shentsize
            if shstr_off_hdr + 64 <= len(self.view):
                _, _, _, _, strtab_off, strtab_sz = struct.unpack_from(
                    "<IIQQQQ", self.view, shstr_off_hdr
                )
                if strtab_off + strtab_sz <= len(self.view):
                    strtab = bytes(self.view[strtab_off : strtab_off + strtab_sz])
                    for i in range(e_shnum):
                        sh_pos = e_shoff + i * e_shentsize
                        if sh_pos + 64 > len(self.view):
                            break
                        sh_name, _sh_type, sh_flags, sh_addr, sh_offset, sh_size = (
                            struct.unpack_from("<IIQQQQ", self.view, sh_pos)
                        )
                        if (sh_flags & 0x4) == 0 or sh_size == 0:
                            continue
                        nul = strtab.find(b"\x00", sh_name)
                        name = strtab[sh_name:nul] if nul >= 0 else b""
                        if name == b".text" and sh_offset + sh_size <= len(self.view):
                            return [(sh_offset, sh_addr, sh_size)]
        ranges = []
        for seg in self.segments:
            if seg.executable and seg.filesz > 0:
                ranges.append((seg.offset, seg.vaddr, seg.filesz))
        return ranges

    def vaddr_to_file_offset(self, vaddr: int, length: int = 1) -> Optional[int]:
        for seg in self.segments:
            if seg.vaddr <= vaddr and vaddr + length <= seg.vaddr + seg.filesz:
                return seg.offset + (vaddr - seg.vaddr)
        return None

    def file_offset_to_vaddr(self, file_off: int, length: int = 1) -> Optional[int]:
        for seg in self.segments:
            if seg.offset <= file_off and file_off + length <= seg.offset + seg.filesz:
                return seg.vaddr + (file_off - seg.offset)
        return None

    def read_u32_vaddr(self, vaddr: int) -> Optional[int]:
        off = self.vaddr_to_file_offset(vaddr, 4)
        if off is None:
            return None
        return struct.unpack_from("<I", self.view, off)[0]

    def read_bytes_vaddr(self, vaddr: int, length: int) -> Optional[bytes]:
        off = self.vaddr_to_file_offset(vaddr, length)
        if off is None:
            return None
        return bytes(self.view[off : off + length])

    def find_function(self, pc: int) -> Optional[Tuple[int, int]]:
        if self.eh_frame is None:
            return None
        return self.eh_frame.find_function(pc)

    def find_string_vaddrs(self, needle: bytes) -> List[int]:
        """Find every occurrence of `needle` across readable PT_LOAD segments."""
        if not needle:
            return []
        raw_bytes = (
            self._raw if isinstance(self._raw, (bytes, bytearray)) else bytes(self.view)
        )
        out: List[int] = []
        seen = set()
        for seg in self.segments:
            if not seg.readable or seg.filesz == 0:
                continue
            start = seg.offset
            end = min(seg.offset + seg.filesz, len(raw_bytes))
            pos = start
            while True:
                idx = raw_bytes.find(needle, pos, end)
                if idx < 0:
                    break
                va = seg.vaddr + (idx - seg.offset)
                if va not in seen:
                    seen.add(va)
                    out.append(va)
                pos = idx + 1
        return out

    def scan_string_xrefs(
        self, needles: Iterable[bytes], followers: int = 64
    ) -> Dict[bytes, List[Xref]]:
        """Single-pass AArch64 `ADRP` + `ADD` xref scan for a batch of anchor strings."""
        requested = list(dict.fromkeys(needles))
        missing = [n for n in requested if n not in self._xref_cache]
        if missing:
            by_page: Dict[int, List[Tuple[bytes, int]]] = {}
            for needle in missing:
                self._xref_cache[needle] = []
                for va in self.find_string_vaddrs(needle):
                    by_page.setdefault(va & ~0xFFF, []).append((needle, va))

            if by_page:
                need_swap = sys.byteorder != "little"
                for file_off, base_vaddr, size in self._text_ranges:
                    aligned_size = size & ~3
                    words = array.array("I")
                    words.frombytes(self.view[file_off : file_off + aligned_size])
                    if need_swap:
                        words.byteswap()
                    n = len(words)
                    for i, w in enumerate(words):
                        if (w & 0x9F000000) != 0x90000000:
                            continue
                        pc = base_vaddr + (i << 2)
                        immlo = (w >> 29) & 0x3
                        immhi = (w >> 5) & 0x7FFFF
                        imm = (immhi << 2) | immlo
                        if imm & 0x100000:
                            imm -= 0x200000
                        page = (pc & ~0xFFF) + (imm << 12)
                        tlist = by_page.get(page)
                        if not tlist:
                            continue
                        rd = w & 0x1F
                        end = min(i + followers, n)
                        for j in range(i + 1, end):
                            w2 = words[j]
                            if (w2 & 0xFFC00000) == 0x91000000 and (
                                (w2 >> 5) & 0x1F
                            ) == rd:
                                resolved = page + ((w2 >> 10) & 0xFFF)
                                for needle, target_va in tlist:
                                    if resolved == target_va:
                                        fn = self.find_function(pc)
                                        self._xref_cache[needle].append(
                                            Xref(
                                                adrp_pc=pc,
                                                add_pc=base_vaddr + (j << 2),
                                                target_vaddr=target_va,
                                                fn_start=fn[0] if fn else None,
                                                fn_end=fn[1] if fn else None,
                                            )
                                        )
                                if (w2 & 0x1F) == rd:
                                    break
                            elif (w2 & 0x9F000000) == 0x90000000 and (w2 & 0x1F) == rd:
                                break

        return {n: list(self._xref_cache[n]) for n in requested}


def _bl_calls_in_range(
    elf: ElfImage, start: int, end: int
) -> List[Tuple[int, int, Optional[int]]]:
    """Return list of `(bl_pc, target_vaddr, next_word)` for all `BL` instructions in `[start, end)`."""
    out: List[Tuple[int, int, Optional[int]]] = []
    off = elf.vaddr_to_file_offset(start, end - start)
    if off is None:
        return out
    n = (end - start) // 4
    for i in range(n):
        w = struct.unpack_from("<I", elf.view, off + i * 4)[0]
        pc = start + i * 4
        target = decode_bl(w, pc)
        if target is not None:
            nxt = (
                struct.unpack_from("<I", elf.view, off + (i + 1) * 4)[0]
                if i + 1 < n
                else None
            )
            out.append((pc, target, nxt))
    return out


def _function_returns_bool_in_w0(elf: ElfImage, fn_start: int, fn_end: int) -> bool:
    """Heuristic sanity check that `[fn_start, fn_end)` ends with `ret` and writes `w0`."""
    size = fn_end - fn_start
    if size < 8:
        return False
    last = elf.read_u32_vaddr(fn_end - 4)
    if last != INSN_RET:
        return False

    tail_start = max(fn_start, fn_end - 32)
    for pc in range(tail_start, fn_end - 4, 4):
        w = elf.read_u32_vaddr(pc)
        if w is None:
            continue

        if (w & 0x1F) == 0 and (
            (w & 0xFFE0FFE0) == 0x2A0003E0
            or (w & 0xFF80001F) == 0x52800000
            or (w & 0xFFC0001F) == 0x39400000
            or (w & 0xFFC0001F) == 0xB9400000
            or (w & 0xFFE00C1F) == 0x1A800400
            or (w & 0xFF80001F) == 0x12000000
        ):
            return True
    return False


def find_staff_devtools(
    elf: ElfImage,
    allow_already_patched: bool = False,
) -> PatchResolution:
    """Resolve the `staff.devtools` predicate (`rel3567ab`: `0x1D57784`).

    Strategy:
      1. Find `"Show Dev Tools Widget"` ADRP+ADD xref -> enclosing function via `.eh_frame_hdr`.
      2. Cross-check `"settings-show-dev-widget"` resolves to the same function if present.
      3. In that function's prologue/early blocks (before the widget string xref),
         find `BL <target>` immediately followed by `CBZ w0, ...` or `TBZ w0, #0, ...`.
      4. Cross-check against `"Staff Settings"` / `"Build Status"` renderer function,
         confirming `<target>` is also called there followed by `TBZ w0, #0` / `CBZ w0`.
      5. Validate `<target>` is a true `.eh_frame_hdr` function start with a standard
         frame prologue (or already `mov w0, #1; ret` when `allow_already_patched=True`).
    """
    if elf.eh_frame is None:
        raise FinderError(
            "staff.devtools: ELF is missing PT_GNU_EH_FRAME (.eh_frame_hdr)"
        )

    xrefs = elf.scan_string_xrefs(
        (
            b"Show Dev Tools Widget",
            b"settings-show-dev-widget",
            b"Staff Settings",
            b"Build Status",
        )
    )

    widget_xrefs = xrefs[b"Show Dev Tools Widget"]
    if not widget_xrefs:
        raise FinderError(
            'staff.devtools: no ADRP+ADD xref found for "Show Dev Tools Widget"'
        )

    widget_fns = sorted(
        {(x.fn_start, x.fn_end) for x in widget_xrefs if x.fn_start is not None}
    )
    if len(widget_fns) != 1:
        raise FinderError(
            f'staff.devtools: expected 1 enclosing function for "Show Dev Tools Widget", '
            f"found {len(widget_fns)}: {[(hex(s), hex(e)) for s, e in widget_fns]}"
        )
    w_start, w_end = widget_fns[0]
    first_widget_pc = min(x.adrp_pc for x in widget_xrefs if x.fn_start == w_start)

    key_xrefs = xrefs[b"settings-show-dev-widget"]
    if key_xrefs:
        key_fns = {(x.fn_start, x.fn_end) for x in key_xrefs if x.fn_start is not None}
        if (w_start, w_end) not in key_fns:
            raise FinderError(
                f'staff.devtools: "settings-show-dev-widget" functions {key_fns} disagree '
                f'with "Show Dev Tools Widget" function ({w_start:#x}..{w_end:#x})'
            )

    candidates: List[int] = []
    for _bl_pc, target, nxt in _bl_calls_in_range(elf, w_start, first_widget_pc):
        if nxt is not None and (is_cbz_w0(nxt) or is_tbz_w0_bit0(nxt)):
            if target not in candidates:
                candidates.append(target)

    if not candidates:
        raise FinderError(
            f"staff.devtools: no `BL <target>; CBZ/TBZ w0` gate found in "
            f"{w_start:#x}..{first_widget_pc:#x}"
        )

    staff_xrefs = xrefs[b"Staff Settings"] or xrefs[b"Build Status"]
    staff_fns = sorted(
        {(x.fn_start, x.fn_end) for x in staff_xrefs if x.fn_start is not None}
    )
    if staff_fns:
        confirmed: List[int] = []
        for sf_start, sf_end in staff_fns:
            calls = _bl_calls_in_range(elf, sf_start, sf_end)
            called_targets = {t for _, t, _ in calls}
            gated_targets = {
                t
                for _, t, nxt in calls
                if nxt is not None and (is_cbz_w0(nxt) or is_tbz_w0_bit0(nxt))
            }
            for cand in candidates:
                if cand in gated_targets and cand not in confirmed:
                    confirmed.append(cand)
            if not confirmed:
                for cand in candidates:
                    if cand in called_targets and cand not in confirmed:
                        confirmed.append(cand)
        if not confirmed:
            raise FinderError(
                f"staff.devtools: candidate(s) {[hex(c) for c in candidates]} from widget fn "
                f"{w_start:#x} not confirmed in Staff Settings fn(s) "
                f"{[(hex(s), hex(e)) for s, e in staff_fns]}"
            )
        candidates = confirmed

    if len(candidates) != 1:
        raise FinderError(
            f"staff.devtools: ambiguous predicate candidates: {[hex(c) for c in candidates]}"
        )

    target = candidates[0]
    target_fn = elf.find_function(target)
    if target_fn is None or target_fn[0] != target:
        raise FinderError(
            f"staff.devtools: resolved target {target:#x} is not an .eh_frame_hdr function start "
            f"(got {target_fn})"
        )
    t_start, t_end = target_fn
    if t_end - t_start < 16:
        raise FinderError(
            f"staff.devtools: target function {t_start:#x}..{t_end:#x} unexpectedly small"
        )

    flag_vaddr = find_bss_flag_slot(elf, index=0)
    patched_stub = encode_adrp_ldrb_ret(t_start, flag_vaddr)

    file_off = elf.vaddr_to_file_offset(t_start, 12)
    if file_off is None:
        raise FinderError(
            f"staff.devtools: target {t_start:#x} (12B) not mapped in PT_LOAD"
        )
    cur = bytes(elf.view[file_off : file_off + 12])
    w0, w1, _w2 = struct.unpack("<III", cur)

    already_bss_stub = (
        cur == patched_stub or decode_adrp_ldrb_ret(cur, t_start) is not None
    )
    already_legacy = cur[:8] == BYTES_MOV_W0_1_RET
    if already_bss_stub or already_legacy:
        if not allow_already_patched:
            raise FinderError(
                f"staff.devtools: target @ {file_off:#x} is already patched ({cur.hex()})"
            )
        status = "ARMED"
    else:
        if not is_standard_prologue(w0, w1):
            raise FinderError(
                f"staff.devtools: target @ {file_off:#x} does not begin with a standard frame "
                f"prologue (words={w0:#010x}, {w1:#010x})"
            )
        if not _function_returns_bool_in_w0(elf, t_start, t_end):
            raise FinderError(
                f"staff.devtools: target @ {file_off:#x}..{t_end:#x} failed bool-return epilogue check"
            )
        status = "VANILLA"

    edit = PatchEdit(
        tag="staff.devtools",
        file_offset=file_off,
        vaddr=t_start,
        orig=cur,
        patched=patched_stub,
        status=status,
        detail=f"caller={w_start:#x}, target={t_start:#x}..{t_end:#x}, bss_flag={flag_vaddr:#x}",
    )
    return PatchResolution(
        name="staff.devtools",
        fn_start=t_start,
        fn_end=t_end,
        edits=(edit,),
        detail=f"gate in {w_start:#x} -> predicate {t_start:#x}..{t_end:#x} (bss_flag={flag_vaddr:#x})",
        runtime_flags=(("staff.devtools", flag_vaddr, 1),),
    )


def find_release_access(
    elf: ElfImage,
    allow_already_patched: bool = False,
) -> PatchResolution:
    """Resolve the `experiment.release_access` return sites (`rel3567ab`: `0x2A7B40C` & `0x2A7B414`).

    Strategy:
      1. Find all ADRP+ADD xrefs to `"2026-11-release-app-access"` and their enclosing
         functions via `.eh_frame_hdr`.
      2. Locate the single helper function where, after the string xref:
         - `cmp x*, #1` is immediately followed by `cset w0, eq` (site 1)
         - `cbz x0, <fallback>` jumps to `mov w0, #2` (site 2) before `ret`.
      3. Both sites are rewritten to `mov w0, #1` (`20 00 80 52`).
    """
    if elf.eh_frame is None:
        raise FinderError(
            "experiment.release_access: ELF is missing PT_GNU_EH_FRAME (.eh_frame_hdr)"
        )

    xrefs = elf.scan_string_xrefs((b"2026-11-release-app-access",))[
        b"2026-11-release-app-access"
    ]
    if not xrefs:
        raise FinderError(
            'experiment.release_access: no ADRP+ADD xref found for "2026-11-release-app-access"'
        )

    fn_xrefs: Dict[Tuple[int, int], int] = {}
    for x in xrefs:
        if x.fn_start is None or x.fn_end is None:
            continue
        key = (x.fn_start, x.fn_end)
        if key not in fn_xrefs or x.adrp_pc < fn_xrefs[key]:
            fn_xrefs[key] = x.adrp_pc

    matches: List[PatchResolution] = []
    for (fn_start, fn_end), xref_pc in sorted(fn_xrefs.items()):

        off = elf.vaddr_to_file_offset(xref_pc, fn_end - xref_pc)
        if off is None:
            continue
        n = (fn_end - xref_pc) // 4
        if n < 6:
            continue
        words = [struct.unpack_from("<I", elf.view, off + i * 4)[0] for i in range(n)]
        if words[-1] != INSN_RET:
            continue

        cbz_targets = set()
        for i, w in enumerate(words):
            t = decode_cbz_x0(w, xref_pc + i * 4)
            if t is not None and xref_pc < t < fn_end:
                cbz_targets.add(t)

        site1_pcs: List[Tuple[int, str]] = []
        for i in range(n - 1):
            if is_cmp_x_imm1(words[i]):
                nxt = words[i + 1]
                pc1 = xref_pc + (i + 1) * 4
                if nxt == INSN_CSET_W0_EQ:
                    site1_pcs.append((pc1, "VANILLA"))
                elif allow_already_patched and nxt == INSN_MOV_W0_1:
                    site1_pcs.append((pc1, "ARMED"))

        if len(site1_pcs) != 1:
            continue
        site1_pc, site1_status = site1_pcs[0]

        site2_pcs: List[Tuple[int, str]] = []
        for i, w in enumerate(words):
            pc2 = xref_pc + i * 4
            if pc2 <= site1_pc:
                continue
            if w == INSN_MOV_W0_2:
                site2_pcs.append((pc2, "VANILLA"))
            elif allow_already_patched and w == INSN_MOV_W0_1 and pc2 in cbz_targets:
                site2_pcs.append((pc2, "ARMED"))

        if len(site2_pcs) != 1:
            continue
        site2_pc, site2_status = site2_pcs[0]

        off1 = elf.vaddr_to_file_offset(site1_pc, 4)
        off2 = elf.vaddr_to_file_offset(site2_pc, 4)
        if off1 is None or off2 is None:
            continue

        e1 = PatchEdit(
            tag="experiment.release_access.1",
            file_offset=off1,
            vaddr=site1_pc,
            orig=BYTES_CSET_W0_EQ,
            patched=BYTES_MOV_W0_1,
            status=site1_status,
            detail=f"fn={fn_start:#x}..{fn_end:#x} (cset w0, eq -> mov w0, #1)",
        )
        e2 = PatchEdit(
            tag="experiment.release_access.2",
            file_offset=off2,
            vaddr=site2_pc,
            orig=BYTES_MOV_W0_2,
            patched=BYTES_MOV_W0_1,
            status=site2_status,
            detail=f"fn={fn_start:#x}..{fn_end:#x} (mov w0, #2 -> mov w0, #1)",
        )
        matches.append(
            PatchResolution(
                name="experiment.release_access",
                fn_start=fn_start,
                fn_end=fn_end,
                edits=(e1, e2),
                detail=f"helper {fn_start:#x}..{fn_end:#x} (xref {xref_pc:#x})",
            )
        )

    if not matches:
        for (fn_start, fn_end), xref_pc in sorted(fn_xrefs.items()):
            if xref_pc - fn_start < 16:
                continue
            pre_len = xref_pc - fn_start
            off = elf.vaddr_to_file_offset(fn_start, pre_len)
            if off is None:
                continue
            pre_n = pre_len // 4
            pre_words = [struct.unpack_from("<I", elf.view, off + i * 4)[0] for i in range(pre_n)]
            for i in range(pre_n - 4):
                if pre_words[i] != INSN_TST_W0_FF:
                    continue
                branch_pc = fn_start + (i + 1) * 4
                bw = pre_words[i + 1]
                mw = pre_words[i + 2]
                if (mw & 0xFFFFFFE0) != 0x52800040:
                    continue
                rd = mw & 0x1F
                has_strb = any(
                    (pre_words[j] & 0xFFFFFC1F) == (0x39000000 | rd)
                    for j in range(i + 3, min(pre_n, i + 6))
                )
                if not has_strb:
                    continue
                is_beq_to_xref = False
                if (bw & 0xFF00001F) == 0x54000000:
                    imm19 = (bw >> 5) & 0x7FFFF
                    if imm19 & 0x40000:
                        imm19 -= 0x80000
                    if branch_pc + (imm19 << 2) == xref_pc:
                        is_beq_to_xref = True
                if is_beq_to_xref:
                    status = "VANILLA"
                    orig_bytes = struct.pack("<I", bw)
                elif allow_already_patched and bw == INSN_NOP:
                    status = "ARMED"
                    delta = (xref_pc - branch_pc) >> 2
                    orig_bytes = struct.pack("<I", 0x54000000 | ((delta & 0x7FFFF) << 5))
                else:
                    continue
                b_off = elf.vaddr_to_file_offset(branch_pc, 4)
                if b_off is None:
                    continue
                e = PatchEdit(
                    tag="experiment.release_access.1",
                    file_offset=b_off,
                    vaddr=branch_pc,
                    orig=orig_bytes,
                    patched=BYTES_NOP,
                    status=status,
                    detail=f"fn={fn_start:#x}..{fn_end:#x} (b.eq {xref_pc:#x} -> nop; always return tag=2 Allowed)",
                )
                matches.append(
                    PatchResolution(
                        name="experiment.release_access",
                        fn_start=fn_start,
                        fn_end=fn_end,
                        edits=(e,),
                        detail=f"access_gate v2 {fn_start:#x}..{fn_end:#x} (xref {xref_pc:#x})",
                    )
                )
                break

    if len(matches) != 1:
        raise FinderError(
            f"experiment.release_access: expected 1 variant helper function among "
            f"{[(hex(s), hex(e)) for s, e in sorted(fn_xrefs)]}, found {len(matches)}"
        )

    res = matches[0]
    if not allow_already_patched and any(e.status != "VANILLA" for e in res.edits):
        raise FinderError(
            f"experiment.release_access: site(s) in {res.fn_start:#x} are already patched"
        )
    return res


def find_all_active_patches(
    elf: ElfImage, allow_already_patched: bool = False
) -> Dict[str, Tuple[Optional[PatchResolution], Optional[str]]]:
    """Run one batched `.text` xref scan and resolve every active auto-finder."""
    elf.scan_string_xrefs(ALL_FINDER_ANCHORS)
    results: Dict[str, Tuple[Optional[PatchResolution], Optional[str]]] = {}
    for name, fn in (
        ("staff.devtools", find_staff_devtools),
        ("experiment.release_access", find_release_access),
    ):
        try:
            results[name] = (fn(elf, allow_already_patched=allow_already_patched), None)
        except Exception as exc:
            results[name] = (None, str(exc))
    return results
