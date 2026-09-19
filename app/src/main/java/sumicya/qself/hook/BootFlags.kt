/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.hook

import java.io.File

/**
 * Root-controlled diagnostic switches, read from the host's own data dir:
 *
 * ```bash
 * su -c 'mkdir -p /data/data/com.tencent.mobileqq/files/qself \
 *          && touch /data/data/com.tencent.mobileqq/files/qself/safe-mode'
 * ```
 *
 * They exist so a device that crashes on start can be bisected without
 * rebuilding the module:
 *
 * | flag | effect |
 * |---|---|
 * | `safe-mode` | the module loads and logs, and then does **nothing** — no boot trigger, no engine, no feature hooks. It isolates "the framework injected our classes" from "our code ran". |
 * | `use-native` | opt *in* to the native engine (LSPlant/Dobby). Without this file the framework\'s Java engine does all hooking, which is the safe default: LSPlant patches ART internals, and an ART (or PAC/JIT configuration) it was not verified against can take the host down. |
 *
 * Deleting the file restores normal behaviour.
 */
object BootFlags {

    private const val DIR = "files/qself"
    const val SAFE_MODE = "safe-mode"
    const val USE_NATIVE = "use-native"

    fun safeMode(dataDir: String?): Boolean = exists(dataDir, SAFE_MODE)

    /** Opt-in: the native engine is experimental until device-verified. */
    fun useNative(dataDir: String?): Boolean = exists(dataDir, USE_NATIVE)

    private fun exists(dataDir: String?, name: String): Boolean = try {
        dataDir != null && File(File(dataDir, DIR), name).exists()
    } catch (t: Throwable) {
        false
    }
}
