/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import sumicya.qself.Qself
import sumicya.qself.R
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.QselfFeature

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

class FeatureAdapter(
    private val rows: List<UiRow>,
    private val onToggle: (QselfFeature, Boolean) -> Unit,
    private val onFeatureClick: (QselfFeature) -> Unit,
    private val onCopyLogs: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is UiRow.Diagnostics -> TYPE_DIAGNOSTICS
        is UiRow.Header -> TYPE_HEADER
        is UiRow.Feature -> TYPE_FEATURE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DIAGNOSTICS -> DiagnosticsViewHolder(
                inflater.inflate(R.layout.item_diagnostics, parent, false),
            )
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_header, parent, false),
            )
            else -> FeatureViewHolder(
                inflater.inflate(R.layout.item_feature, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is UiRow.Diagnostics -> (holder as DiagnosticsViewHolder).bind(row)
            is UiRow.Header -> (holder as HeaderViewHolder).bind(row)
            is UiRow.Feature -> (holder as FeatureViewHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class DiagnosticsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvVersion = view.findViewById<TextView>(R.id.diag_version)
        private val tvHost = view.findViewById<TextView>(R.id.diag_host)
        private val tvSettings = view.findViewById<TextView>(R.id.diag_settings)
        private val tvEngine = view.findViewById<TextView>(R.id.diag_engine)
        private val tvSelfTest = view.findViewById<TextView>(R.id.diag_self_test)

        fun bind(row: UiRow.Diagnostics) {
            tvVersion.text = itemView.context.getString(R.string.diag_version, row.version)
            tvHost.text = itemView.context.getString(R.string.diag_host, row.hostPackage)
            tvSettings.text = itemView.context.getString(R.string.diag_settings, row.sharedSettings)
            tvEngine.text = itemView.context.getString(R.string.diag_engine, row.nativeEngine)
            tvSelfTest.text = itemView.context.getString(R.string.diag_self_test, row.nativeSelfTest)
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.header_title)

        fun bind(row: UiRow.Header) {
            title.text = row.title
        }
    }

    inner class FeatureViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.feature_title)
        private val summary = view.findViewById<TextView>(R.id.feature_summary)
        private val tag = view.findViewById<Chip>(R.id.feature_tag)
        private val switchView = view.findViewById<MaterialSwitch>(R.id.feature_switch)

        fun bind(row: UiRow.Feature) {
            val feature = row.feature
            title.text = feature.name
            summary.text = feature.summary
            tag.isVisible = feature.experimental
            val isAction = feature is ActionFeature
            switchView.isVisible = !isAction
            if (!isAction) {
                switchView.isChecked = Qself.bridge.isEnabled(feature.id, feature.defaultEnabled)
                switchView.setOnClickListener {
                    onToggle(feature, switchView.isChecked)
                }
            }
            itemView.setOnClickListener {
                onFeatureClick(feature)
            }
        }
    }

    companion object {
        private const val TYPE_DIAGNOSTICS = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_FEATURE = 2
    }
}

private var View.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.GONE
    }
