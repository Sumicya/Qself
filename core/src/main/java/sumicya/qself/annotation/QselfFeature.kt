/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.annotation

/**
 * Marks a feature object for the KSP-generated feature registry.
 *
 * All metadata is carried by the annotation so the generated registry is a
 * plain list of object references — no runtime reflection on the metadata.
 *
 * @param id stable identifier used in settings storage, e.g. "misc.anti_update"
 * @param name display name (zh)
 * @param summary short description shown under the name
 * @param category FeatureCategory name: message, group, friend, qwallet,
 *   qzone, notification, media, ui, misc
 * @param enabledByDefault initial switch state
 * @param experimental shown with a "beta" tag in the UI
 * @param processes comma-separated ProcessKind names this feature installs in,
 *   e.g. "main" or "main,msf"
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class QselfFeature(
    val id: String,
    val name: String,
    val summary: String = "",
    val category: String = "misc",
    val enabledByDefault: Boolean = false,
    val experimental: Boolean = false,
    val processes: String = "main",
)
