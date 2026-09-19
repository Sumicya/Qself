/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import sumicya.qself.Qself
import sumicya.qself.R
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.QselfFeature

/** One line of the settings screen. */
sealed class UiRow {
    data class Diagnostics(
        val version: String,
        val hostPackage: String,
        val sharedSettings: String,
        val nativeEngine: String,
        val nativeSelfTest: String,
    ) : UiRow()

    data class Header(val title: String) : UiRow()

    data class Feature(val feature: QselfFeature) : UiRow()
}

/**
 * Plain [BaseAdapter] over [UiRow]s - the settings list is a framework
 * `ListView`, so nothing here depends on AndroidX or Material.
 */
class FeatureAdapter(
    private val context: Context,
    private var rows: List<UiRow>,
    private val onToggle: (QselfFeature, Boolean) -> Unit,
) : BaseAdapter() {

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): UiRow = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = TYPE_COUNT

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is UiRow.Diagnostics -> TYPE_DIAGNOSTICS
        is UiRow.Header -> TYPE_HEADER
        is UiRow.Feature -> TYPE_FEATURE
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(context)
        val view = convertView
            ?: inflater.inflate(layoutOf(getItemViewType(position)), parent, false)
        when (val row = rows[position]) {
            is UiRow.Diagnostics -> bindDiagnostics(view, row)
            is UiRow.Header -> bindHeader(view, row)
            is UiRow.Feature -> bindFeature(view, row)
        }
        return view
    }

    /** Replaces the rows (used once the shared settings finished loading). */
    fun submit(updated: List<UiRow>) {
        rows = updated
        notifyDataSetChanged()
    }

    private fun bindDiagnostics(view: View, row: UiRow.Diagnostics) {
        view.findViewById<TextView>(R.id.diag_version)
            .text = context.getString(R.string.diag_version, row.version)
        view.findViewById<TextView>(R.id.diag_host)
            .text = context.getString(R.string.diag_host, row.hostPackage)
        view.findViewById<TextView>(R.id.diag_settings)
            .text = context.getString(R.string.diag_settings, row.sharedSettings)
        view.findViewById<TextView>(R.id.diag_engine)
            .text = context.getString(R.string.diag_engine, row.nativeEngine)
        view.findViewById<TextView>(R.id.diag_self_test)
            .text = context.getString(R.string.diag_self_test, row.nativeSelfTest)
    }

    private fun bindHeader(view: View, row: UiRow.Header) {
        view.findViewById<TextView>(R.id.header_title).text = row.title
    }

    private fun bindFeature(view: View, row: UiRow.Feature) {
        val feature = row.feature
        view.findViewById<TextView>(R.id.feature_title).text = feature.name
        view.findViewById<TextView>(R.id.feature_summary).text = feature.summary
        view.findViewById<TextView>(R.id.feature_tag)
            .visibility = if (feature.experimental) View.VISIBLE else View.GONE

        val switchView = view.findViewById<Switch>(R.id.feature_switch)
        if (feature is ActionFeature) {
            // Actions have no state; the row itself is the control.
            switchView.visibility = View.GONE
            switchView.setOnCheckedChangeListener(null)
        } else {
            // Detach first: recycled rows must not report a stale change.
            switchView.visibility = View.VISIBLE
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = Qself.bridge.isEnabled(feature.id, feature.defaultEnabled)
            switchView.setOnCheckedChangeListener(
                CompoundButton.OnCheckedChangeListener { _, value -> onToggle(feature, value) },
            )
        }
    }

    private fun layoutOf(viewType: Int): Int = when (viewType) {
        TYPE_DIAGNOSTICS -> R.layout.item_diagnostics
        TYPE_HEADER -> R.layout.item_header
        else -> R.layout.item_feature
    }

    private companion object {
        const val TYPE_DIAGNOSTICS = 0
        const val TYPE_HEADER = 1
        const val TYPE_FEATURE = 2
        const val TYPE_COUNT = 3
    }
}
