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
    @Test fun realDialogIsCenteredBoundedAndItsRowsDoNotHideTransparency() {
        val controller = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Qself_Expressive)
        controller.setup()
        try {
            val provider = Provider()
            val sheet = SettingsOptionSheet().apply {
                arguments = android.os.Bundle().apply { putString("group", "qself-messages") }
                providerLookup = { mapOf("cc.ioctl.hook.msg.ShowMsgCount" to provider) }
            }
            sheet.showNow(activity.supportFragmentManager, "fixture-window")
            val window = sheet.requireDialog().window!!
            assertEquals(android.view.Gravity.CENTER, window.attributes.gravity)
            assertTrue(window.attributes.width < activity.resources.displayMetrics.widthPixels)
            assertTrue(window.attributes.height < activity.resources.displayMetrics.heightPixels)
            val root = sheet.requireView()
            root.animate().cancel(); root.alpha = 1f; root.translationY = 0f
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(window.attributes.width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(window.attributes.height, android.view.View.MeasureSpec.EXACTLY))
            root.layout(0, 0, window.attributes.width, window.attributes.height)
            assertTrue(root.background.alpha < 255)
            fun findCell(view: android.view.View): TitleValueCell? {
                if (view is TitleValueCell && view.title == provider.title) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findCell(view.getChildAt(i))?.let { return it }
                return null
            }
            val cell = requireNotNull(findCell(root))
            assertEquals(0, cell.switchView.left)
            assertEquals(cell.height, cell.switchView.height)
            val file = java.io.File("build/reports/qself-visual/small-window.png")
            file.parentFile.mkdirs()
            val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(image)
            canvas.drawColor(android.graphics.Color.rgb(180, 195, 210)); root.draw(canvas)
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            sheet.dismissNow()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun reportFactoryCreatesTheActualErrorFragment() {
        val fragment = io.github.qauxv.fragment.FuncStatusDetailsFragment.newInstance("fixture")
        assertEquals("fixture", fragment.arguments!!.getString(io.github.qauxv.fragment.FuncStatusDetailsFragment.TARGET_IDENTIFIER))
    }
}
