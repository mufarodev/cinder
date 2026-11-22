use crate::{logger, maps};
use std::ffi::{c_char, c_void, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::atomic::{AtomicU8, Ordering};

const HOST_LIB: &str = "liblibdiscord_wasm_host.so";

const JNI_FN_NEW_STRING_UTF: usize = 167;

type JniEnv = *mut *const *const c_void;
type JClass = *mut c_void;
type JString = *mut c_void;
type JBoolean = u8;

const JNI_FALSE: JBoolean = 0;
const JNI_TRUE: JBoolean = 1;

unsafe fn jni_new_string_utf(env: JniEnv, s: &str) -> JString {
    if env.is_null() {
        return ptr::null_mut();
    }
    let table = *env;
    if table.is_null() {
        return ptr::null_mut();
    }
    let Ok(cstr) = CString::new(s) else {
        return ptr::null_mut();
    };
    let raw_fn = *table.add(JNI_FN_NEW_STRING_UTF);
    if raw_fn.is_null() {
        return ptr::null_mut();
    }
    let new_string_utf: unsafe extern "C" fn(JniEnv, *const c_char) -> JString =
        std::mem::transmute(raw_fn);
    new_string_utf(env, cstr.as_ptr())
}

fn resolve_verified_flag_addr(vaddr: i64) -> Option<usize> {
    if vaddr <= 0 {
        return None;
    }
    let module = maps::find_module(HOST_LIB)?;
    let addr = module.resolve_writable_vaddr(vaddr as u64)?;
    if !maps::is_addr_mapped_rw_private(addr) {
        logger::warn(&format!(
            "refusing host flag access @ vaddr={vaddr:#x} (mem {addr:#x}): not mapped rw-p"
        ));
        return None;
    }
    Some(addr)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_discord_loader_CinderNative_nativeGetHostBase(
    _env: JniEnv,
    _cls: JClass,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| {
        maps::find_module(HOST_LIB)
            .map(|m| m.base as i64)
            .unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_discord_loader_CinderNative_nativeGetHostBuildId(
    env: JniEnv,
    _cls: JClass,
) -> JString {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(module) = maps::find_module(HOST_LIB) else {
            return ptr::null_mut();
        };
        let Some(bid) = module.build_id.as_deref() else {
            return ptr::null_mut();
        };
        jni_new_string_utf(env, bid)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_discord_loader_CinderNative_nativeWriteHostFlag(
    _env: JniEnv,
    _cls: JClass,
    vaddr: i64,
    value: i32,
) -> JBoolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(addr) = resolve_verified_flag_addr(vaddr) else {
            return JNI_FALSE;
        };
        let byte: u8 = if value != 0 { 1 } else { 0 };

        let slot = AtomicU8::from_ptr(addr as *mut u8);
        slot.store(byte, Ordering::Release);
        logger::info(&format!(
            "runtime flag write @ vaddr={vaddr:#x} (mem {addr:#x}) = {byte:#x}"
        ));
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_discord_loader_CinderNative_nativeReadHostFlag(
    _env: JniEnv,
    _cls: JClass,
    vaddr: i64,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(addr) = resolve_verified_flag_addr(vaddr) else {
            return -1;
        };
        let slot = AtomicU8::from_ptr(addr as *mut u8);
        if slot.load(Ordering::Acquire) != 0 {
            1
        } else {
            0
        }
    }))
    .unwrap_or(-1)
}
