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
        (viewHolder.itemView as SettingsHomeView).bind(state(), mode(), action)
    }
    override fun onItemClick(v: View, position: Int, x: Int, y: Int) = Unit
}
