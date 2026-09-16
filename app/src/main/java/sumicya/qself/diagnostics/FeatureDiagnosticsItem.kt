/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import io.github.qauxv.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sumicya.qself.feature.dev.DiagLog
import sumicya.qself.ui.InlineAlertDialogBuilder

/**
 * The single local-diagnostics entry. Both local artifacts are read, toggled
 * and cleared here: the bounded metadata journal (switch on the row) and the
 * plain-text diag file, which has its own switch so the developer dumps can be
 * silenced without touching the journal.
 */
@UiItemAgentEntry
object FeatureDiagnosticsItem : BasePlainUiAgentItem("功能开关与错误记录",
    "默认记录本地元数据；点击查看/复制/清空。关闭后不再写入，不影响其他功能。") {
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override val switchProvider = object : ISwitchCellAgent {
        override val isCheckable = true
        override var isChecked: Boolean
            get() = FeatureJournal.enabled
            set(value) { FeatureJournal.enabled = value }
    }

    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        val options = arrayOf(
            "查看 / 复制本地诊断",
            "诊断文件记录：${if (DiagLog.enabled) "开" else "关"}",
            "清空功能记录",
            "清空诊断文件",
        )
        InlineAlertDialogBuilder(activity).setTitle("功能开关与错误记录").setItems(options) { _, which ->
            when (which) {
                0 -> (activity as? FragmentActivity)?.lifecycleScope?.launch {
                    val report = withContext(Dispatchers.IO) {
                        runCatching { diagnosticsReport() }
                            .getOrElse { "读取失败：${it.javaClass.simpleName}" }
                    }
                    if (!activity.isFinishing && !activity.isDestroyed) showReport(activity, report)
                }
                1 -> {
                    DiagLog.enabled = !DiagLog.enabled
                    val state = if (DiagLog.enabled) "诊断文件记录已开启。" else "诊断文件记录已关闭，新的内容不再写入。"
                    Toasts.info(activity, state)
                }
                2 -> (activity as? FragmentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    runCatching { FeatureJournal.clear() }
                }
                3 -> (activity as? FragmentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    runCatching { DiagLog.clear() }
                }
            }
        }.setNegativeButton("关闭", null).show()
    }

    /** Journal plus the bounded tail of the file, both local metadata only. */
    private fun diagnosticsReport(): String = buildString {
        append(FeatureJournal.report())
        append("\n[诊断文件 qself_diag.log]\n")
        append("记录开关=${if (DiagLog.enabled) "开" else "关"} 大小=${DiagLog.bytes()} 字节\n")
        val tail = DiagLog.read()
        append(if (tail.isEmpty()) "（暂无内容）" else tail)
    }

    private fun showReport(activity: Activity, report: String) {
        val text = TextView(activity).apply {
            this.text = report
            textSize = 12f
            setTextIsSelectable(true)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        InlineAlertDialogBuilder(activity).setTitle("本地诊断（功能记录 + 诊断文件）")
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("复制") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Qself 本地诊断", report))
                Toasts.info(activity, "已复制本地摘要；分享前请再次检查。")
            }
            .setNegativeButton("关闭", null).show()
    }
}
