use crate::maps::{Module, Segment};
use std::collections::HashMap;

const FOLLOWERS: usize = 64;
const PROLOGUE_WINDOW: usize = 32;
const EXACT_PROLOGUE_LEN: usize = 16;

#[derive(Debug, Clone)]
pub struct Pin {
    pub literal_addr: usize,

    pub adrp_pc: usize,

    pub add_pc: Option<usize>,

    pub fn_hint: usize,

    pub fn_exact: bool,

    pub prologue: Vec<u8>,
}

unsafe fn bytes(ptr: usize, len: usize) -> Option<&'static [u8]> {
    if ptr == 0 || len == 0 {
        return None;
    }
    Some(std::slice::from_raw_parts(ptr as *const u8, len))
}

fn find_in_segments(segs: &[&Segment], needle: &[u8]) -> Vec<usize> {
    let mut out = Vec::new();
    if needle.is_empty() {
        return out;
    }
    let first = needle[0];
    let nlen = needle.len();
    for seg in segs {
        if !seg.readable() {
            continue;
        }

        let len = (seg.end.saturating_sub(seg.start)).min(seg.filesz as usize);
        let buf = match unsafe { bytes(seg.start, len) } {
            Some(b) => b,
            None => continue,
        };
        let mut pos = 0usize;
        while pos + nlen <= buf.len() {
            let Some(rel) = buf[pos..buf.len() - nlen + 1]
                .iter()
                .position(|&b| b == first)
            else {
                break;
            };
            let idx = pos + rel;
            if &buf[idx..idx + nlen] == needle {
                out.push(seg.start + idx);
                pos = idx + nlen;
            } else {
                pos = idx + 1;
            }
        }
    }
    out
}

pub fn find_literal(module: &Module, needle: &[u8]) -> Vec<usize> {
    let all: Vec<&Segment> = module.segments.iter().collect();
    find_in_segments(&all, needle)
}

fn sign_extend21(v: u32) -> i64 {
    let base = (v & 0x1f_ffff) as i64;
    if v & 0x10_0000 != 0 {
        base - (1i64 << 21)
    } else {
        base
    }
}

fn decode_adrp(word: u32, pc: usize) -> Option<usize> {
    if word & 0x9f00_0000 != 0x9000_0000 {
        return None;
    }
    let immlo = (word >> 29) & 0x3;
    let immhi = (word >> 5) & 0x7_ffff;
    let imm = sign_extend21((immhi << 2) | immlo);
    let page = (pc & !0xfff).wrapping_add((imm << 12) as isize as usize);
    Some(page)
}

fn decode_add_imm(word: u32, base_reg: u32) -> Option<u32> {
    if word & 0xffc0_0000 != 0x9100_0000 {
        return None;
    }
    let rn = (word >> 5) & 0x1f;
    if rn != base_reg {
        return None;
    }
    Some((word >> 10) & 0xfff)
}

pub fn xref_pins_batch(module: &Module, hits_per_claim: &[Vec<usize>]) -> Vec<Vec<Pin>> {
    let mut out: Vec<Vec<Pin>> = vec![Vec::new(); hits_per_claim.len()];
    let mut by_page: HashMap<usize, Vec<(usize, usize)>> = HashMap::new();
    for (claim_idx, hits) in hits_per_claim.iter().enumerate() {
        for &addr in hits {
            by_page
                .entry(addr & !0xfff)
                .or_default()
                .push((claim_idx, addr));
        }
    }
    if by_page.is_empty() {
        return out;
    }

    for seg in module.text() {
        let len = (seg.end.saturating_sub(seg.start)).min(seg.filesz as usize);
        let buf = match unsafe { bytes(seg.start, len) } {
            Some(b) => b,
            None => continue,
        };
        let n = buf.len() / 4;
        for i in 0..n {
            let off = i * 4;
            let word = u32::from_le_bytes([buf[off], buf[off + 1], buf[off + 2], buf[off + 3]]);
            let pc = seg.start + off;
            let Some(page) = decode_adrp(word, pc) else {
                continue;
            };
            let Some(targets) = by_page.get(&page) else {
                continue;
            };
            let rd = word & 0x1f;
            let end = (i + FOLLOWERS).min(n);
            for j in (i + 1)..end {
                let woff = j * 4;
                let w =
                    u32::from_le_bytes([buf[woff], buf[woff + 1], buf[woff + 2], buf[woff + 3]]);
                let Some(imm12) = decode_add_imm(w, rd) else {
                    if (w & 0x9f00_0000 == 0x9000_0000) && (w & 0x1f == rd) {
                        break;
                    }
                    continue;
                };
                let resolved = page.wrapping_add(imm12 as usize);
                for &(claim_idx, literal_addr) in targets {
                    if resolved == literal_addr {
                        let (fn_hint, fn_exact, prologue) = if let Some((fn_start, fn_end)) =
                            module.find_function(pc)
                        {
                            let plen = fn_end.saturating_sub(fn_start).min(EXACT_PROLOGUE_LEN);
                            let pro = if module.contains_readable(fn_start, plen) {
                                unsafe {
                                    std::slice::from_raw_parts(fn_start as *const u8, plen).to_vec()
                                }
                            } else {
                                Vec::new()
                            };
                            (fn_start, true, pro)
                        } else {
                            let window = PROLOGUE_WINDOW.min(off);
                            (pc - window, false, buf[off - window..off].to_vec())
                        };
                        out[claim_idx].push(Pin {
                            literal_addr,
                            adrp_pc: pc,
                            add_pc: Some(seg.start + woff),
                            fn_hint,
                            fn_exact,
                            prologue,
                        });
                    }
                }

                if (w & 0x1f) == rd {
                    break;
                }
            }
        }
    }
    out
}

#[allow(dead_code)]
pub fn xref_pins(module: &Module, literal_addr: usize) -> Vec<Pin> {
    let mut batch = xref_pins_batch(module, &[vec![literal_addr]]);
    batch.pop().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode_adrp(pc: usize, target_page: usize, rd: u32) -> u32 {
        let pages = ((target_page as i64) - ((pc & !0xfff) as i64)) >> 12;
        let imm = (pages as u32) & 0x1f_ffff;
        let immlo = imm & 0x3;
        let immhi = (imm >> 2) & 0x7_ffff;
        0x9000_0000 | (immlo << 29) | (immhi << 5) | (rd & 0x1f)
    }

    fn encode_add_imm(dst: u32, rn: u32, imm12: u32) -> u32 {
        0x9100_0000 | ((imm12 & 0xfff) << 10) | ((rn & 0x1f) << 5) | (dst & 0x1f)
    }

    #[test]
    fn decode_adrp_handles_positive_and_negative_pages() {
        let pc = 0x72a7_b3e0usize;
        let rodata_page = 0x7027_6000usize;
        let word = encode_adrp(pc, rodata_page, 8);
        assert_eq!(decode_adrp(word, pc), Some(rodata_page));

        let cstring_page = 0x7800_1000usize;
        let word_pos = encode_adrp(pc, cstring_page, 0);
        assert_eq!(decode_adrp(word_pos, pc), Some(cstring_page));

        let same_page = pc & !0xfff;
        assert_eq!(
            decode_adrp(encode_adrp(pc, same_page, 1), pc),
            Some(same_page)
        );
    }

    #[test]
    fn decode_add_imm_accepts_same_and_different_dst() {
        assert_eq!(decode_add_imm(encode_add_imm(8, 8, 0x9cc), 8), Some(0x9cc));

        assert_eq!(decode_add_imm(encode_add_imm(0, 8, 0x9cc), 8), Some(0x9cc));

        assert_eq!(decode_add_imm(encode_add_imm(0, 9, 0x9cc), 8), None);

        assert_eq!(
            decode_add_imm(encode_add_imm(0, 8, 0x1) | (1 << 22), 8),
            None
        );
    }
}
