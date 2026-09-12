/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.dsl.item

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import io.github.qauxv.base.IDynamicHook
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.base.RuntimeErrorTracer
import io.github.qauxv.core.HookInstaller
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.func.IDslItemNode
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.hostInfo

class UiAgentItem(
        override val identifier: String,
        override val name: String,
        val agentProvider: IUiItemAgentProvider,
) : IDslItemNode, TMsgListItem {

    var beforeOpenDetails: (() -> Unit)? = null

    override val isSearchable: Boolean = true
    override val isClickable: Boolean get() = isEnabled || hasFailure()
    override val isEnabled: Boolean
        get() {
            val agent = agentProvider.uiItemAgent
            return agent.validator?.invoke(agent) ?: true
        }

    override val isVoidBackground: Boolean = false

    class HeaderViewHolder(cell: TitleValueCell) : RecyclerView.ViewHolder(cell)

    override fun createViewHolder(context: Context, parent: ViewGroup): RecyclerView.ViewHolder {
        return HeaderViewHolder(TitleValueCell(context))
    }

    private fun restoreCheck(button: CompoundButton, value: Boolean) {
        button.setOnCheckedChangeListener(null)
        button.isChecked = value
        button.setOnCheckedChangeListener(mCheckChangedListener)
    }

    private val mCheckChangedListener = CompoundButton.OnCheckedChangeListener { btn, isChecked ->
        sumicya.qself.ui.InlineSettings.anchor(btn)
        val agent = agentProvider.uiItemAgent
        val funcName = agent.titleProvider.invoke(agent)
        val switchCellAgent = agent.switchProvider
        val unsupported = agentProvider is IDynamicHook && !agentProvider.isAvailable
        val previous = switchCellAgent?.isChecked ?: false
        val action = {
            switchCellAgent?.isChecked = isChecked
            sumicya.qself.diagnostics.FeatureJournal.toggle(agentProvider.javaClass.name, previous, switchCellAgent?.isChecked ?: previous)
            // if the function is enabled but not initialized, initialize it
            if (agentProvider is IDynamicHook) {
                val hook: IDynamicHook = agentProvider
                val context = btn.context
                if (hook.isEnabled && !hook.isInitialized) {
                    // we need to initialize the hook
                    val row = java.lang.ref.WeakReference(btn.parent as? TitleValueCell)
                    HookInstaller.initializeHookForeground(context, hook) {
                        row.get()?.takeIf { it.isAttachedToWindow && it.getTag(io.github.qauxv.R.id.qself_bound_agent) === this }?.let {
                            bindCell(it, -1, it.context)
                        }
                    }
                }
                if (hook.isApplicationRestartRequired) {
                    Toasts.info(context, "重启 ${hostInfo.hostName} 生效")
                }
            }
        }
        if (unsupported && isChecked) {
            val ctx = CommonContextWrapper.createAppCompatContext(btn.context)
            // confirm
            sumicya.qself.ui.InlineAlertDialogBuilder(ctx)
                .setTitle("不支持的功能")
                .setMessage("此功能（$funcName）暂不支持在 ${hostInfo.hostName} ${hostInfo.versionName}(${hostInfo.versionCode32}) 上使用，仍然要开启吗？")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    action()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    restoreCheck(btn, previous)
                }
                .setOnCancelListener {
                    restoreCheck(btn, previous)
                }
                .setCancelable(true)
                .show()
        } else {
            action()
        }
    }

    private val mOnClickListener = View.OnClickListener {
        onItemClick(it, -1, -1, -1)
    }

    override fun bindView(viewHolder: RecyclerView.ViewHolder, position: Int, context: Context) {
        bindCell(viewHolder.itemView as TitleValueCell, position, context)
    }

    private fun bindCell(cell: TitleValueCell, position: Int, context: Context) {
        // Identity check prevents a late initialization callback rebinding a recycled row.
        if (cell.getTag(io.github.qauxv.R.id.qself_bound_agent) !== this) cell.inlineContent.removeAllViews()
        cell.setTag(io.github.qauxv.R.id.qself_bound_agent, this)
        cell.setOnClickListener(null)
        cell.switchView.setOnCheckedChangeListener(null)
        val agent = agentProvider.uiItemAgent
        cell.title = agent.titleProvider.invoke(agent)
        val description: CharSequence? = agent.summaryProvider?.invoke(agent, context)
        val valueState = agent.valueState
        // value state observers are registered in the fragment, we only need to update the value
        val valueStateValue: String? = valueState?.value
        val switchAgent: ISwitchCellAgent? = agent.switchProvider
        val hasError = hasFailure()
        cell.hasError = hasError
        cell.isUnavailable = agentProvider is IDynamicHook && !agentProvider.isAvailable
        if (switchAgent != null) {
            // has switch!!, must not both have a switch and a value
            var toBeShownAtSummary: CharSequence? = valueState?.value
            if (valueStateValue.isNullOrEmpty()) {
                toBeShownAtSummary = description
            }
            cell.summary = if (toBeShownAtSummary.isNullOrEmpty()) null else toBeShownAtSummary
            cell.isHasSwitch = true
            cell.switchView.setCheckedWithoutAnimation(switchAgent.isChecked)
            cell.switchView.isEnabled = isEnabled && switchAgent.isCheckable
            cell.switchView.isClickable = isEnabled && switchAgent.isCheckable
            cell.switchView.setOnCheckedChangeListener(mCheckChangedListener)
        } else {
            // simple case, as it is
            cell.isHasSwitch = false
            cell.summary = description
            cell.value = valueStateValue
        }
        if (hasError || cell.isUnavailable) {
            cell.summary = (if (hasError) "出现错误 · 查看功能错误记录" else "当前版本不支持") +
                cell.summary?.let { "\n$it" }.orEmpty()
        }
        cell.setOnClickListener(mOnClickListener)
        cell.setOnLongClickListener { onLongClick(it, position, -1, -1) }
    }

    override fun onItemClick(v: View, position: Int, x: Int, y: Int) {
        sumicya.qself.ui.InlineSettings.anchor(v)
        val agent = agentProvider.uiItemAgent
        val cell = v as TitleValueCell
        if (hasFailure()) { showFailure(v); return }
        if (!isEnabled) return
        val activity = findActivity(v.context) ?: return
        val onClick = agent.onClickListener
        if (onClick != null) {
            beforeOpenDetails?.invoke()
            onClick.invoke(agent, activity, v)
        } else {
            // check if it has switch
            if (cell.isHasSwitch && cell.switchView.isEnabled) {
                cell.switchView.toggle()
            }
        }
    }

    override val isLongClickable: Boolean get() = agentProvider is IDynamicHook || agentProvider is RuntimeErrorTracer

    override fun onLongClick(v: View, position: Int, x: Int, y: Int): Boolean {
        if (!isLongClickable) return false
        sumicya.qself.ui.InlineSettings.anchor(v)
        showFailure(v)
        return true
    }

    private fun hasFailure(): Boolean =
        (agentProvider is IDynamicHook && ((agentProvider.isInitialized && !agentProvider.isInitializationSuccessful) || agentProvider.runtimeErrors.isNotEmpty())) ||
            (agentProvider is RuntimeErrorTracer && agentProvider.hasRuntimeErrors)

    private fun showFailure(view: View) {
        val activity = findActivity(view.context) ?: return
        val report = buildString {
            append(name).append("\n").append(agentProvider.javaClass.name).append("\n")
            if (agentProvider is IDynamicHook) {
                append("配置：").append(if (agentProvider.isEnabled) "开启" else "关闭")
                append("；初始化：").append(if (!agentProvider.isInitialized) "尚未尝试" else if (agentProvider.isInitializationSuccessful) "已完成" else "失败").append("\n")
            }
            val errors = if (agentProvider is RuntimeErrorTracer)
                io.github.qauxv.fragment.FuncStatListFragment.collectFunctionErrors(agentProvider).toList()
                else (agentProvider as? IDynamicHook)?.runtimeErrors.orEmpty()
            if (errors.isEmpty()) append("当前进程没有记录到异常堆栈；不代表其他进程没有错误。")
            errors.takeLast(16).forEach { append("\n").append(android.util.Log.getStackTraceString(it).take(12000)) }
            append("\n\n点击左侧开关仅修改配置；复制和导出不会修改开关。完整报告可能含异常上下文，分享前请检查。")
        }
        val text = android.widget.TextView(activity).apply {
            this.text = report; textSize = 13f; setTextIsSelectable(true)
            val pad = sumicya.qself.ui.SettingsVisuals.dp(activity, 20); setPadding(pad, pad, pad, pad)
        }
        val builder = sumicya.qself.ui.InlineAlertDialogBuilder(activity)
            .setTitle("功能详情 / 错误输出")
            .setView(android.widget.ScrollView(activity).apply { addView(text) })
            .setPositiveButton("复制错误") { _, _ -> xyz.nextalone.util.SystemServiceUtils.copyToClipboard(activity, report); android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show() }
            .setNegativeButton("关闭", null)
        if (activity is io.github.qauxv.activity.SettingsUiFragmentHostActivity) {
            builder.setNeutralButton("完整报告 / 导出") { _, _ ->
                beforeOpenDetails?.invoke()
                activity.presentFragment(io.github.qauxv.fragment.FuncStatusDetailsFragment.newInstance(identifier))
            }
        }
        builder.show()
    }

    companion object {
        fun findActivity(context: Context): Activity? {
            var current = context
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
            while (visited.add(current)) {
                if (current is Activity) return current
                current = (current as? android.content.ContextWrapper)?.baseContext ?: return null
            }
            return null
        }
    }
}
