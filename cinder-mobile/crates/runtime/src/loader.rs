use crate::config::Config;
use crate::logger;
use crate::maps;
use cinder_mobile_api::{ClientApiV0, PluginManifestV0, ABI_VERSION};
use std::collections::HashSet;
use std::ffi::{c_char, c_int, CStr, CString};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

const RTLD_NOW: c_int = 2;

extern "C" {
    fn dlopen(path: *const c_char, flags: c_int) -> *mut core::ffi::c_void;
    fn dlsym(handle: *mut core::ffi::c_void, symbol: *const c_char) -> *mut core::ffi::c_void;
}

struct Handle(*mut core::ffi::c_void);
unsafe impl Send for Handle {}

static HANDLES: Mutex<Vec<Handle>> = Mutex::new(Vec::new());

type PluginEntry = unsafe extern "C" fn(*mut *const PluginManifestV0) -> i32;

fn plugin_dir(config: &Config) -> PathBuf {
    if let Ok(p) = std::env::var("CINDER_PLUGIN_DIR") {
        return PathBuf::from(p);
    }
    if let Some(p) = config.get("plugins_dir") {
        return PathBuf::from(p);
    }
    if let Some(m) = maps::find_module("libcinder_mobile.so") {
        if let Some((apk, _)) = m.name.split_once("!/") {
            if apk.ends_with(".apk") {
                if let Some(parent) = std::path::Path::new(apk).parent() {
                    let arm64 = parent.join("lib").join("arm64");
                    if arm64.is_dir() {
                        return arm64;
                    }
                    let arm64_v8a = parent.join("lib").join("arm64-v8a");
                    if arm64_v8a.is_dir() {
                        return arm64_v8a;
                    }
                }
            }
        } else if let Some(parent) = std::path::Path::new(&m.name).parent() {
            return parent.to_path_buf();
        }
    }
    config.home().join("plugins")
}

unsafe fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    CStr::from_ptr(p).to_string_lossy().into_owned()
}

fn api_v0() -> *const ClientApiV0 {
    static CLIENT_INFO: &[u8] = b"cinder-mobile v0.1 / com.discord.release\0";
    static API: OnceLock<ClientApiV0> = OnceLock::new();
    API.get_or_init(|| ClientApiV0 {
        abi: ABI_VERSION,
        log: logger::plugin_log,
        client_info: CLIENT_INFO.as_ptr() as *const c_char,
    }) as *const ClientApiV0
}

fn is_plugin_filename(name: &str) -> bool {
    name.ends_with(".so")
        && name != "libcinder_mobile.so"
        && (name.starts_with("libcinder_")
            || name.starts_with("libmod_")
            || name.starts_with("libhello_cinder"))
}

pub fn load_plugins(config: &Config) {
    if !config.bool("plugins", true) {
        logger::info("plugins disabled by config");
        return;
    }
    let dir = plugin_dir(config);
    let entries = match std::fs::read_dir(&dir) {
        Ok(e) => e,
        Err(e) => {
            logger::info(&format!(
                "no plugin dir at {} ({e}) — trying packaged sonames",
                dir.display()
            ));
            load_packaged(config);
            return;
        }
    };

    let api = api_v0();
    let mut ids: HashSet<String> = HashSet::new();
    let mut loaded = 0usize;
    for entry in entries.flatten() {
        let path = entry.path();
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if !is_plugin_filename(name) {
            continue;
        }
        let cpath = match CString::new(path.to_string_lossy().as_bytes()) {
            Ok(c) => c,
            Err(_) => continue,
        };
        unsafe {
            if load_one(&cpath, name, api, &mut ids) {
                loaded += 1;
            }
        }
    }

    if let Ok(extra) = std::env::var("CINDER_PLUGINS") {
        for name in extra.split(',') {
            let name = name.trim();
            if name.is_empty() {
                continue;
            }
            if let Ok(cname) = CString::new(name) {
                unsafe {
                    if load_one(&cname, name, api, &mut ids) {
                        loaded += 1;
                    }
                }
            }
        }
    }
    if loaded == 0 {
        load_packaged_into(config, api, &mut ids, &mut loaded);
    }
    logger::info(&format!("plugins loaded: {loaded}"));
}

unsafe fn load_one(
    cpath: &CStr,
    label: &str,
    api: *const ClientApiV0,
    ids: &mut HashSet<String>,
) -> bool {
    let h = dlopen(cpath.as_ptr(), RTLD_NOW);
    if h.is_null() {
        logger::info(&format!("plugin {label}: not present"));
        return false;
    }
    let sym = dlsym(h, b"cinder_plugin\0".as_ptr() as *const c_char);
    if sym.is_null() {
        logger::warn(&format!("plugin {label}: no cinder_plugin export"));
        return false;
    }
    let entry_fn: PluginEntry = std::mem::transmute(sym);
    let mut manifest: *const PluginManifestV0 = std::ptr::null();
    if entry_fn(&mut manifest) != 0 || manifest.is_null() {
        logger::warn(&format!("plugin {label}: manifest call failed"));
        return false;
    }
    let m = &*manifest;
    if m.abi != ABI_VERSION {
        logger::warn(&format!(
            "plugin {label}: ABI {:#x} rejected (host {:#x})",
            m.abi, ABI_VERSION
        ));
        return false;
    }
    let id = cstr(m.id);
    let disp = cstr(m.name);
    if id.is_empty() || !ids.insert(id.clone()) {
        logger::warn(&format!("plugin {label}: empty or duplicate id '{id}'"));
        return false;
    }
    let rc = (m.on_load)(api);
    if rc != 0 {
        logger::warn(&format!("plugin {disp}: on_load returned {rc}"));
        ids.remove(&id);
        return false;
    }
    HANDLES.lock().unwrap().push(Handle(h));
    logger::info(&format!("plugin loaded: {disp} ({id}) {}", cstr(m.version)));
    true
}

fn load_packaged(config: &Config) {
    let api = api_v0();
    let mut ids: HashSet<String> = HashSet::new();
    let mut loaded = 0usize;
    load_packaged_into(config, api, &mut ids, &mut loaded);
    logger::info(&format!("plugins loaded: {loaded} (packaged sonames)"));
}

fn load_packaged_into(
    config: &Config,
    api: *const ClientApiV0,
    ids: &mut HashSet<String>,
    loaded: &mut usize,
) {
    let names = config
        .get("plugin_names")
        .unwrap_or("libhello_cinder_mobile.so")
        .to_string();
    for name in names.split(',') {
        let name = name.trim();
        if name.is_empty() {
            continue;
        }
        if let Ok(cname) = CString::new(name) {
            unsafe {
                if load_one(&cname, name, api, ids) {
                    *loaded += 1;
                }
            }
        }
    }
}
