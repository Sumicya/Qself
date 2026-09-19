/*
 * Qself native hook engine — JNI surface.
 *
 * Two layers:
 *  - Dobby: inline hooks on arbitrary native addresses, plus a real
 *    self-test that proves the replacement and the trampoline both work.
 *  - LSPlant: ART-level Java method hooking (see art/lsplant_bridge), driven
 *    by libart.so symbols resolved from the on-disk ELF image.
 *
 * Every entry point is defensive: a failure here must degrade to the
 * framework's Java-level engine, never take the host down.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>

#include <atomic>
#include <string>

#include "art/art_symbols.h"
#include "art/lsplant_bridge.h"
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

jstring ToJString(JNIEnv *env, const char *value) {
    return env->NewStringUTF(value != nullptr ? value : "");
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_sumicya_qself_engine_HookNative_nativeInit(JNIEnv *, jobject) {
    g_initialized.store(true);
    return 1;
}

JNIEXPORT jstring JNICALL
Java_sumicya_qself_engine_HookNative_nativeVersion(JNIEnv *env, jobject) {
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
Java_sumicya_qself_engine_HookNative_nativeSelfTest(JNIEnv *, jobject) {
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

/* --- libart.so symbols ---------------------------------------------------- */

/** Number of indexed libart.so symbols (0 = resolver failed). */
JNIEXPORT jlong JNICALL
Java_sumicya_qself_engine_HookNative_nativeArtSymbolCount(JNIEnv *, jobject) {
    return static_cast<jlong>(qself::art::SymbolCount());
}

/** Resolver state, e.g. "ok: 58421 symbols (dynsym 1203, symtab 57218)". */
JNIEXPORT jstring JNICALL
Java_sumicya_qself_engine_HookNative_nativeArtSymbolStatus(JNIEnv *env, jobject) {
    return ToJString(env, qself::art::Status());
}

/* --- LSPlant (ART Java method hooking) ------------------------------------ */

/** @return 1 when LSPlant is ready, 0 otherwise. */
JNIEXPORT jint JNICALL
Java_sumicya_qself_engine_HookNative_nativeLsplantInit(JNIEnv *env, jobject) {
    return qself::art::Init(env) ? 1 : 0;
}

JNIEXPORT jstring JNICALL
Java_sumicya_qself_engine_HookNative_nativeLsplantStatus(JNIEnv *env, jobject) {
    return ToJString(env, qself::art::Status());
}

/**
 * Hooks a java.lang.reflect.Executable (Method or Constructor).
 *
 * @return the backup executable to call the original, or null on failure
 */
JNIEXPORT jobject JNICALL
Java_sumicya_qself_engine_HookNative_nativeHookJava(JNIEnv *env, jobject,
                                                    jobject target, jobject hooker,
                                                    jobject callback) {
    return qself::art::Hook(env, target, hooker, callback);
}

JNIEXPORT jboolean JNICALL
Java_sumicya_qself_engine_HookNative_nativeUnhookJava(JNIEnv *env, jobject, jobject target) {
    return qself::art::Unhook(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_sumicya_qself_engine_HookNative_nativeIsHookedJava(JNIEnv *env, jobject, jobject target) {
    return qself::art::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_sumicya_qself_engine_HookNative_nativeDeoptimizeJava(JNIEnv *env, jobject, jobject target) {
    return qself::art::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
