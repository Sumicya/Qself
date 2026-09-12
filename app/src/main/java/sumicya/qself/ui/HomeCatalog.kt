/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

/** In-code catalog groups; never mutates feature configuration. */
object HomeCatalog {
    data class Section(val id: String, val title: String, val summary: String, val features: List<String>)

    @JvmField
    val sections = listOf(
        Section("appearance", "外观与净化", "统一净化 · 外观调整", sumicya.qself.feature.consolidation.FeatureCatalog.featuresForHome("appearance")),
        Section("chat", "聊天", "消息工具 · 统一标注", sumicya.qself.feature.consolidation.FeatureCatalog.featuresForHome("chat")),
        Section("people", "群与好友", "好友记录 · 群工具", sumicya.qself.feature.consolidation.FeatureCatalog.featuresForHome("people")),
        Section("tools", "工具", "通知 · 媒体 · 维护", sumicya.qself.feature.consolidation.FeatureCatalog.featuresForHome("tools")),
    )

    const val DIAGNOSTICS = "sumicya.qself.diagnostics.ReportDiagnostics"
    const val SEARCH = "search"
    const val THEME = "theme"
    const val BACKUP = "backup"
    const val CATALOG = "catalog"
    const val ABOUT = "about"
}
