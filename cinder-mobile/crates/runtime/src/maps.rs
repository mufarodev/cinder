use std::fs;

const PT_LOAD: u32 = 1;
const PT_NOTE: u32 = 4;
const PT_GNU_EH_FRAME: u32 = 0x6474_e550;
const NT_GNU_BUILD_ID: u32 = 3;

#[derive(Debug, Clone)]
pub struct Segment {
    pub start: usize,
    pub end: usize,
    pub offset: u64,
    pub filesz: u64,
    pub perms: String,
    pub path: String,
}

impl Segment {
    pub fn executable(&self) -> bool {
        self.perms.as_bytes().get(2) == Some(&b'x')
    }
    pub fn readable(&self) -> bool {
        self.perms.as_bytes().get(0) == Some(&b'r')
    }
    pub fn writable(&self) -> bool {
        self.perms.as_bytes().get(1) == Some(&b'w')
    }
}

#[derive(Debug, Clone, Copy)]
pub struct EhFrameHdr {
    pub addr: usize,
    pub len: usize,
}

#[derive(Debug, Clone)]
pub struct Module {
    pub name: String,

    pub base: usize,
    pub segments: Vec<Segment>,

    pub build_id: Option<String>,

    pub eh_frame_hdr: Option<EhFrameHdr>,
}

impl Module {
    pub fn text(&self) -> Vec<&Segment> {
        self.segments
            .iter()
            .filter(|s| s.executable() && s.readable())
            .collect()
    }

    pub fn rodata(&self) -> Vec<&Segment> {
        self.segments
            .iter()
            .filter(|s| s.readable() && !s.executable() && !s.writable())
            .collect()
    }

    pub fn non_exec_readable(&self) -> Vec<&Segment> {
        self.segments
            .iter()
            .filter(|s| s.readable() && !s.executable())
            .collect()
    }

    pub fn contains_readable(&self, addr: usize, len: usize) -> bool {
        let Some(end) = addr.checked_add(len) else {
            return false;
        };
        if addr == 0 || len == 0 {
            return false;
        }
        self.segments.iter().any(|seg| {
            if !seg.readable() {
                return false;
            }
            let file_backed_end = seg.start.saturating_add(seg.filesz as usize).min(seg.end);
            addr >= seg.start && end <= file_backed_end
        })
    }

    pub fn file_offset_to_addr(&self, file_off: u64, len: usize) -> Option<usize> {
        let len_u64 = len as u64;
        let file_end = file_off.checked_add(len_u64)?;
        for seg in &self.segments {
            if !seg.readable() {
                continue;
            }
            let seg_file_end = seg.offset.checked_add(seg.filesz)?;
            if file_off >= seg.offset && file_end <= seg_file_end {
                let delta = usize::try_from(file_off - seg.offset).ok()?;
                let addr = seg.start.checked_add(delta)?;
                let addr_end = addr.checked_add(len)?;
                if addr_end <= seg.end {
                    return Some(addr);
                }
            }
        }
        None
    }

    pub fn resolve_writable_vaddr(&self, vaddr: u64) -> Option<usize> {
        let off = usize::try_from(vaddr).ok()?;
        let addr = self.base.checked_add(off)?;
        let ps = page_size();
        for seg in &self.segments {
            if !seg.writable() {
                continue;
            }
            let page_end = page_align_up(seg.end, ps);
            if addr >= seg.start && addr < page_end {
                return Some(addr);
            }
        }
        None
    }

    pub fn find_function(&self, pc: usize) -> Option<(usize, usize)> {
        let hdr = self.eh_frame_hdr?;
        if hdr.len < 12 || !self.contains_readable(hdr.addr, hdr.len) {
            return None;
        }
        let hdr_bytes = unsafe { std::slice::from_raw_parts(hdr.addr as *const u8, hdr.len) };
        let (fn_start, fde_addr, next_fn) = lookup_eh_frame_hdr(hdr_bytes, hdr.addr, pc)?;

        let mut fn_end = next_fn;
        if self.contains_readable(fde_addr, 16) {
            let fde = unsafe { std::slice::from_raw_parts(fde_addr as *const u8, 16) };
            let length = u32::from_le_bytes([fde[0], fde[1], fde[2], fde[3]]);
            let cie_ptr = u32::from_le_bytes([fde[4], fde[5], fde[6], fde[7]]);
            if length != 0 && length != 0xffff_ffff && cie_ptr != 0 {
                let pc_range = u32::from_le_bytes([fde[12], fde[13], fde[14], fde[15]]) as usize;
                if pc_range > 0 {
                    fn_end = Some(fn_start.saturating_add(pc_range));
                }
            }
        }

        let end = fn_end?;
        if pc >= fn_start && pc < end {
            Some((fn_start, end))
        } else {
            None
        }
    }
}

pub fn parse_gnu_build_id(notes: &[u8]) -> Option<String> {
    let mut pos = 0usize;
    while pos + 12 <= notes.len() {
        let namesz =
            u32::from_le_bytes([notes[pos], notes[pos + 1], notes[pos + 2], notes[pos + 3]])
                as usize;
        let descsz = u32::from_le_bytes([
            notes[pos + 4],
            notes[pos + 5],
            notes[pos + 6],
            notes[pos + 7],
        ]) as usize;
        let ntype = u32::from_le_bytes([
            notes[pos + 8],
            notes[pos + 9],
            notes[pos + 10],
            notes[pos + 11],
        ]);
        let name_start = pos + 12;
        let name_end = name_start.checked_add(namesz)?;
        let desc_start = name_start.checked_add((namesz.checked_add(3)?) & !3)?;
        let desc_end = desc_start.checked_add(descsz)?;
        if desc_end > notes.len() {
            break;
        }
        let name = &notes[name_start..name_end];
        if ntype == NT_GNU_BUILD_ID && (name == b"GNU\0" || name == b"GNU") {
            let mut hex = String::with_capacity(descsz * 2);
            for &b in &notes[desc_start..desc_end] {
                use std::fmt::Write;
                let _ = write!(&mut hex, "{b:02x}");
            }
            return Some(hex);
        }
        pos = desc_start.checked_add((descsz.checked_add(3)?) & !3)?;
    }
    None
}

pub fn lookup_eh_frame_hdr(
    hdr: &[u8],
    hdr_addr: usize,
    pc: usize,
) -> Option<(usize, usize, Option<usize>)> {
    if hdr.len() < 12 || hdr[0] != 1 {
        return None;
    }
    let eh_ptr_enc = hdr[1];
    let fde_cnt_enc = hdr[2];
    let tab_enc = hdr[3];

    let eh_ptr_size = match eh_ptr_enc & 0x0f {
        0x03 | 0x0b => 4usize,
        0x00 | 0x04 | 0x0c => 8usize,
        _ if eh_ptr_enc == 0xff => 0usize,
        _ => return None,
    };
    let cnt_off = 4 + eh_ptr_size;
    if cnt_off + 4 > hdr.len() {
        return None;
    }
    let fde_count = match fde_cnt_enc & 0x0f {
        0x03 | 0x0b => u32::from_le_bytes([
            hdr[cnt_off],
            hdr[cnt_off + 1],
            hdr[cnt_off + 2],
            hdr[cnt_off + 3],
        ]) as usize,
        _ => return None,
    };
    let tab_off = cnt_off + 4;
    if fde_count == 0 {
        return None;
    }
    let signed_entries = match tab_enc {
        0x3b => true,
        0x33 => false,
        _ => return None,
    };
    let tab_end = tab_off.checked_add(fde_count.checked_mul(8)?)?;
    if tab_end > hdr.len() {
        return None;
    }

    let read_entry = |idx: usize| -> (usize, usize) {
        let p = tab_off + idx * 8;
        let a = u32::from_le_bytes([hdr[p], hdr[p + 1], hdr[p + 2], hdr[p + 3]]);
        let b = u32::from_le_bytes([hdr[p + 4], hdr[p + 5], hdr[p + 6], hdr[p + 7]]);
        if signed_entries {
            (
                hdr_addr.wrapping_add((a as i32) as isize as usize),
                hdr_addr.wrapping_add((b as i32) as isize as usize),
            )
        } else {
            (
                hdr_addr.wrapping_add(a as usize),
                hdr_addr.wrapping_add(b as usize),
            )
        }
    };

    let mut lo = 0usize;
    let mut hi = fde_count;
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        let (fn_start, _) = read_entry(mid);
        if fn_start <= pc {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    if lo == 0 {
        return None;
    }
    let idx = lo - 1;
    let (fn_start, fde_addr) = read_entry(idx);
    let next_fn = if idx + 1 < fde_count {
        Some(read_entry(idx + 1).0)
    } else {
        None
    };
    Some((fn_start, fde_addr, next_fn))
}

#[repr(C)]
struct Elf64Phdr {
    p_type: u32,
    p_flags: u32,
    p_offset: u64,
    p_vaddr: u64,
    p_paddr: u64,
    p_filesz: u64,
    p_memsz: u64,
    p_align: u64,
}

#[repr(C)]
struct DlPhdrInfo {
    dlpi_addr: usize,
    dlpi_name: *const std::ffi::c_char,
    dlpi_phdr: *const Elf64Phdr,
    dlpi_phnum: u16,
}

extern "C" {
    fn getpagesize() -> i32;
    fn dl_iterate_phdr(
        callback: unsafe extern "C" fn(*mut DlPhdrInfo, usize, *mut core::ffi::c_void) -> i32,
        data: *mut core::ffi::c_void,
    ) -> i32;
}

struct PhdrSearch<'a> {
    needle: &'a str,
    out: Option<Module>,
}

unsafe extern "C" fn phdr_callback(
    info: *mut DlPhdrInfo,
    _size: usize,
    data: *mut core::ffi::c_void,
) -> i32 {
    let info = &*info;
    let ctx = &mut *(data as *mut PhdrSearch<'_>);
    if ctx.out.is_some() || info.dlpi_name.is_null() {
        return 0;
    }
    let name = std::ffi::CStr::from_ptr(info.dlpi_name).to_string_lossy();
    if !name.contains(ctx.needle) {
        return 0;
    }
    let mut segs = Vec::new();
    let mut note_ranges: Vec<(usize, usize)> = Vec::new();
    let mut eh_frame_hdr: Option<EhFrameHdr> = None;

    for i in 0..(info.dlpi_phnum as usize) {
        let ph = &*info.dlpi_phdr.add(i);
        match ph.p_type {
            PT_LOAD => {
                let start = info.dlpi_addr.wrapping_add(ph.p_vaddr as usize);
                let mut perms = String::with_capacity(3);
                perms.push(if ph.p_flags & 4 != 0 { 'r' } else { '-' });
                perms.push(if ph.p_flags & 2 != 0 { 'w' } else { '-' });
                perms.push(if ph.p_flags & 1 != 0 { 'x' } else { '-' });
                segs.push(Segment {
                    start,
                    end: start.saturating_add(ph.p_memsz as usize),
                    offset: ph.p_offset,
                    filesz: ph.p_filesz,
                    perms,
                    path: name.to_string(),
                });
            }
            PT_NOTE => {
                let len = ph.p_filesz.min(ph.p_memsz) as usize;
                if len >= 12 && len <= 65536 {
                    let addr = info.dlpi_addr.wrapping_add(ph.p_vaddr as usize);
                    note_ranges.push((addr, len));
                }
            }
            PT_GNU_EH_FRAME => {
                let len = ph.p_filesz.min(ph.p_memsz) as usize;
                if len >= 12 {
                    let addr = info.dlpi_addr.wrapping_add(ph.p_vaddr as usize);
                    eh_frame_hdr = Some(EhFrameHdr { addr, len });
                }
            }
            _ => {}
        }
    }

    if !segs.is_empty() {
        let mut module = Module {
            name: name.to_string(),
            base: info.dlpi_addr,
            segments: segs,
            build_id: None,
            eh_frame_hdr,
        };
        for (addr, len) in note_ranges {
            if module.contains_readable(addr, len) {
                let slice = std::slice::from_raw_parts(addr as *const u8, len);
                if let Some(bid) = parse_gnu_build_id(slice) {
                    module.build_id = Some(bid);
                    break;
                }
            }
        }
        ctx.out = Some(module);
        return 1;
    }
    0
}

pub fn find_module(basename: &str) -> Option<Module> {
    let mut ctx = PhdrSearch {
        needle: basename,
        out: None,
    };
    unsafe {
        dl_iterate_phdr(
            phdr_callback,
            &mut ctx as *mut PhdrSearch<'_> as *mut core::ffi::c_void,
        );
    }
    if ctx.out.is_some() {
        return ctx.out;
    }
    find_module_maps(basename)
}

pub fn find_apk_path() -> Option<String> {
    for probe in ["libcinder_mobile.so", "liblibdiscord_wasm_host.so"] {
        if let Some(m) = find_module(probe) {
            if let Some((apk, _)) = m.name.split_once("!/") {
                if apk.ends_with(".apk") {
                    return Some(apk.to_string());
                }
            }

            let p = std::path::Path::new(&m.name);
            if let Some(candidate) = p
                .parent()
                .and_then(|d| d.parent())
                .and_then(|d| d.parent())
                .map(|d| d.join("base.apk"))
            {
                if candidate.is_file() {
                    return Some(candidate.to_string_lossy().into_owned());
                }
            }
        }
    }
    if let Ok(text) = fs::read_to_string("/proc/self/maps") {
        for line in text.lines() {
            if let Some(path) = line.split_whitespace().nth(5) {
                if path.ends_with("/base.apk") && path.contains("discord") {
                    return Some(path.to_string());
                }
            }
        }
    }
    None
}

fn find_module_maps(basename: &str) -> Option<Module> {
    let text = fs::read_to_string("/proc/self/maps").ok()?;
    let mut segments = Vec::new();
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        let range = parts.next()?;
        let perms = parts.next()?;
        let offset = parts.next()?;
        let _dev = parts.next();
        let _ino = parts.next();
        let path = parts.next().unwrap_or("");
        if !path.contains(basename) {
            continue;
        }
        let (start_s, end_s) = range.split_once('-')?;
        let start = usize::from_str_radix(start_s, 16).ok()?;
        let end = usize::from_str_radix(end_s, 16).ok()?;
        let offset = u64::from_str_radix(offset, 16).ok()?;
        segments.push(Segment {
            start,
            end,
            offset,
            filesz: (end.saturating_sub(start)) as u64,
            perms: perms.to_string(),
            path: path.to_string(),
        });
    }
    if segments.is_empty() {
        return None;
    }
    let base = segments.iter().map(|s| s.start).min().unwrap();
    let name = segments[0].path.clone();
    Some(Module {
        name,
        base,
        segments,
        build_id: None,
        eh_frame_hdr: None,
    })
}

pub fn page_size() -> usize {
    let ps = unsafe { getpagesize() };
    if ps > 0 && (ps as usize).is_power_of_two() {
        ps as usize
    } else {
        4096
    }
}

fn page_align_up(addr: usize, ps: usize) -> usize {
    let mask = ps.saturating_sub(1);
    addr.saturating_add(mask) & !mask
}

pub fn parse_maps_rw_private(maps_text: &str, addr: usize) -> bool {
    if addr == 0 {
        return false;
    }
    for line in maps_text.lines() {
        let mut parts = line.split_whitespace();
        let Some(range) = parts.next() else {
            continue;
        };
        let Some(perms) = parts.next() else {
            continue;
        };
        let Some((start_s, end_s)) = range.split_once('-') else {
            continue;
        };
        let Ok(start) = usize::from_str_radix(start_s, 16) else {
            continue;
        };
        let Ok(end) = usize::from_str_radix(end_s, 16) else {
            continue;
        };
        if addr >= start && addr < end {
            let b = perms.as_bytes();
            return b.len() >= 4 && b[0] == b'r' && b[1] == b'w' && b[3] == b'p';
        }
    }
    false
}

pub fn is_addr_mapped_rw_private(addr: usize) -> bool {
    let Ok(text) = fs::read_to_string("/proc/self/maps") else {
        return false;
    };
    parse_maps_rw_private(&text, addr)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_offset_translates_with_vaddr_offset_delta() {
        let m = Module {
            name: "test.so".into(),
            base: 0x7000_0000,
            segments: vec![
                Segment {
                    start: 0x7000_0000,
                    end: 0x7040_0000,
                    offset: 0x0,
                    filesz: 0x40_0000,
                    perms: "r--".into(),
                    path: "test.so".into(),
                },
                Segment {
                    start: 0x7040_1000,
                    end: 0x7240_1000,
                    offset: 0x40_0000,
                    filesz: 0x200_0000,
                    perms: "r-x".into(),
                    path: "test.so".into(),
                },
            ],
            build_id: None,
            eh_frame_hdr: None,
        };
        assert_eq!(m.file_offset_to_addr(0x100, 4), Some(0x7000_0100));
        assert_eq!(m.file_offset_to_addr(0x40_0000, 4), Some(0x7040_1000));
        assert_eq!(m.file_offset_to_addr(0x15A_3374, 4), Some(0x715A_4374));

        assert_eq!(m.file_offset_to_addr(0x900_0000, 4), None);
        assert_eq!(m.file_offset_to_addr(0x23f_fffe, 4), None);
    }

    #[test]
    fn parse_gnu_build_id_skips_preceding_android_note() {
        let mut notes = Vec::new();

        notes.extend_from_slice(&8u32.to_le_bytes());
        notes.extend_from_slice(&4u32.to_le_bytes());
        notes.extend_from_slice(&1u32.to_le_bytes());
        notes.extend_from_slice(b"Android\0");
        notes.extend_from_slice(&[0x18, 0x00, 0x00, 0x00]);

        let bid = [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ];
        notes.extend_from_slice(&4u32.to_le_bytes());
        notes.extend_from_slice(&(bid.len() as u32).to_le_bytes());
        notes.extend_from_slice(&NT_GNU_BUILD_ID.to_le_bytes());
        notes.extend_from_slice(b"GNU\0");
        notes.extend_from_slice(&bid);

        assert_eq!(
            parse_gnu_build_id(&notes).as_deref(),
            Some("0000000000000000000000000000000000000000")
        );
    }

    #[test]
    fn lookup_eh_frame_hdr_binary_searches_sorted_datarel_table() {
        let hdr_addr = 0x705b_fba4usize;
        let mut hdr = Vec::new();
        hdr.extend_from_slice(&[1, 0x1b, 0x03, 0x3b]);
        hdr.extend_from_slice(&0x100i32.to_le_bytes());
        hdr.extend_from_slice(&3u32.to_le_bytes());

        let entries: [(i32, i32); 3] = [(0x1000, 0x200), (0x2000, 0x240), (0x3500, 0x280)];
        for (init_rel, fde_rel) in entries {
            hdr.extend_from_slice(&init_rel.to_le_bytes());
            hdr.extend_from_slice(&fde_rel.to_le_bytes());
        }

        assert_eq!(lookup_eh_frame_hdr(&hdr, hdr_addr, hdr_addr + 0x0fff), None);

        assert_eq!(
            lookup_eh_frame_hdr(&hdr, hdr_addr, hdr_addr + 0x1000),
            Some((hdr_addr + 0x1000, hdr_addr + 0x200, Some(hdr_addr + 0x2000)))
        );

        assert_eq!(
            lookup_eh_frame_hdr(&hdr, hdr_addr, hdr_addr + 0x2450),
            Some((hdr_addr + 0x2000, hdr_addr + 0x240, Some(hdr_addr + 0x3500)))
        );

        assert_eq!(
            lookup_eh_frame_hdr(&hdr, hdr_addr, hdr_addr + 0x3520),
            Some((hdr_addr + 0x3500, hdr_addr + 0x280, None))
        );
    }

    #[test]
    fn resolve_writable_vaddr_accepts_bss_slack_and_rejects_rx() {
        let m = Module {
            name: "host.so".into(),
            base: 0x7000_0000,
            segments: vec![
                Segment {
                    start: 0x7000_0000,
                    end: 0x75b5_7f40,
                    offset: 0,
                    filesz: 0x5b5_7f40,
                    perms: "r-x".into(),
                    path: "host.so".into(),
                },
                Segment {
                    start: 0x75f6_4960,
                    end: 0x75f8_6310,
                    offset: 0x5f6_0960,
                    filesz: 0x1_a4c0,
                    perms: "rw-".into(),
                    path: "host.so".into(),
                },
            ],
            build_id: None,
            eh_frame_hdr: None,
        };

        assert_eq!(m.resolve_writable_vaddr(0x5f8_6310), Some(0x75f8_6310));

        assert_eq!(m.resolve_writable_vaddr(0x1d5_7784), None);

        assert_eq!(m.resolve_writable_vaddr(0x5f9_0000), None);
    }

    #[test]
    fn parse_maps_rw_private_checks_permissions() {
        let sample = "7000000000-7005b58000 r-xp 00000000 08:01 12 /data/app/base.apk!/lib/arm64-v8a/liblibdiscord_wasm_host.so
7005f80000-7005f87000 rw-p 00000000 00:00 0 [anon:.bss]
7005f90000-7005f94000 r--p 00000000 00:00 0 [anon:.ro]
";
        assert!(parse_maps_rw_private(sample, 0x70_05f8_6310));
        assert!(!parse_maps_rw_private(sample, 0x70_01d5_7784));
        assert!(!parse_maps_rw_private(sample, 0x70_05f9_0010));
        assert!(!parse_maps_rw_private(sample, 0x70_05f8_7000));
    }
}
