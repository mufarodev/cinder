use cinder_mobile_api::{cinder_plugin, cstr, ClientApiV0, LOG_INFO};

extern "C" fn on_load(api: *const ClientApiV0) -> i32 {
    unsafe {
        ((*api).log)(
            LOG_INFO,
            cstr!("hello-cinder-mobile: plugin ABI gate passed, on_load called"),
        );
    }
    0
}

cinder_plugin!(
    "cinder.mobile.hello",
    "Hello Cinder Mobile",
    "0.1.0",
    on_load
);
