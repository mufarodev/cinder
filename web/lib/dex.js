// Pure-JS Dalvik DEX patcher for Cinder Mobile (browser & Node.js, zero dependencies).
//
// Strategy ("Extract-and-Emit"):
//   1. If Play Store `PhoenixLaunch` gate ("Phoenix didn't stay up for 3 launches") is present
//      in any `classes*.dex`, patches its boolean return to `true` in place (4 bytes).
//   2. Locates Cinder's hook targets dynamically across `classes*.dex`:
//        - Application class (from AndroidManifest.xml `<application android:name>`, e.g.
//          `com.discord.MainApplication` or `com.discord.nativeapp.NativeApp`)
//        - `com.discord.nativeapp.bridge.GuestHost`
//        - All classes calling `WasmRuntime.nativeDispatch(J[B)Z`
//        - ViewWire `prop_names` static `String[]` field (via `` ` is not in prop_names.txt; dropped ``)
//        - `BridgeRegistry` symbol-table static `String[]` field (from `GuestHost.<clinit>`)
//   3. Removes ONLY those ~4 `class_def`s + `class_data_item`s from their source DEX (zero
//      ID shifts across the remaining ~10,000 classes, avoiding 65,535 method_id overflow!),
//      applies all Cinder bytecode hooks, and emits a pristine, spec-compliant mini-DEX
//      (`classesN.dex`) containing the patched classes.

const PHOENIX_ANCHOR = new TextEncoder().encode("Phoenix didn't stay up for 3 launches");
const PROP_NAMES_MARKER = '` is not in prop_names.txt; dropped';
const GUEST_HOST_DESC = 'Lcom/discord/nativeapp/bridge/GuestHost;';
const WASM_RUNTIME_DESC = 'Lcom/discord/nativeapp/bridge/WasmRuntime;';
const LOADER_DESC = 'Lcom/discord/loader/Loader;';
const HOST_INVOKER_DESC = 'Lcom/discord/loader/HostInvoker;';
const BRIDGE_REGISTRY_DESC = 'Lcom/discord/loader/BridgeRegistry;';
const UI_OPS_DESC = 'Lcom/discord/loader/UiOps;';

const te = new TextEncoder();
const td = new TextDecoder();

// --- Dalvik instruction width table (in 16-bit code units) ------------------------------

// Standard Dalvik opcode widths (0x00..0xff). 0 = unused/illegal opcode.
const OP_WIDTH = new Uint8Array([
  // 0x00..0x0f
  1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1,
  // 0x10..0x1f
  1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2,
  // 0x20..0x2f
  2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2,
  // 0x30..0x3f
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0,
  // 0x40..0x4f
  0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
  // 0x50..0x5f
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
  // 0x60..0x6f
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3,
  // 0x70..0x7f
  3, 3, 3, 0, 3, 3, 3, 3, 3, 0, 0, 1, 1, 1, 1, 1,
  // 0x80..0x8f
  1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  // 0x90..0x9f
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
  // 0xa0..0xaf
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
  // 0xb0..0xbf
  1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  // 0xc0..0xcf
  1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
  // 0xd0..0xdf
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
  // 0xe0..0xef
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0, 0, 0,
  // 0xf0..0xff
  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 3, 3, 2, 2,
]);

function insnWidth(units, i) {
  const w0 = units[i];
  const op = w0 & 0xff;
  if (w0 === 0x0100) return units[i + 1] * 2 + 4; // packed-switch-payload
  if (w0 === 0x0200) return units[i + 1] * 4 + 2; // sparse-switch-payload
  if (w0 === 0x0300) {
    const elemW = units[i + 1];
    const cnt = units[i + 2] | (units[i + 3] << 16);
    return Math.floor((cnt * elemW + 1) / 2) + 4; // fill-array-data-payload
  }
  const w = OP_WIDTH[op];
  if (!w) throw new Error(`unknown Dalvik opcode 0x${op.toString(16)} (word 0x${w0.toString(16)}) at +${i}`);
  return w;
}

// --- Low-level DEX encoding helpers -----------------------------------------------------

function readUleb(d, p) {
  let r = 0;
  let s = 0;
  let b;
  do {
    b = d[p++];
    r |= (b & 0x7f) << s;
    s += 7;
  } while (b & 0x80);
  return [r >>> 0, p];
}

function readSleb(d, p) {
  let r = 0;
  let s = 0;
  let b;
  do {
    b = d[p++];
    r |= (b & 0x7f) << s;
    s += 7;
  } while (b & 0x80);
  if (s < 32 && (b & 0x40)) r |= -(1 << s);
  return [r, p];
}

function writeUleb(out, val) {
  let v = val >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    out.push(b);
  } while (v !== 0);
}

function writeSleb(out, val) {
  let v = val | 0;
  let more = true;
  while (more) {
    let b = v & 0x7f;
    v >>= 7;
    if ((v === 0 && (b & 0x40) === 0) || (v === -1 && (b & 0x40) !== 0)) {
      more = false;
    } else {
      b |= 0x80;
    }
    out.push(b);
  }
}

function adler32(d, from) {
  let a = 1;
  let b = 0;
  for (let i = from; i < d.length; ) {
    const end = Math.min(d.length, i + 3800);
    for (; i < end; i++) {
      a += d[i];
      b += a;
    }
    a %= 65521;
    b %= 65521;
  }
  return ((b << 16) | a) >>> 0;
}

async function fixDexHashes(d) {
  const sha1 = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-1', d.subarray(32)));
  d.set(sha1, 12);
  new DataView(d.buffer, d.byteOffset, d.byteLength).setUint32(8, adler32(d, 12), true);
}

// Decode MUTF-8 bytes -> JS string (handles standard UTF-8 and MUTF-8 surrogates/NUL)
function decodeMutf8(u8, start) {
  let end = start;
  while (u8[end] !== 0) end++;
  // Fast path: pure ASCII or valid UTF-8 without MUTF-8 0xC0 0x80 or 0xED surrogates
  let mutf8Special = false;
  for (let i = start; i < end; i++) {
    const c = u8[i];
    if (c === 0xc0 || c === 0xed) {
      mutf8Special = true;
      break;
    }
  }
  if (!mutf8Special) return td.decode(u8.subarray(start, end));
  let s = '';
  let i = start;
  while (i < end) {
    const a = u8[i++];
    if (a < 0x80) {
      s += String.fromCharCode(a);
    } else if ((a & 0xe0) === 0xc0) {
      const b = u8[i++];
      s += String.fromCharCode(((a & 0x1f) << 6) | (b & 0x3f));
    } else {
      const b = u8[i++];
      const c = u8[i++];
      s += String.fromCharCode(((a & 0x0f) << 12) | ((b & 0x3f) << 6) | (c & 0x3f));
    }
  }
  return s;
}

// Encode JS string -> MUTF-8 bytes (with leading ULEB128 UTF-16 length + trailing NUL)
function encodeMutf8Data(str) {
  const out = [];
  writeUleb(out, str.length);
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    if (c !== 0 && c <= 0x7f) {
      out.push(c);
    } else if (c <= 0x7ff) {
      out.push(0xc0 | ((c >> 6) & 0x1f), 0x80 | (c & 0x3f));
    } else {
      out.push(0xe0 | ((c >> 12) & 0x0f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  out.push(0);
  return Uint8Array.from(out);
}

// --- DEX Reader -------------------------------------------------------------------------

class DexReader {
  constructor(u8) {
    this.u8 = u8;
    this.v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    this.mapOff = this.v.getUint32(52, true);
    this.stringIdsSize = this.v.getUint32(56, true);
    this.stringIdsOff = this.v.getUint32(60, true);
    this.typeIdsSize = this.v.getUint32(64, true);
    this.typeIdsOff = this.v.getUint32(68, true);
    this.protoIdsSize = this.v.getUint32(72, true);
    this.protoIdsOff = this.v.getUint32(76, true);
    this.fieldIdsSize = this.v.getUint32(80, true);
    this.fieldIdsOff = this.v.getUint32(84, true);
    this.methodIdsSize = this.v.getUint32(88, true);
    this.methodIdsOff = this.v.getUint32(92, true);
    this.classDefsSize = this.v.getUint32(96, true);
    this.classDefsOff = this.v.getUint32(100, true);
    this._strCache = new Map();
    this._protoCache = new Map();
    this._fieldCache = new Map();
    this._methodCache = new Map();
  }

  str(idx) {
    let s = this._strCache.get(idx);
    if (s !== undefined) return s;
    let o = this.v.getUint32(this.stringIdsOff + 4 * idx, true);
    [, o] = readUleb(this.u8, o);
    s = decodeMutf8(this.u8, o);
    this._strCache.set(idx, s);
    return s;
  }

  type(idx) {
    return this.str(this.v.getUint32(this.typeIdsOff + 4 * idx, true));
  }

  proto(idx) {
    let p = this._protoCache.get(idx);
    if (p) return p;
    const o = this.protoIdsOff + 12 * idx;
    const shorty = this.str(this.v.getUint32(o, true));
    const ret = this.type(this.v.getUint32(o + 4, true));
    const pOff = this.v.getUint32(o + 8, true);
    const params = [];
    if (pOff) {
      const cnt = this.v.getUint32(pOff, true);
      for (let i = 0; i < cnt; i++) params.push(this.type(this.v.getUint16(pOff + 4 + 2 * i, true)));
    }
    p = { shorty, ret, params, sig: `(${params.join('')})${ret}` };
    this._protoCache.set(idx, p);
    return p;
  }

  field(idx) {
    let f = this._fieldCache.get(idx);
    if (f) return f;
    const o = this.fieldIdsOff + 8 * idx;
    const cls = this.type(this.v.getUint16(o, true));
    const type = this.type(this.v.getUint16(o + 2, true));
    const name = this.str(this.v.getUint32(o + 4, true));
    f = { cls, name, type, key: `${cls}->${name}:${type}` };
    this._fieldCache.set(idx, f);
    return f;
  }

  method(idx) {
    let m = this._methodCache.get(idx);
    if (m) return m;
    const o = this.methodIdsOff + 8 * idx;
    const cls = this.type(this.v.getUint16(o, true));
    const proto = this.proto(this.v.getUint16(o + 2, true));
    const name = this.str(this.v.getUint32(o + 4, true));
    m = { cls, name, proto, key: `${cls}->${name}${proto.sig}` };
    this._methodCache.set(idx, m);
    return m;
  }

  readTypeList(off) {
    if (!off) return [];
    const cnt = this.v.getUint32(off, true);
    const out = [];
    for (let i = 0; i < cnt; i++) out.push(this.type(this.v.getUint16(off + 4 + 2 * i, true)));
    return out;
  }

  readClassDef(idx) {
    const o = this.classDefsOff + 32 * idx;
    const classIdx = this.v.getUint32(o, true);
    const accessFlags = this.v.getUint32(o + 4, true);
    const superIdx = this.v.getUint32(o + 8, true);
    const ifacesOff = this.v.getUint32(o + 12, true);
    const srcIdx = this.v.getUint32(o + 16, true);
    const annoOff = this.v.getUint32(o + 20, true);
    const cdataOff = this.v.getUint32(o + 24, true);
    const svalOff = this.v.getUint32(o + 28, true);
    return {
      idx,
      cls: this.type(classIdx),
      accessFlags,
      superCls: superIdx === 0xffffffff ? null : this.type(superIdx),
      interfaces: this.readTypeList(ifacesOff),
      sourceFile: srcIdx === 0xffffffff ? null : this.str(srcIdx),
      annoOff,
      cdataOff,
      svalOff,
    };
  }

  readClassData(off) {
    if (!off) return { staticFields: [], instanceFields: [], directMethods: [], virtualMethods: [] };
    let p = off;
    let sf, inf, dm, vm;
    [sf, p] = readUleb(this.u8, p);
    [inf, p] = readUleb(this.u8, p);
    [dm, p] = readUleb(this.u8, p);
    [vm, p] = readUleb(this.u8, p);

    const readFields = (cnt) => {
      const out = [];
      let fid = 0;
      for (let i = 0; i < cnt; i++) {
        let diff, acc;
        [diff, p] = readUleb(this.u8, p);
        [acc, p] = readUleb(this.u8, p);
        fid += diff;
        out.push({ field: this.field(fid), origIdx: fid, accessFlags: acc });
      }
      return out;
    };

    const readMethods = (cnt) => {
      const out = [];
      let mid = 0;
      for (let i = 0; i < cnt; i++) {
        let diff, acc, codeOff;
        [diff, p] = readUleb(this.u8, p);
        [acc, p] = readUleb(this.u8, p);
        [codeOff, p] = readUleb(this.u8, p);
        mid += diff;
        out.push({
          method: this.method(mid),
          origIdx: mid,
          accessFlags: acc,
          code: codeOff ? this.readCodeItem(codeOff) : null,
        });
      }
      return out;
    };

    return {
      staticFields: readFields(sf),
      instanceFields: readFields(inf),
      directMethods: readMethods(dm),
      virtualMethods: readMethods(vm),
    };
  }

  readCodeItem(off) {
    const registersSize = this.v.getUint16(off, true);
    const insSize = this.v.getUint16(off + 2, true);
    const outsSize = this.v.getUint16(off + 4, true);
    const triesSize = this.v.getUint16(off + 6, true);
    const insnsSize = this.v.getUint32(off + 12, true);
    const units = new Uint16Array(insnsSize);
    for (let i = 0; i < insnsSize; i++) units[i] = this.v.getUint16(off + 16 + 2 * i, true);

    const tries = [];
    if (triesSize > 0) {
      let tOff = off + 16 + insnsSize * 2;
      if (insnsSize & 1) tOff += 2;
      const handlersBase = tOff + triesSize * 8;
      for (let i = 0; i < triesSize; i++) {
        const startAddr = this.v.getUint32(tOff + i * 8, true);
        const insnCount = this.v.getUint16(tOff + i * 8 + 4, true);
        const handlerOff = this.v.getUint16(tOff + i * 8 + 6, true);
        let hp = handlersBase + handlerOff;
        let size;
        [size, hp] = readSleb(this.u8, hp);
        const pairs = [];
        for (let k = 0; k < Math.abs(size); k++) {
          let tidx, addr;
          [tidx, hp] = readUleb(this.u8, hp);
          [addr, hp] = readUleb(this.u8, hp);
          pairs.push({ type: this.type(tidx), addr });
        }
        let catchAllAddr = null;
        if (size <= 0) {
          [catchAllAddr, hp] = readUleb(this.u8, hp);
        }
        tries.push({ startAddr, insnCount, pairs, catchAllAddr });
      }
    }

    // Parse instruction stream to decode symbolic references (strings/types/fields/methods/protos)
    const refs = [];
    for (let i = 0; i < units.length; ) {
      const w0 = units[i];
      const op = w0 & 0xff;
      const w = insnWidth(units, i);
      if (w0 !== 0x0100 && w0 !== 0x0200 && w0 !== 0x0300) {
        if (op === 0x1a) {
          refs.push({ at: i + 1, kind: 'str16', val: this.str(units[i + 1]) });
        } else if (op === 0x1b) {
          const sid = (units[i + 1] | (units[i + 2] << 16)) >>> 0;
          refs.push({ at: i + 1, kind: 'str32', val: this.str(sid) });
        } else if (op === 0x1c || op === 0x1f || op === 0x20 || (op >= 0x22 && op <= 0x25)) {
          refs.push({ at: i + 1, kind: 'type16', val: this.type(units[i + 1]) });
        } else if (op >= 0x52 && op <= 0x6d) {
          refs.push({ at: i + 1, kind: 'field16', val: this.field(units[i + 1]) });
        } else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
          refs.push({ at: i + 1, kind: 'method16', val: this.method(units[i + 1]) });
        } else if (op === 0xfa || op === 0xfb) {
          refs.push({ at: i + 1, kind: 'method16', val: this.method(units[i + 1]) });
          refs.push({ at: i + 3, kind: 'proto16', val: this.proto(units[i + 3]) });
        } else if (op >= 0xfc && op <= 0xff) {
          throw new Error(`extracted method contains unsupported invoke-custom/method-handle opcode 0x${op.toString(16)}`);
        }
      }
      i += w;
    }

    return { registersSize, insSize, outsSize, units, tries, refs };
  }

  readEncodedValue(pos) {
    const hdr = this.u8[pos++];
    const vtype = hdr & 0x1f;
    const varg = hdr >>> 5;

    const readUnsigned = (len) => {
      let val = 0;
      for (let i = 0; i < len; i++) val |= this.u8[pos++] << (8 * i);
      return val >>> 0;
    };

    if (vtype === 0x1e || vtype === 0x1f) return [{ vtype, varg }, pos];
    if (vtype === 0x1c) {
      const [arr, np] = this.readEncodedArray(pos);
      return [{ vtype, arr }, np];
    }
    if (vtype === 0x1d) {
      const [anno, np] = this.readEncodedAnnotation(pos);
      return [{ vtype, anno }, np];
    }
    const len = varg + 1;
    if (vtype === 0x17) return [{ vtype, str: this.str(readUnsigned(len)) }, pos];
    if (vtype === 0x18) return [{ vtype, type: this.type(readUnsigned(len)) }, pos];
    if (vtype === 0x19 || vtype === 0x1b) return [{ vtype, field: this.field(readUnsigned(len)) }, pos];
    if (vtype === 0x1a) return [{ vtype, method: this.method(readUnsigned(len)) }, pos];
    if (vtype === 0x15) return [{ vtype, proto: this.proto(readUnsigned(len)) }, pos];
    const raw = this.u8.slice(pos, pos + len);
    pos += len;
    return [{ vtype, varg, raw }, pos];
  }

  readEncodedArray(pos) {
    let cnt;
    [cnt, pos] = readUleb(this.u8, pos);
    const items = [];
    for (let i = 0; i < cnt; i++) {
      let val;
      [val, pos] = this.readEncodedValue(pos);
      items.push(val);
    }
    return [items, pos];
  }

  readEncodedAnnotation(pos) {
    let tidx, size;
    [tidx, pos] = readUleb(this.u8, pos);
    [size, pos] = readUleb(this.u8, pos);
    const elements = [];
    for (let i = 0; i < size; i++) {
      let nidx, val;
      [nidx, pos] = readUleb(this.u8, pos);
      [val, pos] = this.readEncodedValue(pos);
      elements.push({ name: this.str(nidx), val });
    }
    return [{ type: this.type(tidx), elements }, pos];
  }

  readAnnotationSet(off) {
    if (!off) return null;
    const cnt = this.v.getUint32(off, true);
    const items = [];
    for (let i = 0; i < cnt; i++) {
      const aOff = this.v.getUint32(off + 4 + 4 * i, true);
      const visibility = this.u8[aOff];
      const [anno] = this.readEncodedAnnotation(aOff + 1);
      items.push({ visibility, anno });
    }
    return items;
  }

  readAnnotationsDirectory(off) {
    if (!off) return null;
    const classOff = this.v.getUint32(off, true);
    const fCnt = this.v.getUint32(off + 4, true);
    const mCnt = this.v.getUint32(off + 8, true);
    const pCnt = this.v.getUint32(off + 12, true);
    let p = off + 16;
    const fieldAnnos = [];
    for (let i = 0; i < fCnt; i++) {
      fieldAnnos.push({ field: this.field(this.v.getUint32(p, true)), set: this.readAnnotationSet(this.v.getUint32(p + 4, true)) });
      p += 8;
    }
    const methodAnnos = [];
    for (let i = 0; i < mCnt; i++) {
      methodAnnos.push({ method: this.method(this.v.getUint32(p, true)), set: this.readAnnotationSet(this.v.getUint32(p + 4, true)) });
      p += 8;
    }
    const paramAnnos = [];
    for (let i = 0; i < pCnt; i++) {
      const method = this.method(this.v.getUint32(p, true));
      const refOff = this.v.getUint32(p + 4, true);
      const rCnt = this.v.getUint32(refOff, true);
      const sets = [];
      for (let k = 0; k < rCnt; k++) sets.push(this.readAnnotationSet(this.v.getUint32(refOff + 4 + 4 * k, true)));
      paramAnnos.push({ method, sets });
      p += 8;
    }
    return {
      classAnnos: this.readAnnotationSet(classOff),
      fieldAnnos,
      methodAnnos,
      paramAnnos,
    };
  }

  extractClass(defIdx) {
    const def = this.readClassDef(defIdx);
    const data = this.readClassData(def.cdataOff);
    const annotations = this.readAnnotationsDirectory(def.annoOff);
    const staticValues = def.svalOff ? this.readEncodedArray(def.svalOff)[0] : null;
    return { ...def, ...data, annotations, staticValues };
  }
}

// --- Dalvik bytecode insertion helper ---------------------------------------------------

/**
 * Insert `insertedUnits` (Uint16Array) and `insertedRefs` (relative to start of `insertedUnits`)
 * into `code` at code-unit offset `insertAt`, updating all relative branches, switch/array
 * payloads, try/catch ranges, and maintaining even alignment for payload pseudo-instructions.
 */
function insertCodeUnits(code, insertAt, insertedUnits, insertedRefs) {
  const old = code.units;
  // Detect whether any switch/fill-array payload exists at or after insertAt.
  let hasPayloadAfter = false;
  for (let i = 0; i < old.length; ) {
    const w0 = old[i];
    if (i >= insertAt && (w0 === 0x0100 || w0 === 0x0200 || w0 === 0x0300)) hasPayloadAfter = true;
    i += insnWidth(old, i);
  }

  // Keep payload 4-byte alignment (even code-unit index) by padding odd insertions with 1 nop.
  let ins = insertedUnits;
  if (hasPayloadAfter && (ins.length & 1) === 1) {
    const padded = new Uint16Array(ins.length + 1);
    padded.set(ins, 0);
    padded[ins.length] = 0x0000; // nop
    ins = padded;
  }

  const delta = ins.length;
  const out = new Uint16Array(old.length + delta);
  out.set(old.subarray(0, insertAt), 0);
  out.set(ins, insertAt);
  out.set(old.subarray(insertAt), insertAt + delta);

  const mapPos = (origPos) => (origPos >= insertAt ? origPos + delta : origPos);

  // Fix relative branches / payload offsets in all original instructions
  for (let i = 0; i < old.length; ) {
    const w0 = old[i];
    const op = w0 & 0xff;
    const w = insnWidth(old, i);
    const newI = mapPos(i);

    if (w0 !== 0x0100 && w0 !== 0x0200 && w0 !== 0x0300) {
      if (op === 0x28) {
        // goto +AA (signed 8-bit)
        const off = (w0 << 16) >> 24;
        const newTarget = mapPos(i + off);
        const newOff = newTarget - newI;
        if (newOff < -128 || newOff > 127) {
          throw new Error(`goto offset overflow (${newOff}) at +${i}`);
        }
        out[newI] = (op | ((newOff & 0xff) << 8)) & 0xffff;
      } else if (op === 0x29 || (op >= 0x32 && op <= 0x3d)) {
        // goto/16 or if-* (signed 16-bit at +1)
        const off = (old[i + 1] << 16) >> 16;
        const newTarget = mapPos(i + off);
        const newOff = newTarget - newI;
        if (newOff < -32768 || newOff > 32767) {
          throw new Error(`branch16 offset overflow (${newOff}) at +${i}`);
        }
        out[newI + 1] = newOff & 0xffff;
      } else if (op === 0x2a || op === 0x26 || op === 0x2b || op === 0x2c) {
        // goto/32, fill-array-data, packed-switch, sparse-switch (signed 32-bit at +1..+2)
        const off = (old[i + 1] | (old[i + 2] << 16)) | 0;
        const newTarget = mapPos(i + off);
        const newOff = newTarget - newI;
        out[newI + 1] = newOff & 0xffff;
        out[newI + 2] = (newOff >>> 16) & 0xffff;
      }
    }
    i += w;
  }

  // Also fix switch payload target offsets (stored relative to the switch instruction, NOT payload!)
  for (let i = 0; i < old.length; ) {
    const w0 = old[i];
    const op = w0 & 0xff;
    const w = insnWidth(old, i);
    if (op === 0x2b || op === 0x2c) {
      const oldSw = i;
      const newSw = mapPos(oldSw);
      const oldPayOff = (old[i + 1] | (old[i + 2] << 16)) | 0;
      const oldPay = oldSw + oldPayOff;
      const newPay = mapPos(oldPay);
      const cnt = old[oldPay + 1];
      const targetsStartOld = op === 0x2b ? oldPay + 4 : oldPay + 2 + cnt * 2;
      const targetsStartNew = op === 0x2b ? newPay + 4 : newPay + 2 + cnt * 2;
      for (let k = 0; k < cnt; k++) {
        const tOffOld = (old[targetsStartOld + 2 * k] | (old[targetsStartOld + 2 * k + 1] << 16)) | 0;
        const newTarget = mapPos(oldSw + tOffOld);
        const newTOff = newTarget - newSw;
        out[targetsStartNew + 2 * k] = newTOff & 0xffff;
        out[targetsStartNew + 2 * k + 1] = (newTOff >>> 16) & 0xffff;
      }
    }
    i += w;
  }

  // Shift symbolic refs
  const newRefs = code.refs.map((r) => ({ ...r, at: mapPos(r.at) }));
  for (const r of insertedRefs) newRefs.push({ ...r, at: insertAt + r.at });

  // Shift try/catch ranges
  for (const t of code.tries) {
    const endAddr = t.startAddr + t.insnCount;
    if (insertAt <= t.startAddr) {
      t.startAddr += delta;
    } else if (insertAt < endAddr) {
      t.insnCount += delta;
    }
    for (const pair of t.pairs) pair.addr = mapPos(pair.addr);
    if (t.catchAllAddr !== null) t.catchAllAddr = mapPos(t.catchAllAddr);
  }

  code.units = out;
  code.refs = newRefs;
}

// --- Dalvik instruction encoders --------------------------------------------------------

function encodeInvokeStatic(regs, methodRef) {
  const n = regs.length;
  const contiguous = n > 0 && regs.every((r, idx) => r === regs[0] + idx);
  const needsRange = regs.some((r) => r >= 16);
  if (needsRange) {
    if (!contiguous) throw new Error(`invoke-static/range requires contiguous registers (got ${regs.join(',')})`);
    return {
      units: Uint16Array.from([0x77 | (n << 8), 0, regs[0]]),
      refs: [{ at: 1, kind: 'method16', val: methodRef }],
    };
  }
  const c = regs[0] || 0;
  const d = regs[1] || 0;
  const e = regs[2] || 0;
  const f = regs[3] || 0;
  const g = regs[4] || 0;
  return {
    units: Uint16Array.from([0x71 | (n << 12) | (g << 8), 0, c | (d << 4) | (e << 8) | (f << 12)]),
    refs: [{ at: 1, kind: 'method16', val: methodRef }],
  };
}

function makeMethod(cls, name, ret, params) {
  const shorty = [ret, ...params].map((t) => (t.startsWith('L') || t.startsWith('[') ? 'L' : t)).join('');
  return { cls, name, proto: { shorty, ret, params, sig: `(${params.join('')})${ret}` } };
}

// --- Cinder class patchers --------------------------------------------------------------

function patchApplicationClass(clsObj, log) {
  const onCreate = clsObj.virtualMethods.find(
    (m) => m.method.name === 'onCreate' && m.method.proto.sig === '()V',
  );
  if (!onCreate || !onCreate.code) {
    throw new Error(`${clsObj.cls} has no onCreate()V method`);
  }
  const code = onCreate.code;
  // Locate `invoke-super {p0}, Landroid/app/Application;->onCreate()V`
  const superMatches = [];
  for (const r of code.refs) {
    if (
      r.kind === 'method16' &&
      r.val.cls === 'Landroid/app/Application;' &&
      r.val.name === 'onCreate' &&
      r.val.proto.sig === '()V'
    ) {
      const insnStart = r.at - 1;
      const op = code.units[insnStart] & 0xff;
      if (op === 0x6f || op === 0x75) superMatches.push(insnStart);
    }
  }
  if (superMatches.length !== 1) {
    throw new Error(`${clsObj.cls}.onCreate: expected 1 super.onCreate(), found ${superMatches.length}`);
  }
  const insertAt = superMatches[0] + 3;
  const p0 = code.registersSize - code.insSize;
  const initMethod = makeMethod(LOADER_DESC, 'init', 'V', ['Landroid/content/Context;']);
  const inv = encodeInvokeStatic([p0], initMethod);
  if (code.outsSize < 1) code.outsSize = 1;
  insertCodeUnits(code, insertAt, inv.units, inv.refs);
  log(`dex: patched ${clsObj.cls}->onCreate()V -> Loader.init(this)`);
}

function patchGuestHostClass(clsObj, propNamesField, log) {
  // 1. Implement HostInvoker
  if (!clsObj.interfaces.includes(HOST_INVOKER_DESC)) {
    clsObj.interfaces.push(HOST_INVOKER_DESC);
  }

  // 2. Patch applyOps([BI)V
  const applyOps = clsObj.virtualMethods.find(
    (m) => m.method.name === 'applyOps' && m.method.proto.sig === '([BI)V',
  );
  if (!applyOps || !applyOps.code) throw new Error('GuestHost.applyOps([BI)V not found');
  {
    const c = applyOps.code;
    const locals = c.registersSize - c.insSize;
    if (locals < 1) throw new Error('GuestHost.applyOps has 0 local registers');
    const p0 = locals;
    const p1 = locals + 1;
    const p2 = locals + 2;
    const v0 = 0;
    const onOpsMethod = makeMethod(LOADER_DESC, 'onOps', '[B', [HOST_INVOKER_DESC, '[B', 'I']);
    const inv = encodeInvokeStatic([p0, p1, p2], onOpsMethod);
    const units = [...inv.units];
    units.push(0x000c | (v0 << 8)); // move-result-object v0
    if (p1 < 16 && p2 < 16) {
      // if-eq v0, p1, +4 (skips if-eq(2) + move-object(1) + array-length(1))
      units.push(0x0032 | (v0 << 8) | (p1 << 12), 4);
      units.push(0x0007 | (p1 << 8) | (v0 << 12)); // move-object p1, v0
      units.push(0x0021 | (p2 << 8) | (p1 << 12)); // array-length p2, p1
    } else {
      // Register-safe fallback for p1/p2 >= 16 using v0 as scratch
      units.push(0x0008 | (v0 << 8), p1); // Wait: need comparison; compare via move-object/from16 if ever needed
      throw new Error(`GuestHost.applyOps parameter registers exceed 15 (p1=${p1}, p2=${p2})`);
    }
    if (c.outsSize < 3) c.outsSize = 3;
    insertCodeUnits(c, 0, Uint16Array.from(units), inv.refs);
  }

  // 3. Wrap invoke([B)[B -> invokeOrig([B)[B + thin invoke([B)[B calling Loader.dispatchInvoke
  const origInvoke = clsObj.virtualMethods.find(
    (m) => m.method.name === 'invoke' && m.method.proto.sig === '([B)[B',
  );
  if (!origInvoke || !origInvoke.code) throw new Error('GuestHost.invoke([B)[B not found');
  origInvoke.method = { ...origInvoke.method, name: 'invokeOrig' };
  const dispatchMethod = makeMethod(LOADER_DESC, 'dispatchInvoke', '[B', [HOST_INVOKER_DESC, '[B']);
  const wrapperInv = encodeInvokeStatic([1, 2], dispatchMethod); // regs=3: v0, p0=v1, p1=v2
  clsObj.virtualMethods.push({
    method: makeMethod(GUEST_HOST_DESC, 'invoke', '[B', ['[B']),
    accessFlags: 0x0001, // public
    code: {
      registersSize: 3,
      insSize: 2,
      outsSize: 2,
      units: Uint16Array.from([...wrapperInv.units, 0x000c, 0x0011]), // move-result-object v0; return-object v0
      tries: [],
      refs: wrapperInv.refs,
    },
  });

  // 4. Patch log(ILjava/lang/String;)V
  const logMethod = clsObj.virtualMethods.find(
    (m) => m.method.name === 'log' && m.method.proto.sig === '(ILjava/lang/String;)V',
  );
  if (!logMethod || !logMethod.code) throw new Error('GuestHost.log(ILjava/lang/String;)V not found');
  {
    const c = logMethod.code;
    const p0 = c.registersSize - c.insSize;
    const onLog = makeMethod(LOADER_DESC, 'onLog', 'V', ['I', 'Ljava/lang/String;']);
    const inv = encodeInvokeStatic([p0 + 1, p0 + 2], onLog);
    if (c.outsSize < 2) c.outsSize = 2;
    insertCodeUnits(c, 0, inv.units, inv.refs);
  }

  // 5. Patch <clinit>()V to register BridgeRegistry symbol table + UiOps prop names
  const clinit = clsObj.directMethods.find(
    (m) => m.method.name === '<clinit>' && m.method.proto.sig === '()V',
  );
  if (clinit && clinit.code) {
    const c = clinit.code;
    const symtabRef = c.refs.find(
      (r) =>
        r.kind === 'field16' &&
        r.val.type === '[Ljava/lang/String;' &&
        (c.units[r.at - 1] & 0xff) === 0x62, // sget-object
    );
    const toRegister = [];
    if (symtabRef) {
      toRegister.push({
        field: symtabRef.val,
        method: makeMethod(BRIDGE_REGISTRY_DESC, 'register', 'V', ['[Ljava/lang/String;']),
        label: `BridgeRegistry(${symtabRef.val.key})`,
      });
    }
    if (propNamesField) {
      toRegister.push({
        field: propNamesField,
        method: makeMethod(UI_OPS_DESC, 'registerPropNames', 'V', ['[Ljava/lang/String;']),
        label: `UiOps(${propNamesField.key})`,
      });
    }
    if (toRegister.length && c.registersSize >= 1) {
      // Find return-void (0x000e) from the end
      const retPositions = [];
      for (let i = 0; i < c.units.length; ) {
        const w = insnWidth(c.units, i);
        if (c.units[i] === 0x000e) retPositions.push(i);
        i += w;
      }
      if (c.outsSize < 1) c.outsSize = 1;
      for (let k = retPositions.length - 1; k >= 0; k--) {
        const units = [];
        const refs = [];
        for (const item of toRegister) {
          const base = units.length;
          units.push(0x0062, 0); // sget-object v0, field
          refs.push({ at: base + 1, kind: 'field16', val: item.field });
          const inv = encodeInvokeStatic([0], item.method);
          for (const r of inv.refs) refs.push({ ...r, at: units.length + r.at });
          units.push(...inv.units);
        }
        insertCodeUnits(c, retPositions[k], Uint16Array.from(units), refs);
      }
      log(`dex: GuestHost.<clinit> registered ${toRegister.map((x) => x.label).join(' + ')}`);
    }
  }

  log('dex: patched GuestHost (HostInvoker, applyOps, invoke/invokeOrig, log)');
}

function patchNativeDispatchInClass(clsObj) {
  const replacement = makeMethod(LOADER_DESC, 'dispatchNative', 'Z', ['J', '[B']);
  let count = 0;
  for (const list of [clsObj.directMethods, clsObj.virtualMethods]) {
    for (const m of list) {
      if (!m.code) continue;
      for (const r of m.code.refs) {
        if (
          r.kind === 'method16' &&
          r.val.cls === WASM_RUNTIME_DESC &&
          r.val.name === 'nativeDispatch' &&
          r.val.proto.sig === '(J[B)Z'
        ) {
          r.val = replacement;
          count++;
        }
      }
    }
  }
  return count;
}

// --- Mini-DEX Builder (spec-compliant DEX 037) ------------------------------------------

const cmpStr = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

class DexBuilder {
  constructor(classes) {
    this.classes = classes;
    this.strings = new Set();
    this.types = new Set();
    this.protos = new Map(); // sig -> proto
    this.fields = new Map(); // key -> field
    this.methods = new Map(); // key -> method
  }

  addStr(s) {
    if (s !== null && s !== undefined) this.strings.add(s);
  }

  addType(t) {
    if (!t) return;
    this.types.add(t);
    this.addStr(t);
  }

  addProto(p) {
    const key = p.sig;
    if (this.protos.has(key)) return;
    this.protos.set(key, p);
    this.addStr(p.shorty);
    this.addType(p.ret);
    for (const pt of p.params) this.addType(pt);
  }

  addField(f) {
    const key = `${f.cls}->${f.name}:${f.type}`;
    if (this.fields.has(key)) return;
    this.fields.set(key, f);
    this.addType(f.cls);
    this.addType(f.type);
    this.addStr(f.name);
  }

  addMethod(m) {
    const key = `${m.cls}->${m.name}${m.proto.sig}`;
    if (this.methods.has(key)) return;
    this.methods.set(key, m);
    this.addType(m.cls);
    this.addProto(m.proto);
    this.addStr(m.name);
  }

  collectEncodedValue(v) {
    if (v.vtype === 0x17) this.addStr(v.str);
    else if (v.vtype === 0x18) this.addType(v.type);
    else if (v.vtype === 0x19 || v.vtype === 0x1b) this.addField(v.field);
    else if (v.vtype === 0x1a) this.addMethod(v.method);
    else if (v.vtype === 0x15) this.addProto(v.proto);
    else if (v.vtype === 0x1c) v.arr.forEach((x) => this.collectEncodedValue(x));
    else if (v.vtype === 0x1d) this.collectEncodedAnnotation(v.anno);
  }

  collectEncodedAnnotation(a) {
    this.addType(a.type);
    for (const el of a.elements) {
      this.addStr(el.name);
      this.collectEncodedValue(el.val);
    }
  }

  collectAnnotationSet(set) {
    if (!set) return;
    for (const item of set) this.collectEncodedAnnotation(item.anno);
  }

  collectAll() {
    for (const c of this.classes) {
      this.addType(c.cls);
      if (c.superCls) this.addType(c.superCls);
      for (const iface of c.interfaces) this.addType(iface);
      if (c.sourceFile) this.addStr(c.sourceFile);
      for (const f of [...c.staticFields, ...c.instanceFields]) this.addField(f.field);
      for (const m of [...c.directMethods, ...c.virtualMethods]) {
        this.addMethod(m.method);
        if (m.code) {
          for (const r of m.code.refs) {
            if (r.kind === 'str16' || r.kind === 'str32') this.addStr(r.val);
            else if (r.kind === 'type16') this.addType(r.val);
            else if (r.kind === 'field16') this.addField(r.val);
            else if (r.kind === 'method16') this.addMethod(r.val);
            else if (r.kind === 'proto16') this.addProto(r.val);
          }
          for (const t of m.code.tries) {
            for (const p of t.pairs) this.addType(p.type);
          }
        }
      }
      if (c.staticValues) c.staticValues.forEach((x) => this.collectEncodedValue(x));
      if (c.annotations) {
        this.collectAnnotationSet(c.annotations.classAnnos);
        for (const fa of c.annotations.fieldAnnos) {
          this.addField(fa.field);
          this.collectAnnotationSet(fa.set);
        }
        for (const ma of c.annotations.methodAnnos) {
          this.addMethod(ma.method);
          this.collectAnnotationSet(ma.set);
        }
        for (const pa of c.annotations.paramAnnos) {
          this.addMethod(pa.method);
          for (const s of pa.sets) this.collectAnnotationSet(s);
        }
      }
    }
  }

  async build() {
    this.collectAll();

    // Sort ID tables per DEX specification
    const strList = [...this.strings].sort(cmpStr);
    const strIdx = new Map(strList.map((s, i) => [s, i]));

    const typeList = [...this.types].sort((a, b) => strIdx.get(a) - strIdx.get(b));
    const typeIdx = new Map(typeList.map((t, i) => [t, i]));

    const protoList = [...this.protos.values()].sort((a, b) => {
      const dr = typeIdx.get(a.ret) - typeIdx.get(b.ret);
      if (dr !== 0) return dr;
      const n = Math.min(a.params.length, b.params.length);
      for (let i = 0; i < n; i++) {
        const dp = typeIdx.get(a.params[i]) - typeIdx.get(b.params[i]);
        if (dp !== 0) return dp;
      }
      return a.params.length - b.params.length;
    });
    const protoIdx = new Map(protoList.map((p, i) => [p.sig, i]));

    const fieldList = [...this.fields.values()].sort(
      (a, b) =>
        typeIdx.get(a.cls) - typeIdx.get(b.cls) ||
        strIdx.get(a.name) - strIdx.get(b.name) ||
        typeIdx.get(a.type) - typeIdx.get(b.type),
    );
    const fieldIdx = new Map(fieldList.map((f, i) => [`${f.cls}->${f.name}:${f.type}`, i]));

    const methodList = [...this.methods.values()].sort(
      (a, b) =>
        typeIdx.get(a.cls) - typeIdx.get(b.cls) ||
        strIdx.get(a.name) - strIdx.get(b.name) ||
        protoIdx.get(a.proto.sig) - protoIdx.get(b.proto.sig),
    );
    const methodIdx = new Map(methodList.map((m, i) => [`${m.cls}->${m.name}${m.proto.sig}`, i]));

    const getFieldIdx = (f) => fieldIdx.get(`${f.cls}->${f.name}:${f.type}`);
    const getMethodIdx = (m) => methodIdx.get(`${m.cls}->${m.name}${m.proto.sig}`);

    // Compute fixed header + ID section offsets
    const stringIdsOff = 0x70;
    const typeIdsOff = stringIdsOff + strList.length * 4;
    const protoIdsOff = typeIdsOff + typeList.length * 4;
    const fieldIdsOff = protoIdsOff + protoList.length * 12;
    const methodIdsOff = fieldIdsOff + fieldList.length * 8;
    const classDefsOff = methodIdsOff + methodList.length * 8;
    const dataOff = classDefsOff + this.classes.length * 32;

    // Build data section sequentially (with map_list tracking)
    const data = [];
    const curOff = () => dataOff + data.length;
    const align4 = () => {
      while ((curOff() & 3) !== 0) data.push(0);
    };
    const pushBytes = (arr) => {
      for (let i = 0; i < arr.length; i++) data.push(arr[i]);
    };
    const pushU16 = (v) => data.push(v & 0xff, (v >>> 8) & 0xff);
    const pushU32 = (v) => data.push(v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff);

    const mapItems = [
      { type: 0x0000, count: 1, off: 0 },
      { type: 0x0001, count: strList.length, off: stringIdsOff },
      { type: 0x0002, count: typeList.length, off: typeIdsOff },
      { type: 0x0003, count: protoList.length, off: protoIdsOff },
      { type: 0x0004, count: fieldList.length, off: fieldIdsOff },
      { type: 0x0005, count: methodList.length, off: methodIdsOff },
      { type: 0x0006, count: this.classes.length, off: classDefsOff },
    ];

    // 1. TYPE_CODE_ITEM (0x2001) — 4-byte aligned
    const codeOffsets = new Map();
    let codeCount = 0;
    let codeSecOff = 0;
    for (const c of this.classes) {
      for (const m of [...c.directMethods, ...c.virtualMethods]) {
        if (!m.code) continue;
        align4();
        if (codeCount === 0) codeSecOff = curOff();
        codeOffsets.set(m, curOff());
        codeCount++;

        const code = m.code;
        const units = code.units.slice();
        for (const r of code.refs) {
          if (r.kind === 'str16') units[r.at] = strIdx.get(r.val);
          else if (r.kind === 'str32') {
            const idx = strIdx.get(r.val);
            units[r.at] = idx & 0xffff;
            units[r.at + 1] = (idx >>> 16) & 0xffff;
          } else if (r.kind === 'type16') units[r.at] = typeIdx.get(r.val);
          else if (r.kind === 'field16') units[r.at] = getFieldIdx(r.val);
          else if (r.kind === 'method16') units[r.at] = getMethodIdx(r.val);
          else if (r.kind === 'proto16') units[r.at] = protoIdx.get(r.val.sig);
        }

        pushU16(code.registersSize);
        pushU16(code.insSize);
        pushU16(code.outsSize);
        pushU16(code.tries.length);
        pushU32(0); // debug_info_off = 0
        pushU32(units.length);
        for (let i = 0; i < units.length; i++) pushU16(units[i]);

        if (code.tries.length > 0) {
          if (units.length & 1) pushU16(0); // padding before tries
          // Deduplicate catch handlers
          const handlerBytesList = [];
          const handlerKeyToOff = new Map();
          const tryHandlerOffs = [];
          const handlerBody = [];
          // Reserve space for list size ULEB128 after counting unique handlers
          const uniqueHandlers = [];
          for (const t of code.tries) {
            const enc = [];
            const sz = t.catchAllAddr !== null ? -t.pairs.length : t.pairs.length;
            writeSleb(enc, sz);
            for (const p of t.pairs) {
              writeUleb(enc, typeIdx.get(p.type));
              writeUleb(enc, p.addr);
            }
            if (t.catchAllAddr !== null) writeUleb(enc, t.catchAllAddr);
            const key = enc.join(',');
            let hIdx = handlerKeyToOff.get(key);
            if (hIdx === undefined) {
              hIdx = uniqueHandlers.length;
              handlerKeyToOff.set(key, hIdx);
              uniqueHandlers.push(enc);
            }
            tryHandlerOffs.push(hIdx);
          }
          const cntBytes = [];
          writeUleb(cntBytes, uniqueHandlers.length);
          const idxToRelOff = [];
          let rel = cntBytes.length;
          for (const enc of uniqueHandlers) {
            idxToRelOff.push(rel);
            rel += enc.length;
          }
          for (let i = 0; i < code.tries.length; i++) {
            const t = code.tries[i];
            pushU32(t.startAddr);
            pushU16(t.insnCount);
            pushU16(idxToRelOff[tryHandlerOffs[i]]);
          }
          pushBytes(cntBytes);
          for (const enc of uniqueHandlers) pushBytes(enc);
          void handlerBytesList;
          void handlerBody;
        }
      }
    }
    if (codeCount > 0) mapItems.push({ type: 0x2001, count: codeCount, off: codeSecOff });

    // 2. TYPE_TYPE_LIST (0x1001) — 4-byte aligned (deduplicated)
    const typeListCache = new Map();
    let typeListCount = 0;
    let typeListSecOff = 0;
    const emitTypeList = (list) => {
      if (!list.length) return 0;
      const key = list.join('|');
      if (typeListCache.has(key)) return typeListCache.get(key);
      align4();
      if (typeListCount === 0) typeListSecOff = curOff();
      const off = curOff();
      typeListCount++;
      pushU32(list.length);
      for (const t of list) pushU16(typeIdx.get(t));
      typeListCache.set(key, off);
      return off;
    };
    const protoParamOffs = protoList.map((p) => emitTypeList(p.params));
    const classIfaceOffs = this.classes.map((c) => emitTypeList(c.interfaces));
    if (typeListCount > 0) mapItems.push({ type: 0x1001, count: typeListCount, off: typeListSecOff });

    // 3. TYPE_STRING_DATA_ITEM (0x2002) — unaligned
    const strDataOffs = new Array(strList.length);
    const strSecOff = curOff();
    for (let i = 0; i < strList.length; i++) {
      strDataOffs[i] = curOff();
      pushBytes(encodeMutf8Data(strList[i]));
    }
    if (strList.length > 0) mapItems.push({ type: 0x2002, count: strList.length, off: strSecOff });

    // Helpers for encoding annotation values
    const encodeUintBytes = (val) => {
      const b = [];
      let v = val >>> 0;
      do {
        b.push(v & 0xff);
        v >>>= 8;
      } while (v !== 0);
      return b;
    };
    const emitEncodedValue = (out, v) => {
      if (v.vtype === 0x1e || v.vtype === 0x1f) {
        out.push((v.varg << 5) | v.vtype);
      } else if (v.vtype === 0x1c) {
        out.push(0x1c);
        emitEncodedArrayBytes(out, v.arr);
      } else if (v.vtype === 0x1d) {
        out.push(0x1d);
        emitEncodedAnnotationBytes(out, v.anno);
      } else if (v.vtype === 0x17) {
        const b = encodeUintBytes(strIdx.get(v.str));
        out.push(((b.length - 1) << 5) | 0x17, ...b);
      } else if (v.vtype === 0x18) {
        const b = encodeUintBytes(typeIdx.get(v.type));
        out.push(((b.length - 1) << 5) | 0x18, ...b);
      } else if (v.vtype === 0x19 || v.vtype === 0x1b) {
        const b = encodeUintBytes(getFieldIdx(v.field));
        out.push(((b.length - 1) << 5) | v.vtype, ...b);
      } else if (v.vtype === 0x1a) {
        const b = encodeUintBytes(getMethodIdx(v.method));
        out.push(((b.length - 1) << 5) | 0x1a, ...b);
      } else if (v.vtype === 0x15) {
        const b = encodeUintBytes(protoIdx.get(v.proto.sig));
        out.push(((b.length - 1) << 5) | 0x15, ...b);
      } else {
        out.push((v.varg << 5) | v.vtype);
        for (let i = 0; i < v.raw.length; i++) out.push(v.raw[i]);
      }
    };
    const emitEncodedArrayBytes = (out, arr) => {
      writeUleb(out, arr.length);
      for (const el of arr) emitEncodedValue(out, el);
    };
    const emitEncodedAnnotationBytes = (out, a) => {
      writeUleb(out, typeIdx.get(a.type));
      const elems = [...a.elements].sort((x, y) => strIdx.get(x.name) - strIdx.get(y.name));
      writeUleb(out, elems.length);
      for (const el of elems) {
        writeUleb(out, strIdx.get(el.name));
        emitEncodedValue(out, el.val);
      }
    };

    // 4. TYPE_ANNOTATION_ITEM (0x2004)
    const annoItemOffs = new Map();
    let annoItemCount = 0;
    let annoItemSecOff = 0;
    const emitAnnoItem = (item) => {
      if (annoItemOffs.has(item)) return annoItemOffs.get(item);
      if (annoItemCount === 0) annoItemSecOff = curOff();
      const off = curOff();
      annoItemCount++;
      const buf = [item.visibility];
      emitEncodedAnnotationBytes(buf, item.anno);
      pushBytes(buf);
      annoItemOffs.set(item, off);
      return off;
    };
    const visitSet = (s) => {
      if (s) for (const it of s) emitAnnoItem(it);
    };
    for (const c of this.classes) {
      if (!c.annotations) continue;
      visitSet(c.annotations.classAnnos);
      for (const fa of c.annotations.fieldAnnos) visitSet(fa.set);
      for (const ma of c.annotations.methodAnnos) visitSet(ma.set);
      for (const pa of c.annotations.paramAnnos) for (const s of pa.sets) visitSet(s);
    }
    if (annoItemCount > 0) mapItems.push({ type: 0x2004, count: annoItemCount, off: annoItemSecOff });

    // 5. TYPE_ANNOTATION_SET_ITEM (0x1003) — 4-byte aligned
    const annoSetOffs = new Map();
    let annoSetCount = 0;
    let annoSetSecOff = 0;
    const emitAnnoSet = (set) => {
      if (!set) return 0;
      if (annoSetOffs.has(set)) return annoSetOffs.get(set);
      align4();
      if (annoSetCount === 0) annoSetSecOff = curOff();
      const off = curOff();
      annoSetCount++;
      const sorted = [...set].sort((a, b) => typeIdx.get(a.anno.type) - typeIdx.get(b.anno.type));
      pushU32(sorted.length);
      for (const it of sorted) pushU32(annoItemOffs.get(it));
      annoSetOffs.set(set, off);
      return off;
    };
    for (const c of this.classes) {
      if (!c.annotations) continue;
      emitAnnoSet(c.annotations.classAnnos);
      for (const fa of c.annotations.fieldAnnos) emitAnnoSet(fa.set);
      for (const ma of c.annotations.methodAnnos) emitAnnoSet(ma.set);
      for (const pa of c.annotations.paramAnnos) for (const s of pa.sets) emitAnnoSet(s);
    }
    if (annoSetCount > 0) mapItems.push({ type: 0x1003, count: annoSetCount, off: annoSetSecOff });

    // 6. TYPE_ANNOTATION_SET_REF_LIST (0x1002) — 4-byte aligned
    const paramRefOffs = new Map();
    let refListCount = 0;
    let refListSecOff = 0;
    for (const c of this.classes) {
      if (!c.annotations) continue;
      for (const pa of c.annotations.paramAnnos) {
        align4();
        if (refListCount === 0) refListSecOff = curOff();
        paramRefOffs.set(pa, curOff());
        refListCount++;
        pushU32(pa.sets.length);
        for (const s of pa.sets) pushU32(s ? annoSetOffs.get(s) : 0);
      }
    }
    if (refListCount > 0) mapItems.push({ type: 0x1002, count: refListCount, off: refListSecOff });

    // 7. TYPE_ANNOTATIONS_DIRECTORY_ITEM (0x2006) — 4-byte aligned
    const classAnnoDirOffs = this.classes.map((c) => {
      if (!c.annotations) return 0;
      return -1; // placeholder
    });
    let dirCount = 0;
    let dirSecOff = 0;
    this.classes.forEach((c, ci) => {
      if (!c.annotations) return;
      align4();
      if (dirCount === 0) dirSecOff = curOff();
      classAnnoDirOffs[ci] = curOff();
      dirCount++;
      const a = c.annotations;
      const fa = [...a.fieldAnnos].sort((x, y) => getFieldIdx(x.field) - getFieldIdx(y.field));
      const ma = [...a.methodAnnos].sort((x, y) => getMethodIdx(x.method) - getMethodIdx(y.method));
      const pa = [...a.paramAnnos].sort((x, y) => getMethodIdx(x.method) - getMethodIdx(y.method));
      pushU32(a.classAnnos ? annoSetOffs.get(a.classAnnos) : 0);
      pushU32(fa.length);
      pushU32(ma.length);
      pushU32(pa.length);
      for (const x of fa) {
        pushU32(getFieldIdx(x.field));
        pushU32(annoSetOffs.get(x.set));
      }
      for (const x of ma) {
        pushU32(getMethodIdx(x.method));
        pushU32(annoSetOffs.get(x.set));
      }
      for (const x of pa) {
        pushU32(getMethodIdx(x.method));
        pushU32(paramRefOffs.get(x));
      }
    });
    if (dirCount > 0) mapItems.push({ type: 0x2006, count: dirCount, off: dirSecOff });

    // 8. TYPE_ENCODED_ARRAY_ITEM (0x2005) — unaligned
    const classSvalOffs = new Array(this.classes.length).fill(0);
    let svalCount = 0;
    let svalSecOff = 0;
    this.classes.forEach((c, ci) => {
      if (!c.staticValues) return;
      if (svalCount === 0) svalSecOff = curOff();
      classSvalOffs[ci] = curOff();
      svalCount++;
      const buf = [];
      emitEncodedArrayBytes(buf, c.staticValues);
      pushBytes(buf);
    });
    if (svalCount > 0) mapItems.push({ type: 0x2005, count: svalCount, off: svalSecOff });

    // 9. TYPE_CLASS_DATA_ITEM (0x2000) — unaligned
    const classDataOffs = new Array(this.classes.length).fill(0);
    let cdCount = 0;
    let cdSecOff = 0;
    this.classes.forEach((c, ci) => {
      if (
        !c.staticFields.length &&
        !c.instanceFields.length &&
        !c.directMethods.length &&
        !c.virtualMethods.length
      ) {
        return;
      }
      if (cdCount === 0) cdSecOff = curOff();
      classDataOffs[ci] = curOff();
      cdCount++;
      const sf = [...c.staticFields].sort((a, b) => getFieldIdx(a.field) - getFieldIdx(b.field));
      const inf = [...c.instanceFields].sort((a, b) => getFieldIdx(a.field) - getFieldIdx(b.field));
      const dm = [...c.directMethods].sort((a, b) => getMethodIdx(a.method) - getMethodIdx(b.method));
      const vm = [...c.virtualMethods].sort((a, b) => getMethodIdx(a.method) - getMethodIdx(b.method));
      const buf = [];
      writeUleb(buf, sf.length);
      writeUleb(buf, inf.length);
      writeUleb(buf, dm.length);
      writeUleb(buf, vm.length);
      const emitFields = (list) => {
        let prev = 0;
        for (const f of list) {
          const idx = getFieldIdx(f.field);
          writeUleb(buf, idx - prev);
          writeUleb(buf, f.accessFlags);
          prev = idx;
        }
      };
      const emitMethods = (list) => {
        let prev = 0;
        for (const m of list) {
          const idx = getMethodIdx(m.method);
          writeUleb(buf, idx - prev);
          writeUleb(buf, m.accessFlags);
          writeUleb(buf, m.code ? codeOffsets.get(m) : 0);
          prev = idx;
        }
      };
      emitFields(sf);
      emitFields(inf);
      emitMethods(dm);
      emitMethods(vm);
      pushBytes(buf);
    });
    if (cdCount > 0) mapItems.push({ type: 0x2000, count: cdCount, off: cdSecOff });

    // 10. TYPE_MAP_LIST (0x1000) — 4-byte aligned
    align4();
    const mapOff = curOff();
    mapItems.push({ type: 0x1000, count: 1, off: mapOff });
    pushU32(mapItems.length);
    for (const item of mapItems) {
      pushU16(item.type);
      pushU16(0);
      pushU32(item.count);
      pushU32(item.off);
    }
    align4();

    const fileSize = dataOff + data.length;
    const out = new Uint8Array(fileSize);
    const v = new DataView(out.buffer);

    // Header (112 bytes)
    out.set(te.encode('dex\n037\0'), 0);
    v.setUint32(32, fileSize, true);
    v.setUint32(36, 0x70, true);
    v.setUint32(40, 0x12345678, true);
    v.setUint32(52, mapOff, true);
    v.setUint32(56, strList.length, true);
    v.setUint32(60, strList.length ? stringIdsOff : 0, true);
    v.setUint32(64, typeList.length, true);
    v.setUint32(68, typeList.length ? typeIdsOff : 0, true);
    v.setUint32(72, protoList.length, true);
    v.setUint32(76, protoList.length ? protoIdsOff : 0, true);
    v.setUint32(80, fieldList.length, true);
    v.setUint32(84, fieldList.length ? fieldIdsOff : 0, true);
    v.setUint32(88, methodList.length, true);
    v.setUint32(92, methodList.length ? methodIdsOff : 0, true);
    v.setUint32(96, this.classes.length, true);
    v.setUint32(100, this.classes.length ? classDefsOff : 0, true);
    v.setUint32(104, data.length, true);
    v.setUint32(108, dataOff, true);

    // ID tables
    for (let i = 0; i < strList.length; i++) v.setUint32(stringIdsOff + 4 * i, strDataOffs[i], true);
    for (let i = 0; i < typeList.length; i++) v.setUint32(typeIdsOff + 4 * i, strIdx.get(typeList[i]), true);
    for (let i = 0; i < protoList.length; i++) {
      const p = protoList[i];
      const o = protoIdsOff + 12 * i;
      v.setUint32(o, strIdx.get(p.shorty), true);
      v.setUint32(o + 4, typeIdx.get(p.ret), true);
      v.setUint32(o + 8, protoParamOffs[i], true);
    }
    for (let i = 0; i < fieldList.length; i++) {
      const f = fieldList[i];
      const o = fieldIdsOff + 8 * i;
      v.setUint16(o, typeIdx.get(f.cls), true);
      v.setUint16(o + 2, typeIdx.get(f.type), true);
      v.setUint32(o + 4, strIdx.get(f.name), true);
    }
    for (let i = 0; i < methodList.length; i++) {
      const m = methodList[i];
      const o = methodIdsOff + 8 * i;
      v.setUint16(o, typeIdx.get(m.cls), true);
      v.setUint16(o + 2, protoIdx.get(m.proto.sig), true);
      v.setUint32(o + 4, strIdx.get(m.name), true);
    }
    for (let i = 0; i < this.classes.length; i++) {
      const c = this.classes[i];
      const o = classDefsOff + 32 * i;
      v.setUint32(o, typeIdx.get(c.cls), true);
      v.setUint32(o + 4, c.accessFlags, true);
      v.setUint32(o + 8, c.superCls ? typeIdx.get(c.superCls) : 0xffffffff, true);
      v.setUint32(o + 12, classIfaceOffs[i], true);
      v.setUint32(o + 16, c.sourceFile ? strIdx.get(c.sourceFile) : 0xffffffff, true);
      v.setUint32(o + 20, classAnnoDirOffs[i], true);
      v.setUint32(o + 24, classDataOffs[i], true);
      v.setUint32(o + 28, classSvalOffs[i], true);
    }

    out.set(data, dataOff);
    await fixDexHashes(out);
    return out;
  }
}

// --- In-place source DEX patching (PhoenixLaunch + class_def/class_data removal) --------

function skipClassDataItem(u8, p) {
  let sf, inf, dm, vm;
  [sf, p] = readUleb(u8, p);
  [inf, p] = readUleb(u8, p);
  [dm, p] = readUleb(u8, p);
  [vm, p] = readUleb(u8, p);
  for (let i = 0; i < sf + inf; i++) {
    [, p] = readUleb(u8, p);
    [, p] = readUleb(u8, p);
  }
  for (let i = 0; i < dm + vm; i++) {
    [, p] = readUleb(u8, p);
    [, p] = readUleb(u8, p);
    [, p] = readUleb(u8, p);
  }
  return p;
}

function removeClassDefsInPlace(dexBytes, dropClassIdxSet) {
  const d = new Uint8Array(dexBytes);
  const v = new DataView(d.buffer);
  const cSz = v.getUint32(96, true);
  const cOff = v.getUint32(100, true);
  const mapOff = v.getUint32(52, true);

  const dropCdata = new Set();
  const kept = [];
  for (let c = 0; c < cSz; c++) {
    const o = cOff + 32 * c;
    if (dropClassIdxSet.has(c)) {
      const cdata = v.getUint32(o + 24, true);
      if (cdata) dropCdata.add(cdata);
    } else {
      kept.push(d.slice(o, o + 32));
    }
  }

  // Locate TYPE_CLASS_DEF_ITEM (0x0006) and TYPE_CLASS_DATA_ITEM (0x2000) in map_list
  const mapCnt = v.getUint32(mapOff, true);
  let cdefMapOff = -1;
  let cdMapOff = -1;
  let cdCnt = 0;
  let cdOff = 0;
  for (let i = 0; i < mapCnt; i++) {
    const mo = mapOff + 4 + 12 * i;
    const tp = v.getUint16(mo, true);
    if (tp === 0x0006) cdefMapOff = mo;
    else if (tp === 0x2000) {
      cdMapOff = mo;
      cdCnt = v.getUint32(mo + 4, true);
      cdOff = v.getUint32(mo + 8, true);
    }
  }
  if (cdefMapOff < 0 || cdMapOff < 0) throw new Error('DEX map_list missing class_def/class_data');

  // Compact TYPE_CLASS_DATA_ITEM in place and record old->new offsets
  let pos = cdOff;
  const compacted = [];
  const oldToNewCd = new Map();
  let newCdCnt = 0;
  let writeLen = 0;
  for (let i = 0; i < cdCnt; i++) {
    const start = pos;
    const end = skipClassDataItem(d, pos);
    if (!dropCdata.has(start)) {
      oldToNewCd.set(start, cdOff + writeLen);
      const slice = d.slice(start, end);
      compacted.push(slice);
      writeLen += slice.length;
      newCdCnt++;
    }
    pos = end;
  }
  const origCdEnd = pos;
  let wp = cdOff;
  for (const s of compacted) {
    d.set(s, wp);
    wp += s.length;
  }
  d.fill(0, wp, origCdEnd);

  // Rewrite compacted class_defs with updated class_data_off
  for (let i = 0; i < kept.length; i++) {
    const entry = kept[i];
    const ev = new DataView(entry.buffer);
    const oldCd = ev.getUint32(24, true);
    if (oldCd) ev.setUint32(24, oldToNewCd.get(oldCd), true);
    d.set(entry, cOff + 32 * i);
  }
  d.fill(0, cOff + 32 * kept.length, cOff + 32 * cSz);

  v.setUint32(96, kept.length, true);
  v.setUint32(cdefMapOff + 4, kept.length, true);
  v.setUint32(cdMapOff + 4, newCdCnt, true);
  return d;
}

function patchPhoenixLaunchInPlace(d) {
  const v = new DataView(d.buffer, d.byteOffset, d.byteLength);
  const sSz = v.getUint32(56, true);
  const sOff = v.getUint32(60, true);
  const pOff = v.getUint32(76, true);
  const mOff = v.getUint32(92, true);
  const cSz = v.getUint32(96, true);
  const cOff = v.getUint32(100, true);

  const strStart = (sid) => {
    let p = v.getUint32(sOff + 4 * sid, true);
    [, p] = readUleb(d, p);
    return p;
  };
  const startsWith = (pos, needle) => {
    for (let i = 0; i < needle.length; i++) if (d[pos + i] !== needle[i]) return false;
    return true;
  };

  const sids = new Set();
  for (let i = 0; i < sSz; i++) if (startsWith(strStart(i), PHOENIX_ANCHOR)) sids.add(i);
  if (!sids.size) return false;

  const hits = new Map();
  for (let c = 0; c < cSz; c++) {
    const cdata = v.getUint32(cOff + 32 * c + 24, true);
    if (!cdata) continue;
    let p = cdata;
    let sf, inf, dm, vm;
    [sf, p] = readUleb(d, p);
    [inf, p] = readUleb(d, p);
    [dm, p] = readUleb(d, p);
    [vm, p] = readUleb(d, p);
    for (let i = 0; i < sf + inf; i++) {
      [, p] = readUleb(d, p);
      [, p] = readUleb(d, p);
    }
    let midx = 0;
    for (let k = 0; k < dm + vm; k++) {
      if (k === dm) midx = 0;
      let diff, code;
      [diff, p] = readUleb(d, p);
      [, p] = readUleb(d, p);
      [code, p] = readUleb(d, p);
      midx += diff;
      if (!code) continue;
      const regs = v.getUint16(code, true);
      const n = v.getUint32(code + 12, true);
      const insns = code + 16;
      for (let i = 0; i < n; i++) {
        const op = d[insns + 2 * i];
        if (op === 0x1a && i + 1 < n && sids.has(v.getUint16(insns + 2 * i + 2, true))) {
          hits.set(insns, { midx, regs, insns });
        } else if (op === 0x1b && i + 2 < n && sids.has(v.getUint32(insns + 2 * i + 2, true))) {
          hits.set(insns, { midx, regs, insns });
        }
      }
    }
  }
  if (hits.size !== 1) throw new Error(`PhoenixLaunch anchor: expected 1 method, got ${hits.size}`);
  const { midx, regs, insns } = [...hits.values()][0];
  const proto = v.getUint16(mOff + 8 * midx + 2, true);
  const shorty = d[strStart(v.getUint32(pOff + 12 * proto, true))];
  if (shorty !== 0x5a /* 'Z' */ || regs < 1) throw new Error('PhoenixLaunch anchor method does not return boolean');
  d.set([0x12, 0x10, 0x0f, 0x00], insns); // const/4 v0, #1; return v0
  return true;
}

// --- Main entry point -------------------------------------------------------------------

/**
 * Patch all `classes*.dex` buffers for Cinder Mobile:
 * - Patches `PhoenixLaunch` boolean gate if present (Play Store alpha builds)
 * - Extracts `<application>`, `GuestHost`, and all `WasmRuntime.nativeDispatch` callers
 *   out of their source DEX(es) and emits a new mini-DEX containing the Cinder-hooked classes.
 *
 * @param {Array<{name:string, data:Uint8Array}>} dexFiles
 * @param {string} applicationClassName  Java class name from AndroidManifest.xml (e.g. `com.discord.MainApplication`)
 * @param {(msg:string)=>void} log
 * @returns {Promise<{patchedDexes: Map<string, Uint8Array>, hooksDex: Uint8Array}>}
 */
export async function patchDexesForCinder(dexFiles, applicationClassName, log = () => {}) {
  const appDesc = applicationClassName
    ? `L${applicationClassName.replace(/\./g, '/')};`
    : 'Lcom/discord/nativeapp/NativeApp;';

  const extractedClasses = [];
  const patchedDexes = new Map();
  let foundApp = false;
  let foundGuestHost = false;
  let propNamesField = null;
  let totalNativeDispatchSites = 0;

  // Pass 1: discover ViewWire prop_names field across all DEX files
  for (const { data } of dexFiles) {
    if (propNamesField) break;
    const r = new DexReader(data);
    let markerSid = -1;
    for (let i = 0; i < r.stringIdsSize; i++) {
      if (r.str(i).includes(PROP_NAMES_MARKER)) {
        markerSid = i;
        break;
      }
    }
    if (markerSid < 0) continue;

    for (let c = 0; c < r.classDefsSize; c++) {
      const cdataOff = r.v.getUint32(r.classDefsOff + 32 * c + 24, true);
      if (!cdataOff) continue;
      const cd = r.readClassData(cdataOff);
      const clsName = r.type(r.v.getUint32(r.classDefsOff + 32 * c, true));
      let hasMarker = false;
      for (const m of [...cd.directMethods, ...cd.virtualMethods]) {
        if (!m.code) continue;
        if (m.code.refs.some((ref) => (ref.kind === 'str16' || ref.kind === 'str32') && ref.val.includes(PROP_NAMES_MARKER))) {
          hasMarker = true;
          break;
        }
      }
      if (!hasMarker) continue;
      for (const m of [...cd.directMethods, ...cd.virtualMethods]) {
        if (!m.code) continue;
        for (const ref of m.code.refs) {
          if (
            ref.kind === 'field16' &&
            ref.val.type === '[Ljava/lang/String;' &&
            ref.val.cls !== clsName &&
            (m.code.units[ref.at - 1] & 0xff) === 0x62
          ) {
            propNamesField = ref.val;
            break;
          }
        }
        if (propNamesField) break;
      }
      if (propNamesField) break;
    }
  }

  // Pass 2: scan each DEX for PhoenixLaunch, Application, GuestHost, and nativeDispatch callers
  for (const { name, data } of dexFiles) {
    const r = new DexReader(data);
    const dropSet = new Set();

    // Find WasmRuntime.nativeDispatch(J[B)Z method_id in this DEX (if referenced)
    let nativeDispatchMid = -1;
    for (let i = 0; i < r.methodIdsSize; i++) {
      const m = r.method(i);
      if (m.cls === WASM_RUNTIME_DESC && m.name === 'nativeDispatch' && m.proto.sig === '(J[B)Z') {
        nativeDispatchMid = i;
        break;
      }
    }

    for (let c = 0; c < r.classDefsSize; c++) {
      const clsName = r.type(r.v.getUint32(r.classDefsOff + 32 * c, true));
      let needExtract = clsName === appDesc || clsName === GUEST_HOST_DESC;

      // Fast bytecode scan for nativeDispatch call sites
      if (!needExtract && nativeDispatchMid >= 0) {
        const cdataOff = r.v.getUint32(r.classDefsOff + 32 * c + 24, true);
        if (cdataOff) {
          let p = cdataOff;
          let sf, inf, dm, vm;
          [sf, p] = readUleb(data, p);
          [inf, p] = readUleb(data, p);
          [dm, p] = readUleb(data, p);
          [vm, p] = readUleb(data, p);
          for (let i = 0; i < sf + inf; i++) {
            [, p] = readUleb(data, p);
            [, p] = readUleb(data, p);
          }
          outer: for (let k = 0; k < dm + vm; k++) {
            let codeOff;
            [, p] = readUleb(data, p);
            [, p] = readUleb(data, p);
            [codeOff, p] = readUleb(data, p);
            if (!codeOff) continue;
            const n = r.v.getUint32(codeOff + 12, true);
            const ins = codeOff + 16;
            for (let i = 0; i < n - 2; i++) {
              const op = data[ins + 2 * i];
              if ((op === 0x71 || op === 0x77) && r.v.getUint16(ins + 2 * i + 2, true) === nativeDispatchMid) {
                needExtract = true;
                break outer;
              }
            }
          }
        }
      }

      if (!needExtract) continue;
      const clsObj = r.extractClass(c);
      dropSet.add(c);

      if (clsName === appDesc) {
        patchApplicationClass(clsObj, log);
        foundApp = true;
      }
      if (clsName === GUEST_HOST_DESC) {
        patchGuestHostClass(clsObj, propNamesField, log);
        foundGuestHost = true;
      }
      if (nativeDispatchMid >= 0) {
        const n = patchNativeDispatchInClass(clsObj);
        if (n > 0) {
          totalNativeDispatchSites += n;
          log(`dex: redirected ${n} WasmRuntime.nativeDispatch site(s) in ${clsName}`);
        }
      }
      extractedClasses.push(clsObj);
    }

    let modified = null;
    if (dropSet.size > 0) {
      modified = removeClassDefsInPlace(data, dropSet);
    }
    const checkBuf = modified || new Uint8Array(data);
    const didPhoenix = patchPhoenixLaunchInPlace(checkBuf);
    if (didPhoenix) {
      modified = checkBuf;
      log(`dex: ${name} -> PhoenixLaunch forced on`);
    }
    if (modified) {
      await fixDexHashes(modified);
      patchedDexes.set(name, modified);
    }
  }

  if (!foundApp) throw new Error(`Application class ${appDesc} not found in APK DEX files`);
  if (!foundGuestHost) throw new Error(`GuestHost (${GUEST_HOST_DESC}) not found in APK DEX files`);
  if (totalNativeDispatchSites === 0) {
    throw new Error('WasmRuntime.nativeDispatch(J[B)Z call site not found in APK DEX files');
  }

  const builder = new DexBuilder(extractedClasses);
  const hooksDex = await builder.build();
  log(`dex: emitted Cinder hooks mini-DEX (${extractedClasses.length} classes, ${(hooksDex.length / 1024).toFixed(1)} KB)`);

  return { patchedDexes, hooksDex };
}
