/*
 * Qself native hook engine — JNI surface.
 *
 * Wraps LSPlant (ART inline hooks) and Dobby (PLT hooks) behind a tiny
 * JNI API. In v1 the engine is initialized at module boot and exercised by
 * a self-test so diagnostics can prove the engine works on the device;
 * Java-level features use the Xposed API. Native-level feature hooking
 * builds on this surface in later releases.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>

#include <atomic>
#include <string>

#include "dobby.h"
#include "lsplant/lsplant.h"

namespace {

enum State {
    STATE_UNINIT = 0,
    STATE_READY = 1,
};

std::atomic<int> g_state{STATE_UNINIT};

/* --- self-test target ----------------------------------------------------- */

int g_marker_calls = 0;
bool g_marker_hooked = false;

int Marker() {
    g_marker_calls++;
    if (g_marker_hooked) {
        return 1;
    }
    return 0;
}

int MarkerReplace() {
    g_marker_hooked = true;
    return Marker();
}

bool SelfTestInline() {
    uintptr_t target = reinterpret_cast<uintptr_t>(&Marker);
    lsplant::TrampolineContext *ctx = nullptr;
    if (!lsplant::HookInline(
            target,
            reinterpret_cast<uintptr_t>(&MarkerReplace),
            &ctx)) {
        return false;
    }
    bool ok = Marker() == 1;
    lsplant::UnhookInline(target);
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativeInit(JNIEnv *, jobject) {
    int expected = STATE_UNINIT;
    if (g_state.compare_exchange_strong(expected, STATE_READY)) {
        lsplant::Init();
        return 1;
    }
    return g_state.load();
}

JNIEXPORT jstring JNICALL
Java_sumicya_qself_native_HookNative_nativeVersion(JNIEnv *env, jobject) {
    std::string version = "lsplant+dobbyte-state-";
    version += std::to_string(g_state.load());
    return env->NewStringUTF(version.c_str());
}

/*
 * Hook our own Marker() with an inline hook, call it, expect the replaced
 * behaviour, unhook again. Proves the engine end to end without touching
 * the host app.
 *
 * @return 0 on success, non-zero on failure
 */
JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativeSelfTest(JNIEnv *, jobject) {
    if (g_state.load() != STATE_READY) {
        return -1;
    }
    g_marker_hooked = false;
    int baseline = Marker();
    if (baseline != 0) {
        return -2;
    }
    if (!SelfTestInline()) {
        return -3;
    }
    g_marker_hooked = false;
    int after = Marker();
    if (after != 0) {
        return -4;
    }
    return 0;
}

/*
 * Dobby PLT hook surface (for future native features / diagnostics).
 * All addresses are provided by the caller; no symbol lookup happens here
 * (Dobby's symbol resolver is disabled on purpose).
 *
 * @return 0 on success, non-zero on failure
 */
JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativePltHook(
        JNIEnv *,
        jobject,
        jlong target,
        jlong replace,
        jlong origOut) {
    void **orig = origOut != 0 ? reinterpret_cast<void **>(origOut) : nullptr;
    if (dobby::PLTHook(reinterpret_cast<void *>(target),
                       reinterpret_cast<void *>(replace), orig)
        != 0) {
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_sumicya_qself_native_HookNative_nativePltUnhook(JNIEnv *, jobject, jlong target) {
    if (dobby::PLTHookRestore(reinterpret_cast<void *>(target)) != 0) {
        return -1;
    }
    return 0;
}

}  // extern "C"
