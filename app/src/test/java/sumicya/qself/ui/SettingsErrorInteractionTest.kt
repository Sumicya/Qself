/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import io.github.qauxv.R
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.RuntimeErrorTracer
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import io.github.qauxv.hook.BasePlainUiAgentItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ResourcesMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@ResourcesMode(ResourcesMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsErrorInteractionTest {
    private class Provider : BasePlainUiAgentItem("测试错误项", "独立开关"), RuntimeErrorTracer {
        var details = 0
        override val switchProvider = object : ISwitchCellAgent {
            override var isChecked = false
            override val isCheckable = true
        }
        override val runtimeErrors = listOf(IllegalStateException("fixture failure"))
        override val runtimeErrorDependentComponents: List<RuntimeErrorTracer>? = null
        override fun traceError(e: Throwable) = Unit
        override val uiItemLocation = emptyArray<String>()
        override val onClickListener: (io.github.qauxv.base.IUiItemAgent, Activity, android.view.View) -> Unit = { _, _, _ -> details++ }
    }
    @Test fun wrappedContextErrorClickAndCopyDoNotToggleOrCallFeatureDetails() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Qself_Expressive)
        controller.setup()
        try {
            val wrapped = ContextWrapper(ContextWrapper(activity))
            assertSame(activity, UiAgentItem.findActivity(wrapped))
            val provider = Provider()
            val item = UiAgentItem(provider.itemAgentProviderUniqueIdentifier, provider.title, provider)
            val parent = FrameLayout(wrapped)
            val holder = item.createViewHolder(wrapped, parent)
            item.bindView(holder, 0, wrapped)
            val cell = holder.itemView as TitleValueCell
            assertTrue(cell.hasError)
            cell.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse(provider.switchProvider.isChecked)
            assertEquals(0, provider.details)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("fixture failure"))
            assertFalse(provider.switchProvider.isChecked)
            cell.switchView.performClick()
            assertTrue(provider.switchProvider.isChecked)
            assertEquals(0, provider.details)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun inlineLegacyDialogKeepsSaveCancelAndCreatesNoWindow() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Qself_Expressive)
        controller.setup()
        try {
            val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            activity.setContentView(root)
            val row = TitleValueCell(activity).apply { title = "输入设置" }
            root.addView(row)
            InlineSettings.register(activity, root)
            InlineSettings.anchor(row)
            var saved = "old"
            var cancelled = 0
            val input = android.widget.EditText(activity).apply { id = android.view.View.generateViewId(); setText("draft") }
            val dialog = InlineAlertDialogBuilder(activity).setTitle("编辑")
                .setView(input).setPositiveButton("保存") { _, _ -> saved = input.text.toString() }
                .setNegativeButton("取消", null).setOnCancelListener { cancelled++ }.create()
            dialog.show()
            assertTrue(dialog.isShowing)
            assertEquals(1, row.inlineContent.childCount)
            assertSame(input, dialog.findViewById<android.widget.EditText>(input.id))
            assertNull(dialog.window!!.decorView.parent)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals("draft", saved)
            assertFalse(dialog.isShowing)
            assertEquals(0, row.inlineContent.childCount)
            val second = InlineAlertDialogBuilder(activity).setTitle("第二项").setMessage("取消不写配置")
                .setPositiveButton("保存") { _, _ -> saved = "wrong" }.setOnCancelListener { cancelled++ }.create()
            second.show()
            assertTrue(InlineSettings.back(activity))
            assertEquals(1, cancelled)
            assertEquals("draft", saved)
            assertFalse(second.isShowing)
            val locked = InlineAlertDialogBuilder(activity).setMessage("初始化中").setCancelable(false).create()
            locked.show()
            assertTrue(InlineSettings.back(activity))
            assertTrue(locked.isShowing)
            assertTrue(InlineSettings.collapseRow(row))
            assertTrue(locked.isShowing)
            locked.dismiss()
            assertEquals(0, row.inlineContent.childCount)
        } finally { InlineSettings.unregister(activity); controller.pause().stop().destroy() }
    }

    @Test fun backUnwindsLevelsInOpenOrderAndCascadesIntoCollapsedCards() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Qself_Expressive)
        controller.setup()
        try {
            val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            activity.setContentView(root)
            InlineSettings.register(activity, root)
            val row = TitleValueCell(activity).apply { title = "行内设置" }
            val card = SettingsAccordion(activity,
                SettingsVisuals.palette(activity), "分类", "副标题",
                io.github.qauxv.R.drawable.ic_settings) { row }
            root.addView(card)
            card.setExpanded(true, false)
            InlineSettings.anchor(row)
            assertTrue(InlineSettings.hasLevels(activity))

            // Level 2: a panel opened from a row inside the expanded card.
            var cancelled = 0
            InlineAlertDialogBuilder(activity).setTitle("面板").setMessage("内容")
                .setOnCancelListener { cancelled++ }.create().show()
            settle()
            assertEquals(1, row.inlineContent.childCount)

            // Back: the panel first - it is the innermost level.
            assertTrue(InlineSettings.back(activity))
            settle()
            assertEquals(1, cancelled)
            assertEquals(0, row.inlineContent.childCount)
            // The card is still open: back unwound exactly one level.
            assertTrue(card.expanded)
            assertTrue(InlineSettings.hasLevels(activity))

            // Back again: the card itself.
            assertTrue(InlineSettings.back(activity))
            settle()
            assertFalse(card.expanded)
            assertFalse(InlineSettings.hasLevels(activity))

            // Empty stack: the host may leave the screen.
            assertFalse(InlineSettings.back(activity))
        } finally {
            InlineSettings.unregister(activity)
            controller.pause().stop().destroy()
        }
    }

    @Test fun collapsingACardClosesPanelsOpenedInsideIt() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Qself_Expressive)
        controller.setup()
        try {
            val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            activity.setContentView(root)
            InlineSettings.register(activity, root)
            val row = TitleValueCell(activity).apply { title = "行内设置" }
            val card = SettingsAccordion(activity,
                SettingsVisuals.palette(activity), "分类", "副标题",
                io.github.qauxv.R.drawable.ic_settings) { row }
            root.addView(card)
            card.setExpanded(true, false)
            InlineSettings.anchor(row)
            var cancelled = 0
            InlineAlertDialogBuilder(activity).setTitle("面板").setOnCancelListener { cancelled++ }.create().show()
            settle()
            assertEquals(1, row.inlineContent.childCount)

            // Collapsing the card must take its open levels with it, so re-expanding is clean.
            card.setExpanded(false, false)
            settle()
            assertEquals(1, cancelled)
            assertEquals(0, row.inlineContent.childCount)
            assertFalse(InlineSettings.hasLevels(activity))
            card.setExpanded(true, false)
            assertEquals(0, row.inlineContent.childCount)
            assertTrue(InlineSettings.hasLevels(activity))
        } finally {
            InlineSettings.unregister(activity)
            controller.pause().stop().destroy()
        }
    }

    /**
     * These tests assert state, not motion. The shadow clock is advanced so the expand and
     * collapse animations run to their end (they never complete on their own under Robolectric),
     * which means the assertions observe the same end state the user ends up with.
     */
    private fun settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test fun reportFactoryCreatesTheActualErrorFragment() {
        val fragment = io.github.qauxv.fragment.FuncStatusDetailsFragment.newInstance("fixture")
        assertEquals("fixture", fragment.arguments!!.getString(io.github.qauxv.fragment.FuncStatusDetailsFragment.TARGET_IDENTIFIER))
    }
}
