/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.hostapi.ui

import java.lang.reflect.Method

/**
 * Port: the conversation title bar (消息界面标题栏) button area.
 *
 * Batch-2 pilot (RFC-03 §8): two features (camera button removal, Super
 * QQShow badge removal) share one port because they target the same host
 * region and controller family. The volatile knowledge — per-version
 * obfuscated method names and generation boundaries — is adapter state,
 * exposed there as pure version-code tables (third testable seam).
 */
interface ConversationTitleBarApi {

    /** Resolved camera-button target(s). */
    sealed class CameraHandle {
        class QqPath(val hideMethod: Method, val removeMethod: Method) : CameraHandle()
        class PlayQqCrop(val cropMethod: Method) : CameraHandle()
    }

    /** Resolved Super QQShow badge target; the install shape differs per generation. */
    sealed class SuperShowHandle {
        /** NT-era config validator: blank by returning constant false. */
        class ConfigValidator(val method: Method) : SuperShowHandle()

        /** Legacy badge inflate path: blank by suppression. */
        class BadgeView(val method: Method) : SuperShowHandle()
    }

    /**
     * Resolve the camera/small-world button removal target for the running
     * host (QQ dual-method path or PlayQQ crop path). Null when absent.
     */
    fun resolveCameraButton(classLoader: ClassLoader): CameraHandle?

    /**
     * Resolve the Super QQShow badge removal target. Null when absent.
     */
    fun resolveSuperShowBadge(classLoader: ClassLoader): SuperShowHandle?

    /**
     * Install camera-button removal. For [CameraHandle.QqPath] both the
     * hide and the remove entry are suppressed; for [CameraHandle.PlayQqCrop]
     * the crop view is hidden after the original call.
     */
    fun installCameraRemove(
        handle: CameraHandle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean

    /**
     * Install Super QQShow badge removal.
     *
     * Note on fidelity: the legacy implementation gated the suppression
     * paths on the runtime toggle but left the config-validator path
     * ungated; both are preserved here bug-for-bug (see RFC-03 §8).
     */
    fun installSuperShowRemove(
        handle: SuperShowHandle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
