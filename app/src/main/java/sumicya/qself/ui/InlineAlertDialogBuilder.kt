/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Preserves existing list/input/button callbacks, but mounts their content below the active row. */
class InlineAlertDialogBuilder @JvmOverloads constructor(context: Context, theme: Int = 0) : AlertDialog.Builder(context, theme) {
    override fun create(): AlertDialog {
        if (!InlineSettings.available(context)) return super.create()
        // This is our bundled AppCompat implementation, not a hidden Android API.
        // Copy AlertParams rather than transplanting another dialog's controller: that would
        // leave button handlers dismissing the wrong Dialog and lose cancel/save semantics.
        val dialog = InlineDialog(context)
        val paramsField = AlertDialog.Builder::class.java.getDeclaredField("P").apply { isAccessible = true }
        val params = paramsField.get(this)
        val controller = AlertDialog::class.java.getDeclaredField("mAlert").apply { isAccessible = true }.get(dialog)
        params.javaClass.getDeclaredMethod("apply", controller.javaClass).apply { isAccessible = true }.invoke(params, controller)
        fun param(name: String): Any? = params.javaClass.getField(name).apply { isAccessible = true }.get(params)
        dialog.setCancelable(param("mCancelable") as Boolean)
        dialog.setOnCancelListener(param("mOnCancelListener") as? DialogInterface.OnCancelListener)
        dialog.setOnDismissListener(param("mOnDismissListener") as? DialogInterface.OnDismissListener)
        (param("mOnKeyListener") as? DialogInterface.OnKeyListener)?.let { dialog.setOnKeyListener(it) }
        return dialog
    }
    override fun show(): AlertDialog = create().also { it.show() }

    private class InlineDialog(context: Context) : AlertDialog(context) {
        private var close: (() -> Unit)? = null
        private var shown = false
        private var cancelable = true
        private var cancelListener: DialogInterface.OnCancelListener? = null
        private var dismissListener: DialogInterface.OnDismissListener? = null
        private var showListener: DialogInterface.OnShowListener? = null
        override fun setCancelable(flag: Boolean) { cancelable = flag; super.setCancelable(flag) }
        override fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) { cancelListener = listener }
        override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { dismissListener = listener }
        override fun setOnShowListener(listener: DialogInterface.OnShowListener?) { showListener = listener }
        override fun isShowing(): Boolean = shown
        override fun show() {
            if (shown) return
            create() // Inflate AppCompat's content without adding a Window to WindowManager.
            val content = window!!.decorView.findViewById<ViewGroup>(android.R.id.content)
            val panel = content.getChildAt(0)
            content.removeView(panel)
            close = InlineSettings.show(context, panel, Runnable {
                if (shown) { shown = false; close = null; dismissListener?.onDismiss(this) }
            }, cancelable, Runnable { cancel() })
            check(close != null) { "Inline settings host detached before opening content" }
            shown = true
            showListener?.onShow(this)
        }
        override fun dismiss() { close?.invoke() }
        override fun cancel() {
            if (shown && cancelable) { cancelListener?.onCancel(this); dismiss() }
        }
    }
}
