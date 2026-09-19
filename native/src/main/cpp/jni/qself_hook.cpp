/*
 * Qself native hook engine — JNI surface.
 *
 * v1 ships Dobby: native inline hooks for arbitrary function addresses plus
 * import-table (PLT) replacement, both driven from Java. Initialization and
 * a real self-test are exposed so the settings UI can prove the engine
 * works on the device.
 *
 * LSPlant (ART-level Java method hooking) is wired in v1.1: it needs a
 * libart.so symbol resolver injected through its InitInfo callback, which
 * is a separate piece of work — see docs/NATIVE-LOADING.md.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>

#include <atomic>
#include <string>

#include "dobby.h"

namespace {

std::atomic<bool> g_initialized{false};

/* --- self-test target ----------------------------------------------------- */

volatile int g_marker_calls = 0;

__attribute__((noinline)) int Marker() {
    g_marker_calls++;
    return 0;
}

int (*g_marker_origin)() = nullptr;

__attribute__((noinline)) int MarkerReplace() {
    // call the original through Dobby's trampoline: proves both the
    // replacement and the trampoline are functional
    return (g_marker_origin != nullptr ? g_marker_origin() : -1) + 1;
}

void *MarkerAddress() {
    return reinterpret_cast<void *>(&Marker);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativeInit(JNIEnv *, jobject) {
    g_initialized.store(true);
    return 1;
}

JNIEXPORT jstring JNICALL
Java_sumicya_qself_native_HookNative_nativeVersion(JNIEnv *env, jobject) {
    const char *version = DobbyGetVersion();
    return env->NewStringUTF(version != nullptr ? version : "dobby");
}

/*
 * Hook Marker() with Dobby, verify the replacement runs and the trampoline
 * still reaches the original, then restore it.
 *
 * @return 0 on success, negative error code otherwise
 */
JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativeSelfTest(JNIEnv *, jobject) {
    if (!g_initialized.load()) {
        return -1;
    }
    if (g_marker_origin != nullptr) {
        // a previous run left the hook installed
        DobbyDestroy(MarkerAddress());
        g_marker_origin = nullptr;
    }
    if (Marker() != 0) {
        return -2;
    }
    if (DobbyHook(MarkerAddress(),
                  reinterpret_cast<dobby_dummy_func_t>(&MarkerReplace),
                  reinterpret_cast<dobby_dummy_func_t *>(&g_marker_origin)) != 0) {
        return -3;
    }
    if (g_marker_origin == nullptr) {
        DobbyDestroy(MarkerAddress());
        return -4;
    }
    const int hooked = Marker();
    const int destroyed = DobbyDestroy(MarkerAddress());
    g_marker_origin = nullptr;
    if (hooked != 1) {
        return -5;
    }
    if (destroyed != 0) {
        return -6;
    }
    if (Marker() != 0) {
        return -7;
    }
    return 0;
}

/*
 * Import-table (PLT) replacement for callers that resolve the addresses
 * themselves. Dobby's symbol resolver is disabled on purpose, so no symbol
 * lookup happens here.
 *
 * @return 0 on success, negative error code otherwise
 */
JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativePltReplace(
        JNIEnv *env,
        jobject,
        jstring imageName,
        jstring symbolName,
        jlong fakeFunc,
        jlong originOut) {
    if (imageName == nullptr || symbolName == nullptr || fakeFunc == 0 || originOut == 0) {
        return -1;
    }
    const char *image = env->GetStringUTFChars(imageName, nullptr);
    const char *symbol = env->GetStringUTFChars(symbolName, nullptr);
    if (image == nullptr || symbol == nullptr) {
        if (image != nullptr) env->ReleaseStringUTFChars(imageName, image);
        if (symbol != nullptr) env->ReleaseStringUTFChars(symbolName, symbol);
        return -2;
    }
    int result = DobbyImportTableReplace(
            const_cast<char *>(image),
            const_cast<char *>(symbol),
            reinterpret_cast<dobby_dummy_func_t>(fakeFunc),
            reinterpret_cast<dobby_dummy_func_t *>(originOut));
    env->ReleaseStringUTFChars(imageName, image);
    env->ReleaseStringUTFChars(symbolName, symbol);
    return result;
}

}  // extern "C"
