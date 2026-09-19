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
 * | `no-native` | everything runs, but the native engine (LSPlant/Dobby) is never used: the framework's Java engine installs the hooks instead. |
 *
 * Deleting the file restores normal behaviour.
 */
object BootFlags {

    private const val DIR = "files/qself"
    const val SAFE_MODE = "safe-mode"
    const val NO_NATIVE = "no-native"

    fun safeMode(dataDir: String?): Boolean = exists(dataDir, SAFE_MODE)

    fun noNative(dataDir: String?): Boolean = exists(dataDir, NO_NATIVE)

    private fun exists(dataDir: String?, name: String): Boolean = try {
        dataDir != null && File(File(dataDir, DIR), name).exists()
    } catch (t: Throwable) {
        false
    }
}
