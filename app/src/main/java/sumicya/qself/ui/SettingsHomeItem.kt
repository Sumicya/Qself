/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.qauxv.dsl.item.TMsgListItem

/** One full-width, wrap-height dashboard. No config access is hidden inside its layout factory. */
class SettingsHomeItem(
    private val state: () -> SettingsHomeView.State,
    private val mode: () -> Int,
    private val action: (String) -> Unit,
) : TMsgListItem {
    private var pendingState: android.os.Parcelable? = null
    private var boundView = java.lang.ref.WeakReference<SettingsHomeView>(null)
    var savedState: android.os.Parcelable?
        get() = boundView.get()?.onSaveInstanceState() ?: pendingState
        set(value) { pendingState = value }
    override val isEnabled = true
    override val isClickable = false
    override val isLongClickable = false
    override val isVoidBackground = true
    override fun createViewHolder(context: Context, parent: ViewGroup): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(SettingsHomeView(context).apply {
            id = io.github.qauxv.R.id.qself_settings_home
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }) { }
    override fun bindView(viewHolder: RecyclerView.ViewHolder, position: Int, context: Context) {
        val view = viewHolder.itemView as SettingsHomeView
        view.bind(state(), mode(), action)
        if (boundView.get() !== view) {
            pendingState?.let { view.onRestoreInstanceState(it) }
            boundView = java.lang.ref.WeakReference(view)
        }
    }
    override fun onItemClick(v: View, position: Int, x: Int, y: Int) = Unit
}
