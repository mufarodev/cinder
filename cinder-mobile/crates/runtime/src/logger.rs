use core::ffi::{c_char, c_int};
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char);
}

#[cfg(target_os = "android")]
const ANDROID_LOG_DEBUG: c_int = 3;
#[cfg(target_os = "android")]
const ANDROID_LOG_INFO: c_int = 4;
#[cfg(target_os = "android")]
const ANDROID_LOG_WARN: c_int = 5;
#[cfg(target_os = "android")]
const ANDROID_LOG_ERROR: c_int = 6;

static LOG_FILE: Mutex<Option<File>> = Mutex::new(None);

pub fn set_log_path(p: PathBuf) {
    if let Ok(f) = OpenOptions::new().create(true).append(true).open(&p) {
        if let Ok(mut guard) = LOG_FILE.lock() {
            *guard = Some(f);
        }
    }
}

#[cfg(target_os = "android")]
fn prio(level: c_int) -> c_int {
    match level {
        0 => ANDROID_LOG_DEBUG,
        2 => ANDROID_LOG_WARN,
        3 => ANDROID_LOG_ERROR,
        _ => ANDROID_LOG_INFO,
    }
}

pub fn log(level: c_int, msg: &str) {
    #[cfg(target_os = "android")]
    unsafe {
        let tag = b"cinder\0".as_ptr() as *const c_char;
        let mut line = Vec::with_capacity(msg.len() + 1);
        line.extend_from_slice(msg.as_bytes());
        line.push(0);
        __android_log_write(prio(level), tag, line.as_ptr() as *const c_char);
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = level;
        eprintln!("[cinder] {msg}");
    }

    if let Ok(mut guard) = LOG_FILE.lock() {
        if let Some(f) = guard.as_mut() {
            let _ = writeln!(f, "{msg}");
        }
    }
}

pub extern "C" fn plugin_log(level: c_int, msg: *const c_char) {
    if msg.is_null() {
        return;
    }
    let s = unsafe { std::ffi::CStr::from_ptr(msg) }.to_string_lossy();
    log(level, &s);
}

pub fn info(msg: &str) {
    log(cinder_mobile_api::LOG_INFO, msg);
}
pub fn warn(msg: &str) {
    log(cinder_mobile_api::LOG_WARN, msg);
}
#[allow(dead_code)]
pub fn error(msg: &str) {
    log(cinder_mobile_api::LOG_ERROR, msg);
}
pub fn step(msg: &str) {
    log(cinder_mobile_api::LOG_INFO, &format!("[cinder/step] {msg}"));
}
