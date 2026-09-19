/*
 * Qself — LSPlant bridge (see lsplant_bridge.h).
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "lsplant_bridge.h"

#include <lsplant.hpp>

#include <cstring>
#include <mutex>
#include <string>
#include <string_view>

#include "art_symbols.h"
#include "dobby.h"

namespace qself::art {
namespace {

bool g_ready = false;
std::once_flag g_init;
std::string g_status = "not initialised";

/** LSPlant's inline hooker: Dobby, with the trampoline as the backup. */
void* DobbyInlineHook(void* target, void* hooker) {
    void* backup = nullptr;
    if (DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(hooker),
                  reinterpret_cast<dobby_dummy_func_t*>(&backup)) != 0) {
        return nullptr;
    }
    return backup;
}

bool DobbyInlineUnhook(void* func) {
    return DobbyDestroy(func) == 0;
}

void InitInternal(JNIEnv* env) {
    if (SymbolCount() == 0) {
        g_status = std::string("no libart symbols: ") + LsplantStatus();
        return;
    }
    const lsplant::InitInfo info{
        .inline_hooker = &DobbyInlineHook,
        .inline_unhooker = &DobbyInlineUnhook,
        .art_symbol_resolver =
            [](std::string_view name) { return Resolve(name); },
        .art_symbol_prefix_resolver =
            [](std::string_view prefix) { return ResolvePrefix(prefix); },
    };
    g_ready = lsplant::Init(env, info);
    g_status = g_ready ? "ready" : "lsplant::Init failed";
}

}  // namespace

bool LsplantReady() {
    return g_ready;
}

bool LsplantInit(JNIEnv* env) {
    if (env == nullptr) {
        g_status = "no JNIEnv";
        return false;
    }
    std::call_once(g_init, [env] { InitInternal(env); });
    return g_ready;
}

const char* LsplantStatus() {
    return g_status.c_str();
}

jobject LsplantHook(JNIEnv* env, jobject target, jobject hooker, jobject callback) {
    if (!g_ready || target == nullptr || hooker == nullptr || callback == nullptr) {
        return nullptr;
    }
    return lsplant::Hook(env, target, hooker, callback);
}

bool LsplantUnhook(JNIEnv* env, jobject target) {
    if (!g_ready || target == nullptr) {
        return false;
    }
    return lsplant::UnHook(env, target);
}

bool LsplantIsHooked(JNIEnv* env, jobject target) {
    if (!g_ready || target == nullptr) {
        return false;
    }
    return lsplant::IsHooked(env, target);
}

bool LsplantDeoptimize(JNIEnv* env, jobject target) {
    if (!g_ready || target == nullptr) {
        return false;
    }
    return lsplant::Deoptimize(env, target);
}

}  // namespace qself::art
