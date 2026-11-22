mod claims;
mod config;
mod hook;
mod jni_bridge;
mod loader;
mod logger;
mod manifest;
mod maps;
mod patches;
mod scan;

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

static BOOT_CONFIG: OnceLock<config::Config> = OnceLock::new();

unsafe extern "C" fn init_array_entry() {
    let _ = catch_unwind(AssertUnwindSafe(boot));
}

#[used]
#[cfg_attr(
    any(target_os = "linux", target_os = "android"),
    link_section = ".init_array"
)]
static INIT: unsafe extern "C" fn() = init_array_entry;

fn boot() {
    let cfg = config::Config::load();
    let home = cfg.home();
    let _ = std::fs::create_dir_all(&home);
    logger::set_log_path(home.join("cinder.log"));

    logger::step("boot: cinder-mobile v0.1");
    if let Some(p) = &cfg.path {
        logger::info(&format!("config: {}", p.display()));
    } else {
        logger::warn("no config found; running with defaults (verify-only)");
    }

    let verify = cfg.bool("verify_only", true);
    logger::info(&format!("verify_only={verify}"));
    if verify {
        patches::warn_verify_only();
    }
    logger::info(&format!(
        "hook engine ready: aarch64 detour ({} bytes), live {}",
        hook::DETOUR_LEN,
        hook::live()
    ));
    let _ = BOOT_CONFIG.set(cfg);
}

fn hex(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

const FALLBACK_BUILD_ID: &str = "0000000000000000000000000000000000000000";

const FALLBACK_STATIC_PATCHES: &[(&str, u64, &[u8], &[u8])] = &[
    (
        "staff.devtools",
        0x1D57784,
        &[
            0xFF, 0x03, 0x01, 0xD1, 0xFD, 0x7B, 0x02, 0xA9, 0xF3, 0x1B, 0x00, 0xF9,
        ],
        &[
            0x68, 0x11, 0x02, 0xF0, 0x00, 0x41, 0x4C, 0x39, 0xC0, 0x03, 0x5F, 0xD6,
        ],
    ),
    (
        "experiment.release_access.1",
        0x2A7B40C,
        &[0xE0, 0x17, 0x9F, 0x1A],
        &[0x20, 0x00, 0x80, 0x52],
    ),
    (
        "experiment.release_access.2",
        0x2A7B414,
        &[0x40, 0x00, 0x80, 0x52],
        &[0x20, 0x00, 0x80, 0x52],
    ),
];

fn verify_one_patch(module: &maps::Module, tag: &str, file_off: u64, orig: &[u8], patched: &[u8]) {
    let Some(addr) = module.file_offset_to_addr(file_off, patched.len()) else {
        logger::warn(&format!(
            "static patch {tag} @ file {file_off:#x}: out of mapped segment bounds (build drift?)"
        ));
        return;
    };
    let live = unsafe { std::slice::from_raw_parts(addr as *const u8, patched.len()) };
    let status = if live == patched {
        "ARMED"
    } else if live == orig {
        "not applied"
    } else {
        "DRIFTED"
    };
    logger::info(&format!(
        "static patch {tag} @ file {file_off:#x} (mem {addr:#x}): {} ({status})",
        hex(live)
    ));
}

fn verify_static_patches(cfg: &config::Config, module: &maps::Module) {
    let live_bid = module.build_id.as_deref();
    logger::info(&format!(
        "host build_id: {}",
        live_bid.unwrap_or("unavailable")
    ));

    if let Some((m, src)) = manifest::load(cfg) {
        logger::info(&format!(
            "patch manifest: {src} (build_id={}, {} patch(es))",
            if m.build_id.is_empty() {
                "none"
            } else {
                &m.build_id
            },
            m.patches.len()
        ));
        if !m.build_id.is_empty() {
            match live_bid {
                Some(live) if live.eq_ignore_ascii_case(&m.build_id) => {
                    logger::info(&format!("build_id verified: {live} (MATCH)"));
                }
                Some(live) => {
                    logger::warn(&format!(
                        "BUILD_DRIFT: live build_id={live} != manifest build_id={}; refusing to verify offsets",
                        m.build_id
                    ));
                    return;
                }
                None => {
                    logger::warn(
                        "live host Build ID unavailable; verifying manifest offsets with byte guards only",
                    );
                }
            }
        }
        for p in &m.patches {
            verify_one_patch(module, &p.tag, p.file_offset, &p.orig, &p.patched);
        }
        return;
    }

    logger::info("no patch manifest found; falling back to built-in verification (rel3567ab)");
    if let Some(live) = live_bid {
        if !live.eq_ignore_ascii_case(FALLBACK_BUILD_ID) {
            logger::warn(&format!(
                "BUILD_DRIFT: live build_id={live} != fallback build_id={FALLBACK_BUILD_ID}; skipping built-in offsets"
            ));
            return;
        }
    }
    for &(tag, file_off, orig, patched) in FALLBACK_STATIC_PATCHES {
        verify_one_patch(module, tag, file_off, orig, patched);
    }
}

fn resolve_stage(cfg: &config::Config) {
    let mut module = None;
    for _ in 0..40 {
        module = maps::find_module("liblibdiscord_wasm_host.so");
        if module.is_some() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    let module = match module {
        Some(m) => m,
        None => {
            logger::warn(
                "WASM host module not found in /proc/self/maps after 20s — resolver skipped",
            );
            return;
        }
    };
    logger::info(&format!(
        "host module: {} base={:#x} eh_frame={}",
        module.name,
        module.base,
        if module.eh_frame_hdr.is_some() {
            "present"
        } else {
            "absent"
        }
    ));

    verify_static_patches(cfg, &module);

    let verify = cfg.bool("verify_only", true);
    let brand = cfg.get("version_prefix").unwrap_or("").to_string();

    let hits_per_claim: Vec<Vec<usize>> = claims::CLAIMS
        .iter()
        .map(|(_, needle)| scan::find_literal(&module, needle.as_bytes()))
        .collect();

    if let Some(sci_idx) = claims::CLAIMS
        .iter()
        .position(|(t, _)| *t == "track.science")
    {
        logger::info(&format!(
            "telemetry: /science literal has {} hit(s) in mapped image",
            hits_per_claim[sci_idx].len()
        ));
    }

    let pins_per_claim = scan::xref_pins_batch(&module, &hits_per_claim);

    for (idx, (tag, needle)) in claims::CLAIMS.iter().enumerate() {
        let hits = &hits_per_claim[idx];
        let pins = &pins_per_claim[idx];
        patches::log_verdict(tag, hits, pins);

        if *tag == "brand.version"
            && !brand.is_empty()
            && !verify
            && claims::DATA_PATCHABLE.contains(tag)
        {
            patches::apply_version_brand(&module, hits, &brand, needle.as_bytes());
        }
        if tag.starts_with("experiment.") || tag.starts_with("track.") {
            patches::apply_code_patch_stub(cfg, tag);
        }
    }
    logger::step(if verify {
        "resolver: done (no writes in verify-only mode)"
    } else {
        "resolver: done"
    });
}

#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut c_void, _reserved: *mut c_void) -> i32 {
    if let Some(cfg) = BOOT_CONFIG.get() {
        let cfg_clone = cfg.clone();
        let spawned = std::thread::Builder::new()
            .name("cinder-resolve".into())
            .stack_size(4 << 20)
            .spawn(move || resolve_stage(&cfg_clone));
        if spawned.is_err() {
            logger::warn("resolver thread spawn failed; skipping pin scan this boot");
        }
        loader::load_plugins(cfg);
    }
    0x0001_0006
}
