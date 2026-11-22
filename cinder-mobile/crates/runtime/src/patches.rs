use crate::config::Config;
use crate::hook::flush_icache;
use crate::logger;
use crate::maps::{Module, Segment};
use crate::scan::Pin;
use cinder_mobile_api::LOG_WARN;
use std::ffi::c_void;

extern "C" {
    fn mprotect(addr: *mut c_void, len: usize, prot: i32) -> i32;
    fn getpagesize() -> i32;
    fn open(path: *const i8, flags: i32, ...) -> i32;
    fn pwrite(fd: i32, buf: *const c_void, count: usize, offset: i64) -> i64;
    fn close(fd: i32) -> i32;
    #[cfg(target_os = "android")]
    fn __errno() -> *mut i32;
    #[cfg(not(target_os = "android"))]
    fn __errno_location() -> *mut i32;
}

const O_RDWR: i32 = 2;

unsafe fn errno() -> i32 {
    #[cfg(target_os = "android")]
    {
        *__errno()
    }
    #[cfg(not(target_os = "android"))]
    {
        *__errno_location()
    }
}

unsafe fn write_memory(addr: usize, bytes: &[u8], orig_prot: i32) -> bool {
    let page = getpagesize().max(1) as usize;
    let (start, len) = page_range_for(addr, bytes.len(), page);
    if mprotect(start as *mut c_void, len, 0x1 | 0x2) == 0 {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), addr as *mut u8, bytes.len());
        if mprotect(start as *mut c_void, len, orig_prot) != 0 {
            logger::warn(&format!(
                "mprotect restore prot={orig_prot:#x} failed errno={} at {start:#x}+{len:#x}",
                errno()
            ));
        }
        if orig_prot & 0x4 != 0 {
            flush_icache(addr as *const u8, bytes.len());
        }
        return true;
    }
    logger::warn(&format!(
        "mprotect RW failed errno={} at {start:#x}+{len:#x}; trying /proc/self/mem",
        errno()
    ));
    let path = b"/proc/self/mem\0";
    let fd = open(path.as_ptr() as *const i8, O_RDWR);
    if fd < 0 {
        logger::warn(&format!("/proc/self/mem open failed errno={}", errno()));
        return false;
    }
    let mut written = 0usize;
    while written < bytes.len() {
        let n = pwrite(
            fd,
            bytes.as_ptr().add(written) as *const c_void,
            bytes.len() - written,
            (addr + written) as i64,
        );
        if n <= 0 {
            logger::warn(&format!(
                "/proc/self/mem pwrite failed errno={} at {:#x}",
                errno(),
                addr + written
            ));
            break;
        }
        written += n as usize;
    }
    close(fd);
    if orig_prot & 0x4 != 0 {
        flush_icache(addr as *const u8, bytes.len());
    }
    written == bytes.len()
}

fn perms_to_prot(seg: &Segment) -> i32 {
    let mut p = 0;
    if seg.perms.as_bytes().get(0) == Some(&b'r') {
        p |= 0x1;
    }
    if seg.perms.as_bytes().get(1) == Some(&b'w') {
        p |= 0x2;
    }
    if seg.perms.as_bytes().get(2) == Some(&b'x') {
        p |= 0x4;
    }
    p
}

fn page_range_for(addr: usize, len: usize, page: usize) -> (usize, usize) {
    let start = addr & !(page - 1);
    let end = (addr.saturating_add(len).saturating_add(page - 1)) & !(page - 1);
    (start, end.saturating_sub(start))
}

pub fn log_verdict(tag: &str, literal_hits: &[usize], pins: &[Pin]) {
    logger::info(&format!(
        "claim {tag}: {} literal hit(s), {} code pin(s)",
        literal_hits.len(),
        pins.len()
    ));
    for (i, p) in pins.iter().enumerate().take(8) {
        let fn_label = if p.fn_exact {
            format!(
                "fn={:#x} (eh_frame +{:#x})",
                p.fn_hint,
                p.adrp_pc.saturating_sub(p.fn_hint)
            )
        } else {
            format!("fn~{:#x}", p.fn_hint)
        };
        logger::info(&format!(
            "  pin {i}: lit={:#x} adrp={:#x} add={:?} {fn_label} prologue={:02x?}",
            p.literal_addr, p.adrp_pc, p.add_pc, p.prologue
        ));
    }
    if pins.len() > 8 {
        logger::info(&format!("  … +{} more", pins.len() - 8));
    }
}

pub fn apply_version_brand(module: &Module, hits: &[usize], brand: &str, expected: &[u8]) {
    let mut applied = 0usize;
    for &addr in hits {
        let cur = unsafe { std::slice::from_raw_parts(addr as *const u8, expected.len()) };
        if cur != expected {
            logger::warn(&format!("brand guard mismatch at {addr:#x}, skipping"));
            continue;
        }
        let seg = module
            .segments
            .iter()
            .find(|s| addr >= s.start && addr + expected.len() <= s.end);
        let orig_prot = match seg {
            Some(s) => perms_to_prot(s),
            None => continue,
        };
        let mut bytes = vec![b'0'; expected.len()];
        let b = brand.as_bytes();
        let n = b.len().min(bytes.len());
        bytes[..n].copy_from_slice(&b[..n]);
        if !unsafe { write_memory(addr, &bytes, orig_prot) } {
            logger::warn(&format!("brand write failed at {addr:#x}"));
            continue;
        }
        applied += 1;
        logger::info(&format!(
            "brand applied at {addr:#x}: {:?} -> {:?}",
            expected, bytes
        ));
    }
    logger::info(&format!("version-brand: {applied} site(s) rewritten"));
}

pub fn apply_code_patch_stub(config: &Config, tag: &str) {
    let key = format!("{tag}.code_addr");
    match config.get(&key) {
        Some(addr_s) if addr_s.starts_with("0x") => {
            logger::warn(&format!(
                "{tag}: explicit pin {addr_s} present but code patch stage is not implemented in v1 — verify-only"
            ));
        }
        _ => logger::info(&format!("{tag}: no pin in config — verify-only (no write)")),
    }
}

pub fn warn_verify_only() {
    logger::log(
        LOG_WARN,
        "verify_only is on: pins are logged, no memory is written",
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_range_does_not_underflow_and_handles_cross_page() {
        assert_eq!(
            page_range_for(0x7000_29c6d1, 22, 4096),
            (0x7000_29c000, 4096)
        );

        assert_eq!(
            page_range_for(0x7000_29cff8, 16, 4096),
            (0x7000_29c000, 8192)
        );

        assert_eq!(
            page_range_for(0x7000_29c000, 4096, 4096),
            (0x7000_29c000, 4096)
        );

        assert_eq!(
            page_range_for(0x7000_29c6d1, 22, 16384),
            (0x7000_29c000, 16384)
        );
    }
}
