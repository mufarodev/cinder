use std::collections::HashSet;
use std::ffi::c_void;
use std::sync::{LazyLock, Mutex};

const PROT_READ: i32 = 0x1;
const PROT_WRITE: i32 = 0x2;
const PROT_EXEC: i32 = 0x4;

extern "C" {
    fn mprotect(addr: *mut c_void, len: usize, prot: i32) -> i32;
    fn getpagesize() -> i32;
}

#[cfg(target_arch = "aarch64")]
pub(crate) unsafe fn flush_icache(start: *const u8, len: usize) {
    use std::arch::asm;
    if len == 0 {
        return;
    }
    let ctr: u64;
    asm!("mrs {0}, ctr_el0", out(reg) ctr, options(nomem, nostack));
    let dline = 4usize << ((ctr >> 16) & 0xf);
    let iline = 4usize << (ctr & 0xf);

    let begin = start as usize;
    let end = begin.saturating_add(len);

    let mut p = begin & !(dline - 1);
    while p < end {
        asm!("dc cvau, {0}", in(reg) p, options(nostack));
        p += dline;
    }
    asm!("dsb ish", options(nostack));

    p = begin & !(iline - 1);
    while p < end {
        asm!("ic ivau, {0}", in(reg) p, options(nostack));
        p += iline;
    }
    asm!("dsb ish", "isb", options(nostack));
}

#[cfg(not(target_arch = "aarch64"))]
pub(crate) unsafe fn flush_icache(_start: *const u8, _len: usize) {}

static INSTALLED: LazyLock<Mutex<HashSet<usize>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

pub const DETOUR_LEN: usize = 16;

fn detour(replacement: usize, target: usize) -> Result<[u8; 16], String> {
    if target & 3 != 0 {
        return Err(format!(
            "aarch64 target must be 4-byte aligned: {target:#x}"
        ));
    }

    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&0x5800_0050u32.to_le_bytes());
    out[4..8].copy_from_slice(&0xD61F_0200u32.to_le_bytes());
    out[8..16].copy_from_slice(&(replacement as u64).to_le_bytes());
    Ok(out)
}

pub struct InstalledPatch {
    addr: usize,
    original: [u8; 16],
}

impl InstalledPatch {
    pub fn install(
        target: usize,
        replacement: usize,
        guard: Option<&[u8]>,
    ) -> Result<Self, String> {
        let mut installed = INSTALLED.lock().unwrap();
        if installed.contains(&target) {
            return Err(format!("double install refused at {target:#x}"));
        }
        let patch = detour(replacement, target)?;
        let original = unsafe { std::slice::from_raw_parts(target as *const u8, DETOUR_LEN) };
        let mut saved = [0u8; 16];
        saved.copy_from_slice(original);
        if let Some(g) = guard {
            if !saved.starts_with(g) {
                return Err(format!(
                    "prologue drift at {target:#x}: got {:02x?}, want {:02x?}",
                    &saved[..g.len().min(16)],
                    g
                ));
            }
        }
        let page = unsafe { getpagesize() as usize };
        let start = target & !(page - 1);
        let end = (target + DETOUR_LEN + page - 1) & !(page - 1);
        let len = end - start;
        unsafe {
            if mprotect(start as *mut c_void, len, PROT_READ | PROT_WRITE) != 0 {
                return Err(format!("mprotect RW failed at {start:#x}"));
            }
            std::ptr::copy_nonoverlapping(patch.as_ptr(), target as *mut u8, DETOUR_LEN);
            if mprotect(start as *mut c_void, len, PROT_READ | PROT_EXEC) != 0 {
                return Err(format!("mprotect RX failed at {start:#x}"));
            }
            flush_icache(target as *const u8, DETOUR_LEN);
        }
        installed.insert(target);
        Ok(InstalledPatch {
            addr: target,
            original: saved,
        })
    }

    pub fn restore(&self) -> Result<(), String> {
        let mut installed = INSTALLED.lock().unwrap();
        if !installed.contains(&self.addr) {
            return Ok(());
        }
        let page = unsafe { getpagesize() as usize };
        let start = self.addr & !(page - 1);
        let end = (self.addr + DETOUR_LEN + page - 1) & !(page - 1);
        let len = end - start;
        unsafe {
            if mprotect(start as *mut c_void, len, PROT_READ | PROT_WRITE) != 0 {
                return Err("restore mprotect RW failed".into());
            }
            std::ptr::copy_nonoverlapping(self.original.as_ptr(), self.addr as *mut u8, DETOUR_LEN);
            if mprotect(start as *mut c_void, len, PROT_READ | PROT_EXEC) != 0 {
                return Err("restore mprotect RX failed".into());
            }
            flush_icache(self.addr as *const u8, DETOUR_LEN);
        }
        installed.remove(&self.addr);
        Ok(())
    }
}

pub fn live() -> usize {
    INSTALLED.lock().unwrap().len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detour_works_on_4_and_8_aligned_targets_beyond_4gb() {
        let far_repl = 0x0000_7f12_3456_7890usize;
        for target in [0x0000_0055_0000_1000usize, 0x0000_0055_0000_1004usize] {
            let bytes = detour(far_repl, target).expect("4-aligned target must succeed");
            assert_eq!(&bytes[0..4], &0x5800_0050u32.to_le_bytes());
            assert_eq!(&bytes[4..8], &0xD61F_0200u32.to_le_bytes());
            assert_eq!(&bytes[8..16], &(far_repl as u64).to_le_bytes());
        }
        assert!(detour(far_repl, 0x1002).is_err());
    }
}
