package com.discord.nativeapp.bridge;

/**
 * Compile-time-only stub matching Discord Phoenix's {@code WasmRuntime} JNI signature.
 * Passed only via {@code -classpath} / R8 {@code --lib}; never packaged into {@code classes2.dex}.
 */
public final class WasmRuntime {
    private WasmRuntime() {}

    public static native boolean nativeDispatch(long handle, byte[] frame);
}
