/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

/** Classification of the host process the module is running in. */
enum class ProcessKind(val displayName: String) {
    MAIN("main process"),
    MSF("long-connection process"),
    PUSH("push process"),
    CHANNEL("channel process"),
    MICROAPP("mini app"),
    FILEMANAGER("file manager"),
    OTHER("other"),
}

object ProcessState {

    /**
     * QQ's process naming is stable enough to classify on the suffix.
     * Anything not listed is [ProcessKind.OTHER]; features may opt in.
     */
    fun classify(packageName: String, processName: String): ProcessKind = when {
        processName == packageName -> ProcessKind.MAIN
        processName.endsWith(":MSF") -> ProcessKind.MSF
        processName.endsWith(":Push") -> ProcessKind.PUSH
        processName.endsWith(":Channel") -> ProcessKind.CHANNEL
        processName.endsWith(":MicroApp") -> ProcessKind.MICROAPP
        processName.endsWith(":filemanager") -> ProcessKind.FILEMANAGER
        else -> ProcessKind.OTHER
    }
}
