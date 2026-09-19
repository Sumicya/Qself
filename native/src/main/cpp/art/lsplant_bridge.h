/*
 * Qself — LSPlant bridge (ART-level Java method hooking).
 *
 * LSPlant is the ART hooking core used by LSPosed: it patches ArtMethod
 * entries and needs two things from us, both provided here:
 *
 *  - an inline hooker (Dobby) to rewrite the compiled entry points, and
 *  - a libart.so symbol resolver (see art_symbols.h) to find ART internals.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef QSELF_LSPLANT_BRIDGE_H
#define QSELF_LSPLANT_BRIDGE_H

#include <jni.h>

namespace qself::art {

/** True once LSPlant initialized successfully. */
bool Ready();

/** Idempotent; returns whether LSPlant is usable afterwards. */
bool Init(JNIEnv* env);

/** Human-readable state for the settings diagnostics. */
const char* Status();

/** Hook a Method/Constructor; returns the backup executable, or null. */
jobject Hook(JNIEnv* env, jobject target, jobject hooker, jobject callback);

bool Unhook(JNIEnv* env, jobject target);

bool IsHooked(JNIEnv* env, jobject target);

/** Deoptimize so inlined call sites stop using the old body. */
bool Deoptimize(JNIEnv* env, jobject target);

}  // namespace qself::art

#endif  // QSELF_LSPLANT_BRIDGE_H
