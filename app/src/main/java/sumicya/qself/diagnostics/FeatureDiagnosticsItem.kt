/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UiItemAgentEntry
object FeatureDiagnosticsItem : BasePlainUiAgentItem("功能开关与错误记录",
    "默认记录本地元数据；点击查看/复制/清空。关闭后不再写入，不影响 O3 诊断或拦截。") {
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY
    override val switchProvider = object : ISwitchCellAgent {
        override val isCheckable = true
        override var isChecked: Boolean
            get() = FeatureJournal.enabled
            set(value) { FeatureJournal.enabled = value }
    }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        (activity as? FragmentActivity)?.lifecycleScope?.launch {
            val report = withContext(Dispatchers.IO) { runCatching { FeatureJournal.report() }.getOrElse { "读取失败：${it.javaClass.simpleName}" } }
            if (!activity.isFinishing && !activity.isDestroyed) sumicya.qself.ui.InlineAlertDialogBuilder(activity)
                .setTitle("功能开关与错误记录").setMessage(report)
                .setPositiveButton("复制") { _, _ -> (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Qself 功能记录", report)) }
                .setNeutralButton("清空") { _, _ -> (activity as FragmentActivity).lifecycleScope.launch(Dispatchers.IO) { runCatching { FeatureJournal.clear() } } }
                .setNegativeButton("关闭", null).show()
        }
    }
}
