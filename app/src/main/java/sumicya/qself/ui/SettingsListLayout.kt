/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.qauxv.R

/** The production fragment/list measurement chain, also used by native integration tests. */
class SettingsListLayout(context: Context) : FrameLayout(context) {
    val recycler = RecyclerView(context).apply {
        id = R.id.fragmentMainRecyclerView
        layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        clipToPadding = false
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) SettingsGlass.invalidateMaterials(recyclerView)
            }
        })
    }
    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
