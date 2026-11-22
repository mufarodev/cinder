use core::ffi::{c_char, c_int};

pub const ABI_VERSION: u64 = 0xC1DE_0000_0000_1001;

pub const LOG_DEBUG: c_int = 0;
pub const LOG_INFO: c_int = 1;
pub const LOG_WARN: c_int = 2;
pub const LOG_ERROR: c_int = 3;

#[repr(C)]
pub struct ClientApiV0 {
    pub abi: u64,

    pub log: extern "C" fn(level: c_int, msg: *const c_char),

    pub client_info: *const c_char,
}
unsafe impl Sync for ClientApiV0 {}
unsafe impl Send for ClientApiV0 {}

#[repr(C)]
pub struct PluginManifestV0 {
    pub abi: u64,
    pub id: *const c_char,
    pub name: *const c_char,
    pub version: *const c_char,

    pub on_load: extern "C" fn(api: *const ClientApiV0) -> i32,
    pub on_unload: extern "C" fn(),
}
unsafe impl Sync for PluginManifestV0 {}
unsafe impl Send for PluginManifestV0 {}

#[macro_export]
macro_rules! cstr {
    ($s:literal) => {
        concat!($s, "\0").as_ptr() as *const core::ffi::c_char
    };
}

pub extern "C" fn noop_unload() {}

#[macro_export]
macro_rules! cinder_plugin {
    ($id:literal, $name:literal, $version:literal, $on_load:expr) => {
        static ON_LOAD: extern "C" fn(*const $crate::ClientApiV0) -> i32 = $on_load;
        static MANIFEST: $crate::PluginManifestV0 = $crate::PluginManifestV0 {
            abi: $crate::ABI_VERSION,
            id: $crate::cstr!($id),
            name: $crate::cstr!($name),
            version: $crate::cstr!($version),
            on_load: ON_LOAD,
            on_unload: $crate::noop_unload,
        };
        #[no_mangle]
        pub extern "C" fn cinder_plugin(out: *mut *const $crate::PluginManifestV0) -> i32 {
            unsafe {
                if !out.is_null() {
                    *out = &MANIFEST;
                }
            }
            0
        }
    };
}
