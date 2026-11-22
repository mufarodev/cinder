// Pure-JS AArch64 ELF64 analyzer and dynamic patcher for `liblibdiscord_wasm_host.so`.
// Port of `mobile/cinder-mobile/installer/elf_finder.py` + `apply_host_so_patches`,
// with dual `experiment.release_access` resolution supporting both standalone Phoenix APKs
// and Play Store Discord Alpha builds (356207+).

const ELF_MAGIC = [0x7f, 0x45, 0x4c, 0x46];
const PT_LOAD = 1;
const PT_NOTE = 4;
const PT_GNU_EH_FRAME = 0x6474e550;
const PF_X = 0x1;
const PF_W = 0x2;
const PF_R = 0x4;
const NT_GNU_BUILD_ID = 3;
const SHT_RELA = 4;
const R_AARCH64_RELATIVE = 1027;

const DW_EH_PE_OMIT = 0xff;
const DW_EH_PE_ABSPTR = 0x00;
const DW_EH_PE_ULEB128 = 0x01;
const DW_EH_PE_UDATA2 = 0x02;
const DW_EH_PE_UDATA4 = 0x03;
const DW_EH_PE_UDATA8 = 0x04;
const DW_EH_PE_SLEB128 = 0x09;
const DW_EH_PE_SDATA2 = 0x0a;
const DW_EH_PE_SDATA4 = 0x0b;
const DW_EH_PE_SDATA8 = 0x0c;
const DW_EH_PE_PCREL = 0x10;
const DW_EH_PE_DATAREL = 0x30;

const INSN_MOV_W0_1 = 0x52800020;
const INSN_MOV_W0_2 = 0x52800040;
const INSN_CSET_W0_EQ = 0x1a9f17e0;
const INSN_RET = 0xd65f03c0;

const te = new TextEncoder();
const td = new TextDecoder();

function hex(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i++) s += u8[i].toString(16).padStart(2, '0');
  return s;
}

function packU32s(...words) {
  const b = new Uint8Array(words.length * 4);
  const v = new DataView(b.buffer);
  for (let i = 0; i < words.length; i++) v.setUint32(i * 4, words[i] >>> 0, true);
  return b;
}

function readUleb128(u8, pos) {
  let res = 0;
  let shift = 0;
  while (pos < u8.length) {
    const b = u8[pos++];
    res += (b & 0x7f) * (2 ** shift);
    if ((b & 0x80) === 0) return [res, pos];
    shift += 7;
  }
  throw new Error('truncated ULEB128 in .eh_frame');
}

function readSleb128(u8, pos) {
  let res = 0n;
  let shift = 0n;
  while (pos < u8.length) {
    const b = u8[pos++];
    res |= BigInt(b & 0x7f) << shift;
    shift += 7n;
    if ((b & 0x80) === 0) {
      if (b & 0x40) res -= 1n << shift;
      return [Number(res), pos];
    }
  }
  throw new Error('truncated SLEB128 in .eh_frame');
}

function readEhValue(u8, v, pos, enc, fieldVaddr, datarelBase, applyRel = true) {
  if (enc === DW_EH_PE_OMIT) return [null, pos];
  const fmt = enc & 0x0f;
  const rel = enc & 0x70;
  let val;
  let nextPos = pos;

  if (fmt === DW_EH_PE_ABSPTR || fmt === DW_EH_PE_UDATA8) {
    val = Number(v.getBigUint64(pos, true));
    nextPos = pos + 8;
  } else if (fmt === DW_EH_PE_SDATA8) {
    val = Number(v.getBigInt64(pos, true));
    nextPos = pos + 8;
  } else if (fmt === DW_EH_PE_UDATA4) {
    val = v.getUint32(pos, true);
    nextPos = pos + 4;
  } else if (fmt === DW_EH_PE_SDATA4) {
    val = v.getInt32(pos, true);
    nextPos = pos + 4;
  } else if (fmt === DW_EH_PE_UDATA2) {
    val = v.getUint16(pos, true);
    nextPos = pos + 2;
  } else if (fmt === DW_EH_PE_SDATA2) {
    val = v.getInt16(pos, true);
    nextPos = pos + 2;
  } else if (fmt === DW_EH_PE_ULEB128) {
    [val, nextPos] = readUleb128(u8, pos);
  } else if (fmt === DW_EH_PE_SLEB128) {
    [val, nextPos] = readSleb128(u8, pos);
  } else {
    throw new Error(`unsupported DWARF pointer encoding 0x${enc.toString(16)}`);
  }

  if (applyRel && val !== 0) {
    if (rel === DW_EH_PE_PCREL) val += fieldVaddr;
    else if (rel === DW_EH_PE_DATAREL) val += datarelBase;
    else if (rel !== 0) throw new Error(`unsupported DWARF relocation 0x${enc.toString(16)}`);
  }
  return [val, nextPos];
}

// --- AArch64 decoders / encoders --------------------------------------------------------

function decodeAdrp(w, pc) {
  if (((w & 0x9f000000) >>> 0) !== 0x90000000) return null;
  const immlo = (w >>> 29) & 0x3;
  const immhi = (w >>> 5) & 0x7ffff;
  let imm = (immhi << 2) | immlo;
  if (imm & 0x100000) imm -= 0x200000;
  return Math.floor(pc / 4096) * 4096 + imm * 4096;
}

function decodeBl(w, pc) {
  if (((w & 0xfc000000) >>> 0) !== 0x94000000) return null;
  let imm = w & 0x03ffffff;
  if (imm & 0x02000000) imm -= 0x04000000;
  return pc + imm * 4;
}

function decodeCbzX0(w, pc) {
  if (((w & 0xff00001f) >>> 0) !== 0xb4000000) return null;
  let imm = (w >>> 5) & 0x7ffff;
  if (imm & 0x40000) imm -= 0x80000;
  return pc + imm * 4;
}

const isCbzW0 = (w) => ((w & 0xff00001f) >>> 0) === 0x34000000;
const isTbzW0Bit0 = (w) => ((w & 0xfff8001f) >>> 0) === 0x36000000;
const isCmpXImm1 = (w) => ((w & 0xfffffc1f) >>> 0) === 0xf100041f;

function isStandardPrologue(w0, w1) {
  if (((w0 & 0xffc003ff) >>> 0) === 0xd10003ff && ((w1 & 0xffc07fff) >>> 0) === 0xa9007bfd) {
    return true;
  }
  if (((w0 & 0xffc07fff) >>> 0) === 0xa9807bfd) {
    if (w1 === 0x910003fd) return true;
    if (((w1 & 0xffc003e0) >>> 0) === 0xa90003e0 || ((w1 & 0xffc003e0) >>> 0) === 0xf90003e0) {
      return true;
    }
  }
  return false;
}

function encodeAdrpLdrbRet(predVaddr, flagVaddr) {
  if ((predVaddr & 3) !== 0) throw new Error(`predicate vaddr 0x${predVaddr.toString(16)} unaligned`);
  const predPage = Math.floor(predVaddr / 4096);
  const flagPage = Math.floor(flagVaddr / 4096);
  const deltaPages = flagPage - predPage;
  if (deltaPages < -0x100000 || deltaPages > 0x0fffff) {
    throw new Error(`ADRP page delta out of range: 0x${predVaddr.toString(16)} -> 0x${flagVaddr.toString(16)}`);
  }
  const imm21 = deltaPages & 0x1fffff;
  const immlo = imm21 & 0x3;
  const immhi = (imm21 >>> 2) & 0x7ffff;
  const adrp = (0x90000000 | (immlo << 29) | (immhi << 5) | 8) >>> 0;
  const flagOff = flagVaddr & 0xfff;
  const ldrb = (0x39400000 | (flagOff << 10) | (8 << 5) | 0) >>> 0;
  return packU32s(adrp, ldrb, INSN_RET);
}

// --- .eh_frame_hdr binary search index --------------------------------------------------

class EhFrameIndex {
  constructor(elf, hdrOffset, hdrVaddr, hdrSize) {
    this.elf = elf;
    this.u8 = elf.u8;
    this.v = elf.v;
    this.hdrOffset = hdrOffset;
    this.hdrVaddr = hdrVaddr;
    this.hdrSize = hdrSize;
    this._cieEncCache = new Map();

    if (hdrSize < 12) throw new Error(`.eh_frame_hdr too small (${hdrSize} B)`);
    const ver = this.u8[hdrOffset];
    const ehPtrEnc = this.u8[hdrOffset + 1];
    const fdeCntEnc = this.u8[hdrOffset + 2];
    const tabEnc = this.u8[hdrOffset + 3];
    if (ver !== 1) throw new Error(`unsupported .eh_frame_hdr version ${ver}`);

    let pos = hdrOffset + 4;
    [, pos] = readEhValue(this.u8, this.v, pos, ehPtrEnc, hdrVaddr + (pos - hdrOffset), hdrVaddr);
    let fdeCnt;
    [fdeCnt, pos] = readEhValue(this.u8, this.v, pos, fdeCntEnc, hdrVaddr + (pos - hdrOffset), hdrVaddr, false);
    if (fdeCnt == null || fdeCnt < 0) throw new Error('missing FDE count in .eh_frame_hdr');

    this.tableEnc = tabEnc;
    this.tableOffset = pos;
    this.fdeCount = fdeCnt;

    if (tabEnc === (DW_EH_PE_DATAREL | DW_EH_PE_SDATA4)) {
      this.entrySize = 8;
      this.mode = 'datarel_i32';
    } else if (tabEnc === (DW_EH_PE_DATAREL | DW_EH_PE_UDATA4)) {
      this.entrySize = 8;
      this.mode = 'datarel_u32';
    } else if (tabEnc === DW_EH_PE_ABSPTR || tabEnc === DW_EH_PE_UDATA8) {
      this.entrySize = 16;
      this.mode = 'abs_u64';
    } else {
      throw new Error(`unsupported .eh_frame_hdr table_enc 0x${tabEnc.toString(16)}`);
    }
  }

  _entry(idx) {
    const o = this.tableOffset + idx * this.entrySize;
    if (this.mode === 'datarel_i32') {
      return [this.hdrVaddr + this.v.getInt32(o, true), this.hdrVaddr + this.v.getInt32(o + 4, true)];
    }
    if (this.mode === 'datarel_u32') {
      return [this.hdrVaddr + this.v.getUint32(o, true), this.hdrVaddr + this.v.getUint32(o + 4, true)];
    }
    return [Number(this.v.getBigUint64(o, true)), Number(this.v.getBigUint64(o + 8, true))];
  }

  _cieFdeEncoding(cieVaddr) {
    if (this._cieEncCache.has(cieVaddr)) return this._cieEncCache.get(cieVaddr);
    const cieOff = this.elf.vaddrToFileOffset(cieVaddr, 16);
    if (cieOff === null) return DW_EH_PE_PCREL | DW_EH_PE_SDATA4;

    let length = this.v.getUint32(cieOff, true);
    let pos = cieOff + 4;
    if (length === 0xffffffff) {
      length = Number(this.v.getBigUint64(pos, true));
      pos += 8;
    }
    pos += 4; // cie_id == 0
    const ver = this.u8[pos++];
    const augStart = pos;
    while (pos < this.u8.length && this.u8[pos] !== 0) pos++;
    const aug = this.u8.subarray(augStart, pos);
    pos++; // NUL

    if (ver >= 4) pos += 2;
    [, pos] = readUleb128(this.u8, pos);
    [, pos] = readSleb128(this.u8, pos);
    if (ver === 1) pos += 1;
    else [, pos] = readUleb128(this.u8, pos);

    let fdeEnc = DW_EH_PE_ABSPTR;
    if (aug.length > 0 && aug[0] === 0x7a /* 'z' */) {
      [, pos] = readUleb128(this.u8, pos);
      for (let i = 1; i < aug.length; i++) {
        const ch = aug[i];
        if (ch === 0x4c /* 'L' */) pos += 1;
        else if (ch === 0x50 /* 'P' */) {
          const pEnc = this.u8[pos++];
          [, pos] = readEhValue(this.u8, this.v, pos, pEnc, cieVaddr + (pos - cieOff), this.hdrVaddr, false);
        } else if (ch === 0x52 /* 'R' */) {
          fdeEnc = this.u8[pos];
          break;
        } else if (ch === 0x53 || ch === 0x42) {
          continue;
        } else {
          break;
        }
      }
    }
    this._cieEncCache.set(cieVaddr, fdeEnc);
    return fdeEnc;
  }

  _fdeRange(fdeVaddr) {
    const fdeOff = this.elf.vaddrToFileOffset(fdeVaddr, 16);
    if (fdeOff === null) return null;
    let length = this.v.getUint32(fdeOff, true);
    let pos = fdeOff + 4;
    if (length === 0) return null;
    if (length === 0xffffffff) {
      length = Number(this.v.getBigUint64(pos, true));
      pos += 8;
    }
    const ciePtrVaddr = fdeVaddr + (pos - fdeOff);
    const ciePtr = this.v.getUint32(pos, true);
    if (ciePtr === 0) return null;
    const cieVaddr = ciePtrVaddr - ciePtr;
    pos += 4;

    const fdeEnc = this._cieFdeEncoding(cieVaddr);
    [, pos] = readEhValue(this.u8, this.v, pos, fdeEnc, fdeVaddr + (pos - fdeOff), this.hdrVaddr);
    const [pcRange] = readEhValue(
      this.u8,
      this.v,
      pos,
      fdeEnc & 0x0f,
      fdeVaddr + (pos - fdeOff),
      this.hdrVaddr,
      false,
    );
    return pcRange != null && pcRange > 0 ? pcRange : null;
  }

  findFunction(pc) {
    if (this.fdeCount === 0) return null;
    let lo = 0;
    let hi = this.fdeCount - 1;
    let ans = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1;
      const [fnStart] = this._entry(mid);
      if (fnStart <= pc) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (ans < 0) return null;
    const [fnStart, fdeVaddr] = this._entry(ans);
    const pcRange = this._fdeRange(fdeVaddr);
    let fnEnd;
    if (pcRange !== null) fnEnd = fnStart + pcRange;
    else if (ans + 1 < this.fdeCount) [fnEnd] = this._entry(ans + 1);
    else return null;
    return pc >= fnStart && pc < fnEnd ? [fnStart, fnEnd] : null;
  }
}

// --- ELF64 AArch64 image ----------------------------------------------------------------

export class ElfImage {
  constructor(u8) {
    this.u8 = u8;
    this.v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    if (u8.length < 64 || ELF_MAGIC.some((b, i) => u8[i] !== b)) {
      throw new Error('not a valid ELF binary');
    }
    if (u8[4] !== 2 || u8[5] !== 1) throw new Error('expected ELF64 little-endian');
    const machine = this.v.getUint16(18, true);
    if (machine !== 183) throw new Error(`expected EM_AARCH64 (183), got ${machine}`);

    const phoff = Number(this.v.getBigUint64(32, true));
    const shoff = Number(this.v.getBigUint64(40, true));
    const phentsize = this.v.getUint16(54, true);
    const phnum = this.v.getUint16(56, true);
    const shentsize = this.v.getUint16(58, true);
    const shnum = this.v.getUint16(60, true);
    const shstrndx = this.v.getUint16(62, true);

    this.segments = [];
    this._notes = [];
    let ehInfo = null;

    for (let i = 0; i < phnum; i++) {
      const o = phoff + i * phentsize;
      const pType = this.v.getUint32(o, true);
      const pFlags = this.v.getUint32(o + 4, true);
      const pOffset = Number(this.v.getBigUint64(o + 8, true));
      const pVaddr = Number(this.v.getBigUint64(o + 16, true));
      const pFilesz = Number(this.v.getBigUint64(o + 32, true));
      const pMemsz = Number(this.v.getBigUint64(o + 40, true));
      if (pType === PT_LOAD) {
        this.segments.push({ flags: pFlags, offset: pOffset, vaddr: pVaddr, filesz: pFilesz, memsz: pMemsz });
      } else if (pType === PT_NOTE) {
        this._notes.push([pOffset, pFilesz]);
      } else if (pType === PT_GNU_EH_FRAME) {
        ehInfo = [pOffset, pVaddr, pFilesz];
      }
    }

    this.sections = this._parseSections(shoff, shentsize, shnum, shstrndx);
    this.buildId = this._parseBuildId();
    this.ehFrame = ehInfo ? new EhFrameIndex(this, ...ehInfo) : null;
    this.textRanges = this._discoverExecRanges();
    this._xrefCache = new Map();
    this._relocTargets = null;
  }

  _parseSections(shoff, shentsize, shnum, shstrndx) {
    const map = {};
    if (!shoff || !shnum || shentsize < 64 || shstrndx >= shnum) return map;
    const strHdr = shoff + shstrndx * shentsize;
    if (strHdr + 64 > this.u8.length) return map;
    const strOff = Number(this.v.getBigUint64(strHdr + 24, true));
    const strSz = Number(this.v.getBigUint64(strHdr + 32, true));
    if (strOff + strSz > this.u8.length) return map;
    const strtab = this.u8.subarray(strOff, strOff + strSz);

    for (let i = 0; i < shnum; i++) {
      const o = shoff + i * shentsize;
      if (o + 64 > this.u8.length) break;
      const shName = this.v.getUint32(o, true);
      const shType = this.v.getUint32(o + 4, true);
      const shFlags = Number(this.v.getBigUint64(o + 8, true));
      const shAddr = Number(this.v.getBigUint64(o + 16, true));
      const shOffset = Number(this.v.getBigUint64(o + 24, true));
      const shSize = Number(this.v.getBigUint64(o + 32, true));
      let end = shName;
      while (end < strtab.length && strtab[end] !== 0) end++;
      const name = td.decode(strtab.subarray(shName, end));
      map[name] = { name, type: shType, flags: shFlags, addr: shAddr, offset: shOffset, size: shSize };
    }
    return map;
  }

  _parseBuildId() {
    for (const [noteOff, noteSize] of this._notes) {
      let pos = noteOff;
      const end = Math.min(noteOff + noteSize, this.u8.length);
      while (pos + 12 <= end) {
        const namesz = this.v.getUint32(pos, true);
        const descsz = this.v.getUint32(pos + 4, true);
        const ntype = this.v.getUint32(pos + 8, true);
        const nameStart = pos + 12;
        const descStart = nameStart + ((namesz + 3) & ~3);
        const descEnd = descStart + descsz;
        if (descEnd > end) break;
        const name = td.decode(this.u8.subarray(nameStart, nameStart + namesz)).replace(/\0+$/, '');
        if (ntype === NT_GNU_BUILD_ID && name === 'GNU') {
          return hex(this.u8.subarray(descStart, descEnd));
        }
        pos = descStart + ((descsz + 3) & ~3);
      }
    }
    return '';
  }

  _discoverExecRanges() {
    const text = this.sections['.text'];
    if (text && (text.flags & 0x4) && text.size > 0 && text.offset + text.size <= this.u8.length) {
      return [[text.offset, text.addr, text.size]];
    }
    return this.segments
      .filter((s) => (s.flags & PF_X) && s.filesz > 0)
      .map((s) => [s.offset, s.vaddr, s.filesz]);
  }

  vaddrToFileOffset(vaddr, length = 1) {
    for (const s of this.segments) {
      if (vaddr >= s.vaddr && vaddr + length <= s.vaddr + s.filesz) {
        return s.offset + (vaddr - s.vaddr);
      }
    }
    return null;
  }

  readU32Vaddr(vaddr) {
    const off = this.vaddrToFileOffset(vaddr, 4);
    return off === null ? null : this.v.getUint32(off, true);
  }

  readU64Vaddr(vaddr) {
    const off = this.vaddrToFileOffset(vaddr, 8);
    return off === null ? null : Number(this.v.getBigUint64(off, true));
  }

  findFunction(pc) {
    return this.ehFrame ? this.ehFrame.findFunction(pc) : null;
  }

  findStringVaddrs(needleStr) {
    const needle = te.encode(needleStr);
    const out = [];
    const seen = new Set();
    const nLen = needle.length;
    const first = needle[0];

    for (const seg of this.segments) {
      if (!(seg.flags & PF_R) || seg.filesz === 0) continue;
      const start = seg.offset;
      const end = Math.min(seg.offset + seg.filesz, this.u8.length) - nLen;
      for (let i = start; i <= end; ) {
        const idx = this.u8.indexOf(first, i);
        if (idx < 0 || idx > end) break;
        let match = true;
        for (let j = 1; j < nLen; j++) {
          if (this.u8[idx + j] !== needle[j]) {
            match = false;
            break;
          }
        }
        if (match) {
          const va = seg.vaddr + (idx - seg.offset);
          if (!seen.has(va)) {
            seen.add(va);
            out.push(va);
          }
        }
        i = idx + 1;
      }
    }
    return out;
  }

  /** Map target vaddr -> list of slot vaddrs populated via R_AARCH64_RELATIVE relocations (RELA + Android APS2). */
  relocSlotsTo(targetVaddr) {
    if (!this._relocTargets) {
      const m = new Map();
      const addSlot = (slot, addend) => {
        let list = m.get(addend);
        if (!list) {
          list = [];
          m.set(addend, list);
        }
        list.push(slot);
      };
      for (const sec of Object.values(this.sections)) {
        if (sec.type === 0x60000002 /* SHT_ANDROID_RELA */ && sec.size >= 4) {
          const d = this.u8;
          const off = sec.offset;
          if (d[off] === 0x41 && d[off + 1] === 0x50 && d[off + 2] === 0x53 && d[off + 3] === 0x32) {
            let p = off + 4;
            const sl = () => {
              let r = 0n, sh = 0n, b;
              do {
                b = d[p++];
                r |= BigInt(b & 0x7f) << sh;
                sh += 7n;
              } while (b & 0x80);
              if (b & 0x40) r -= 1n << sh;
              return r;
            };
            const total = Number(sl());
            let roff = sl();
            let addend = 0n;
            let cnt = 0;
            while (cnt < total) {
              const gs = Number(sl());
              const fl = Number(sl());
              const god = (fl & 2) ? sl() : 0n;
              let info = (fl & 1) ? sl() : 0n;
              if ((fl & 8) && (fl & 4)) addend += sl();
              for (let k = 0; k < gs; k++, cnt++) {
                roff += (fl & 2) ? god : sl();
                if (!(fl & 1)) info = sl();
                if ((fl & 8) && !(fl & 4)) addend += sl();
                if (Number(info & 0xffffffffn) === R_AARCH64_RELATIVE) {
                  addSlot(Number(roff), Number((fl & 8) ? addend : 0n));
                }
              }
            }
          }
        } else if (sec.type === SHT_RELA) {
          for (let o = sec.offset; o + 24 <= sec.offset + sec.size; o += 24) {
            const rInfo = Number(this.v.getBigUint64(o + 8, true) & 0xffffffffn);
            if (rInfo !== R_AARCH64_RELATIVE) continue;
            addSlot(Number(this.v.getBigUint64(o, true)), Number(this.v.getBigInt64(o + 16, true)));
          }
        }
      }
      this._relocTargets = m;
    }
    return this._relocTargets.get(targetVaddr) || [];
  }

  /**
   * Single-pass AArch64 `ADRP + ADD` (and `ADRP + LDR` for table pointers) xref scanner
   * for a set of target vaddr groups: `Map<key, number[]>`.
   */
  scanTargetXrefs(targetMap, followers = 64) {
    const results = new Map();
    const byPage = new Map();

    for (const [key, vaddrs] of targetMap.entries()) {
      results.set(key, []);
      for (const va of vaddrs) {
        const page = Math.floor(va / 4096) * 4096;
        let list = byPage.get(page);
        if (!list) {
          list = [];
          byPage.set(page, list);
        }
        list.push([key, va]);
      }
    }
    if (!byPage.size) return results;

    for (const [fileOff, baseVaddr, size] of this.textRanges) {
      const count = (size & ~3) >>> 2;
      const absOff = this.u8.byteOffset + fileOff;
      // Handle arbitrary byteOffset alignment cleanly
      const words = (absOff & 3) === 0
        ? new Uint32Array(this.u8.buffer, absOff, count)
        : new Uint32Array(this.u8.buffer.slice(absOff, absOff + count * 4));

      for (let i = 0; i < count; i++) {
        const w = words[i];
        if (((w & 0x9f000000) >>> 0) !== 0x90000000) continue;
        const pc = baseVaddr + i * 4;
        const immlo = (w >>> 29) & 0x3;
        const immhi = (w >>> 5) & 0x7ffff;
        let imm = (immhi << 2) | immlo;
        if (imm & 0x100000) imm -= 0x200000;
        const page = Math.floor(pc / 4096) * 4096 + imm * 4096;
        const tlist = byPage.get(page);
        if (!tlist) continue;

        const rd = w & 0x1f;
        const end = Math.min(i + followers, count);
        for (let j = i + 1; j < end; j++) {
          const w2 = words[j];
          const isAdd = ((w2 & 0xffc00000) >>> 0) === 0x91000000 && ((w2 >>> 5) & 0x1f) === rd;
          const isLdr = ((w2 & 0xffc00000) >>> 0) === 0xf9400000 && ((w2 >>> 5) & 0x1f) === rd;
          if (isAdd || isLdr) {
            const resolved = isAdd ? page + ((w2 >>> 10) & 0xfff) : page + ((w2 >>> 10) & 0xfff) * 8;
            for (const [key, targetVa] of tlist) {
              if (resolved === targetVa) {
                const fn = this.findFunction(pc);
                results.get(key).push({
                  adrpPc: pc,
                  addPc: baseVaddr + j * 4,
                  targetVaddr: targetVa,
                  fnStart: fn ? fn[0] : null,
                  fnEnd: fn ? fn[1] : null,
                });
              }
            }
            if ((w2 & 0x1f) === rd) break;
          } else if (((w2 & 0x9f000000) >>> 0) === 0x90000000 && (w2 & 0x1f) === rd) {
            break;
          }
        }
      }
    }
    return results;
  }

  scanStringXrefs(needles, followers = 64) {
    const missing = needles.filter((n) => !this._xrefCache.has(n));
    if (missing.length) {
      const targetMap = new Map();
      for (const n of missing) targetMap.set(n, this.findStringVaddrs(n));
      const scanned = this.scanTargetXrefs(targetMap, followers);
      for (const n of missing) this._xrefCache.set(n, scanned.get(n) || []);
    }
    const out = {};
    for (const n of needles) out[n] = this._xrefCache.get(n) || [];
    return out;
  }
}

// --- Active patch finders ---------------------------------------------------------------

function blCallsInRange(elf, start, end) {
  const out = [];
  const off = elf.vaddrToFileOffset(start, end - start);
  if (off === null) return out;
  const n = (end - start) >>> 2;
  for (let i = 0; i < n; i++) {
    const w = elf.v.getUint32(off + i * 4, true);
    const pc = start + i * 4;
    const target = decodeBl(w, pc);
    if (target !== null) {
      const nxt = i + 1 < n ? elf.v.getUint32(off + (i + 1) * 4, true) : null;
      out.push([pc, target, nxt]);
    }
  }
  return out;
}

function functionReturnsBoolInW0(elf, fnStart, fnEnd) {
  if (fnEnd - fnStart < 8) return false;
  if (elf.readU32Vaddr(fnEnd - 4) !== INSN_RET) return false;
  const tailStart = Math.max(fnStart, fnEnd - 32);
  for (let pc = tailStart; pc < fnEnd - 4; pc += 4) {
    const w = elf.readU32Vaddr(pc);
    if (w === null) continue;
    if ((w & 0x1f) === 0 && (
      ((w & 0xffe0ffe0) >>> 0) === 0x2a0003e0 ||
      ((w & 0xff80001f) >>> 0) === 0x52800000 ||
      ((w & 0xffc0001f) >>> 0) === 0x39400000 ||
      ((w & 0xffc0001f) >>> 0) === 0xb9400000 ||
      ((w & 0xffe00c1f) >>> 0) === 0x1a800400 ||
      ((w & 0xff80001f) >>> 0) === 0x12000000
    )) {
      return true;
    }
  }
  return false;
}

function findBssFlagSlot(elf, index = 0, minSlack = 64) {
  const writable = elf.segments.filter((s) => (s.flags & PF_W) !== 0);
  if (!writable.length) throw new Error('ELF has no writable PT_LOAD segment for .bss runtime flags');
  const last = writable[writable.length - 1];
  const segEnd = last.vaddr + last.memsz;
  const alignedBase = Math.ceil(segEnd / 16) * 16;
  const page4End = Math.ceil(segEnd / 4096) * 4096;
  const page16End = Math.ceil(segEnd / 16384) * 16384;
  if (page4End - alignedBase < minSlack) {
    throw new Error(`insufficient .bss page slack (${page4End - alignedBase} B < ${minSlack} B)`);
  }
  const slot = alignedBase + index * 16;
  if (slot + 16 > page4End || slot + 16 > page16End) {
    throw new Error(`.bss flag slot 0x${slot.toString(16)} exceeds page end 0x${page4End.toString(16)}`);
  }
  return slot;
}

function findStaffDevTools(elf) {
  if (!elf.ehFrame) throw new Error('staff.devtools: missing PT_GNU_EH_FRAME');
  const xrefs = elf.scanStringXrefs([
    'Show Dev Tools Widget',
    'settings-show-dev-widget',
    'Staff Settings',
    'Build Status',
  ]);

  const widgetXrefs = xrefs['Show Dev Tools Widget'];
  if (!widgetXrefs.length) throw new Error('staff.devtools: no xref for "Show Dev Tools Widget"');
  const widgetFnMap = new Map();
  for (const x of widgetXrefs) {
    if (x.fnStart !== null) widgetFnMap.set(`${x.fnStart}:${x.fnEnd}`, [x.fnStart, x.fnEnd]);
  }
  const widgetFns = [...widgetFnMap.values()];
  if (widgetFns.length !== 1) {
    throw new Error(`staff.devtools: expected 1 widget fn, found ${widgetFns.length}`);
  }
  const [wStart, wEnd] = widgetFns[0];
  const firstWidgetPc = Math.min(...widgetXrefs.filter((x) => x.fnStart === wStart).map((x) => x.adrpPc));

  const keyXrefs = xrefs['settings-show-dev-widget'];
  if (keyXrefs.length && !keyXrefs.some((x) => x.fnStart === wStart && x.fnEnd === wEnd)) {
    throw new Error('staff.devtools: "settings-show-dev-widget" disagrees with widget function');
  }

  let candidates = [];
  for (const [, target, nxt] of blCallsInRange(elf, wStart, firstWidgetPc)) {
    if (nxt !== null && (isCbzW0(nxt) || isTbzW0Bit0(nxt)) && !candidates.includes(target)) {
      candidates.push(target);
    }
  }
  if (!candidates.length) {
    throw new Error(`staff.devtools: no BL; CBZ/TBZ w0 gate in 0x${wStart.toString(16)}..0x${firstWidgetPc.toString(16)}`);
  }

  const staffXrefs = xrefs['Staff Settings'].length ? xrefs['Staff Settings'] : xrefs['Build Status'];
  const staffFnMap = new Map();
  for (const x of staffXrefs) {
    if (x.fnStart !== null) staffFnMap.set(`${x.fnStart}:${x.fnEnd}`, [x.fnStart, x.fnEnd]);
  }
  const staffFns = [...staffFnMap.values()];
  if (staffFns.length) {
    const confirmed = [];
    for (const [sfStart, sfEnd] of staffFns) {
      const calls = blCallsInRange(elf, sfStart, sfEnd);
      const called = new Set(calls.map((c) => c[1]));
      const gated = new Set(calls.filter((c) => c[2] !== null && (isCbzW0(c[2]) || isTbzW0Bit0(c[2]))).map((c) => c[1]));
      for (const cand of candidates) if (gated.has(cand) && !confirmed.includes(cand)) confirmed.push(cand);
      if (!confirmed.length) {
        for (const cand of candidates) if (called.has(cand) && !confirmed.includes(cand)) confirmed.push(cand);
      }
    }
    if (!confirmed.length) throw new Error('staff.devtools: widget predicate not confirmed in Staff Settings fn');
    candidates = confirmed;
  }

  if (candidates.length !== 1) {
    throw new Error(`staff.devtools: ambiguous predicate candidates (${candidates.length})`);
  }

  const target = candidates[0];
  const targetFn = elf.findFunction(target);
  if (!targetFn || targetFn[0] !== target) {
    throw new Error(`staff.devtools: target 0x${target.toString(16)} is not an .eh_frame_hdr fn start`);
  }
  const [tStart, tEnd] = targetFn;
  if (tEnd - tStart < 16) throw new Error('staff.devtools: target function unexpectedly small');

  const flagVaddr = findBssFlagSlot(elf, 0);
  const patchedStub = encodeAdrpLdrbRet(tStart, flagVaddr);
  const fileOff = elf.vaddrToFileOffset(tStart, 12);
  if (fileOff === null) throw new Error('staff.devtools: target not mapped in PT_LOAD');

  const orig = elf.u8.slice(fileOff, fileOff + 12);
  const w0 = elf.v.getUint32(fileOff, true);
  const w1 = elf.v.getUint32(fileOff + 4, true);
  if (!isStandardPrologue(w0, w1)) {
    throw new Error(`staff.devtools: target @ 0x${fileOff.toString(16)} failed prologue check (0x${w0.toString(16)}, 0x${w1.toString(16)})`);
  }
  if (!functionReturnsBoolInW0(elf, tStart, tEnd)) {
    throw new Error(`staff.devtools: target @ 0x${fileOff.toString(16)} failed bool-return epilogue check`);
  }

  return {
    edits: [{ tag: 'staff.devtools', fileOffset: fileOff, vaddr: tStart, orig, patched: patchedStub }],
    runtimeFlags: { 'staff.devtools': { vaddr: flagVaddr, default: 1 } },
    detail: `predicate 0x${tStart.toString(16)}..0x${tEnd.toString(16)} -> .bss flag 0x${flagVaddr.toString(16)}`,
  };
}

function findPhoenixAccess(elf) {
  if (!elf.ehFrame) throw new Error('experiment.release_access: missing PT_GNU_EH_FRAME');
  const EXPERIMENT = '2026-11-release-app-access';

  // Strategy A: Cinder's variant-helper finder (standalone discord-mobile.apk)
  const xrefs = elf.scanStringXrefs([EXPERIMENT])[EXPERIMENT];
  const fnXrefs = new Map();
  for (const x of xrefs) {
    if (x.fnStart === null || x.fnEnd === null) continue;
    const key = `${x.fnStart}:${x.fnEnd}`;
    const prev = fnXrefs.get(key);
    if (!prev || x.adrpPc < prev.xrefPc) fnXrefs.set(key, { fnStart: x.fnStart, fnEnd: x.fnEnd, xrefPc: x.adrpPc });
  }

  const matches = [];
  for (const { fnStart, fnEnd, xrefPc } of fnXrefs.values()) {
    const off = elf.vaddrToFileOffset(xrefPc, fnEnd - xrefPc);
    if (off === null) continue;
    const n = (fnEnd - xrefPc) >>> 2;
    if (n < 6) continue;
    const words = new Uint32Array(n);
    for (let i = 0; i < n; i++) words[i] = elf.v.getUint32(off + i * 4, true);
    if (words[n - 1] !== INSN_RET) continue;

    const cbzTargets = new Set();
    for (let i = 0; i < n; i++) {
      const t = decodeCbzX0(words[i], xrefPc + i * 4);
      if (t !== null && t > xrefPc && t < fnEnd) cbzTargets.add(t);
    }

    const site1s = [];
    for (let i = 0; i < n - 1; i++) {
      if (isCmpXImm1(words[i]) && words[i + 1] === INSN_CSET_W0_EQ) {
        site1s.push(xrefPc + (i + 1) * 4);
      }
    }
    if (site1s.length !== 1) continue;
    const site1Pc = site1s[0];

    const site2s = [];
    for (let i = 0; i < n; i++) {
      const pc2 = xrefPc + i * 4;
      if (pc2 > site1Pc && words[i] === INSN_MOV_W0_2) site2s.push(pc2);
    }
    if (site2s.length !== 1) continue;
    const site2Pc = site2s[0];

    const off1 = elf.vaddrToFileOffset(site1Pc, 4);
    const off2 = elf.vaddrToFileOffset(site2Pc, 4);
    if (off1 === null || off2 === null) continue;
    matches.push({
      edits: [
        { tag: 'experiment.release_access.1', fileOffset: off1, vaddr: site1Pc, orig: packU32s(INSN_CSET_W0_EQ), patched: packU32s(INSN_MOV_W0_1) },
        { tag: 'experiment.release_access.2', fileOffset: off2, vaddr: site2Pc, orig: packU32s(INSN_MOV_W0_2), patched: packU32s(INSN_MOV_W0_1) },
      ],
      detail: `variant helper 0x${fnStart.toString(16)}..0x${fnEnd.toString(16)} (2 sites -> mov w0, #1)`,
    });
  }

  if (matches.length === 1) return matches[0];

  // Strategy B: Experiment-table RELA slot finder (Play Store Discord Alpha 356207+)
  const strVaddrs = elf.findStringVaddrs(EXPERIMENT);
  const tableSlots = [];
  for (const s of strVaddrs) {
    for (const slot of elf.relocSlotsTo(s)) {
      if (elf.readU64Vaddr(slot + 8) === EXPERIMENT.length) tableSlots.push(slot);
    }
  }
  if (tableSlots.length) {
    const slotRefs = elf.scanTargetXrefs(new Map([['exp_table', tableSlots]]), 16).get('exp_table') || [];
    const evalFns = [...new Set(slotRefs.filter((r) => r.fnStart !== null).map((r) => r.fnStart))];
    if (evalFns.length === 1) {
      const fnStart = evalFns[0];
      const fileOff = elf.vaddrToFileOffset(fnStart, 8);
      if (fileOff !== null) {
        const orig = elf.u8.slice(fileOff, fileOff + 8);
        const patched = packU32s(INSN_MOV_W0_1, INSN_RET);
        return {
          edits: [{ tag: 'experiment.release_access', fileOffset: fileOff, vaddr: fnStart, orig, patched }],
          detail: `access evaluator 0x${fnStart.toString(16)} -> mov w0, #1; ret`,
        };
      }
    }
  }

  throw new Error('experiment.release_access: could not resolve access gate in host .so');
}

function replaceExactLiteral(u8, oldStr, newStr, { expect = 1, optional = false } = {}) {
  const oldB = te.encode(oldStr);
  const newB = te.encode(newStr);
  if (oldB.length !== newB.length) throw new Error(`literal length mismatch for ${oldStr}`);
  const hits = [];
  const first = oldB[0];
  for (let i = 0; i <= u8.length - oldB.length; ) {
    const idx = u8.indexOf(first, i);
    if (idx < 0 || idx > u8.length - oldB.length) break;
    let ok = true;
    for (let j = 1; j < oldB.length; j++) {
      if (u8[idx + j] !== oldB[j]) {
        ok = false;
        break;
      }
    }
    if (ok) {
      hits.push(idx);
      i = idx + oldB.length;
    } else {
      i = idx + 1;
    }
  }
  if (hits.length === 0 && optional) return 0;
  if (expect !== null && hits.length !== expect) {
    if (optional) return 0;
    throw new Error(`literal "${oldStr}" found ${hits.length} times (expected ${expect})`);
  }
  for (const idx of hits) u8.set(newB, idx);
  return hits.length;
}

function applyEdit(u8, edit) {
  const cur = u8.subarray(edit.fileOffset, edit.fileOffset + edit.orig.length);
  for (let i = 0; i < edit.orig.length; i++) {
    if (cur[i] !== edit.orig[i]) {
      throw new Error(`${edit.tag}: guard mismatch at 0x${edit.fileOffset.toString(16)}`);
    }
  }
  u8.set(edit.patched, edit.fileOffset);
}

/**
 * Patch `liblibdiscord_wasm_host.so` in place with all Cinder Mobile static patches
 * and return the `assets/cinder-patches.json` manifest object.
 */
export function patchHostSo(u8, log = () => {}) {
  const elf = new ElfImage(u8);
  log(`host ELF Build ID: ${elf.buildId || '(none)'}`);

  // Single batched `.text` scan for all string anchors
  elf.scanStringXrefs([
    'Show Dev Tools Widget',
    'settings-show-dev-widget',
    'Staff Settings',
    'Build Status',
    '2026-11-release-app-access',
  ]);

  const appliedEdits = [];
  const runtimeFlags = {};

  const accessRes = findPhoenixAccess(elf);
  for (const e of accessRes.edits) {
    applyEdit(u8, e);
    appliedEdits.push(e);
  }
  log(`host .so: release_access -> ${accessRes.detail}`);

  const staffRes = findStaffDevTools(elf);
  for (const e of staffRes.edits) {
    applyEdit(u8, e);
    appliedEdits.push(e);
  }
  Object.assign(runtimeFlags, staffRes.runtimeFlags);
  log(`host .so: staff.devtools -> ${staffRes.detail}`);

  const labelHits = replaceExactLiteral(u8, 'Native runtime · build ', 'Cinder runtime · build ', {
    expect: 1,
    optional: true,
  });
  if (labelHits) log(`host .so: branded runtime label (${labelHits} site)`);

  const manifest = {
    schema: 1,
    build_id: elf.buildId || '',
    patches: appliedEdits.map((e) => ({
      tag: e.tag,
      file_offset: `0x${e.fileOffset.toString(16)}`,
      orig_hex: hex(e.orig),
      patched_hex: hex(e.patched),
    })),
    runtime_flags: runtimeFlags,
  };
  return manifest;
}
