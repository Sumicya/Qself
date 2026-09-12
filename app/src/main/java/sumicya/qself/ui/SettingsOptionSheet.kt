/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Dialog
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import kotlinx.coroutines.launch
import sumicya.qself.feature.consolidation.FeatureCatalog

/** One restorable modal, not a stack of sliding full-screen category pages. */
class SettingsOptionSheet : BottomSheetDialogFragment() {
    private var groupId: String? = null
    private lateinit var recycler: RecyclerView
    private lateinit var heading: TextView
    private lateinit var back: MaterialButton
    private val rows = ArrayList<Any>()
    private val providers by lazy { FunctionEntryRouter.queryAnnotatedUiItemAgentEntries().associateBy { it.javaClass.name } }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { groupId = null; populate() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        groupId = when {
            state != null -> state.getString("currentGroup")
            arguments?.getBoolean("hostRestore") == true -> arguments?.getString("currentGroup")
            else -> arguments?.getString("group")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putString("currentGroup", groupId)
        if (::recycler.isInitialized) out.putParcelable("scroll", recycler.layoutManager?.onSaveInstanceState())
    }
    fun saveForHost(): Bundle = Bundle(arguments ?: Bundle()).apply {
        putBoolean("hostRestore", true); putString("currentGroup", groupId)
        if (::recycler.isInitialized) putParcelable("scroll", recycler.layoutManager?.onSaveInstanceState())
    }
    override fun onCreateDialog(state: Bundle?): Dialog = BottomSheetDialog(requireContext(), theme).also {
        it.onBackPressedDispatcher.addCallback(this, backCallback)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireActivity()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(16, 8, 16, 0) }
        back = MaterialButton(context).apply { text = "返回"; contentDescription = "返回分类"; setOnClickListener { groupId = null; populate() } }
        top.addView(back, LinearLayout.LayoutParams(SettingsVisuals.dp(context, 80), SettingsVisuals.dp(context, 48)))
        heading = TextView(context).apply { textSize = 19f; gravity = Gravity.CENTER; setTextColor(SettingsAppearanceItem.overlayPalette(context).text) }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(MaterialButton(context).apply { text = "关闭"; setOnClickListener { dismiss() } },
            LinearLayout.LayoutParams(SettingsVisuals.dp(context, 80), SettingsVisuals.dp(context, 48)))
        root.addView(top)
        root.addView(TextView(context).apply {
            text = "✓ 开启   × 关闭   − 不支持或出错 · 各项独立"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(SettingsAppearanceItem.overlayPalette(context).secondary)
            setPadding(8, 8, 8, 8)
        })
        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            SettingsVisuals.addListSpacing(this)
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        populate()
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                providers.values.forEach { provider -> provider.uiItemAgent.valueState?.let { flow ->
                    launch { flow.collect { listAdapter.notifyDataSetChanged() } }
                } }
            }
        }
        val focus = arguments?.getString("focus")
        val index = rows.indexOfFirst { it is UiAgentItem && it.identifier == focus }
        val scroll = state?.getParcelable<android.os.Parcelable>("scroll")
            ?: arguments?.getParcelable<android.os.Parcelable>("scroll")
        if (scroll != null) recycler.layoutManager?.onRestoreInstanceState(scroll)
        else if (index >= 0) recycler.post { recycler.scrollToPosition(index) }
    }
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let {
            val sheet = it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@let
            sheet.background = SettingsAppearanceItem.material(requireContext(), sheet)
            sheet.layoutParams.height = (resources.displayMetrics.heightPixels * .85f).toInt()
            it.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            it.behavior.skipCollapsed = true
        }
    }
    private fun populate() {
        rows.clear()
        val group = FeatureCatalog.groups.firstOrNull { it.id == groupId }
        val canBack = group != null && arguments?.getString("group") == null
        back.visibility = if (canBack) View.VISIBLE else View.INVISIBLE
        backCallback.isEnabled = canBack
        heading.text = group?.title ?: "功能设置"
        if (group == null) {
            val home = arguments?.getString("home")
            rows.addAll(FeatureCatalog.groups.filter { it.id != "_core" && (home == null || it.home == home) })
        } else group.sections.forEach { section ->
            rows.add(section.title)
            section.features.forEach { id -> providers[id]?.let { provider ->
                rows.add(UiAgentItem(provider.itemAgentProviderUniqueIdentifier, id, provider).apply { beforeOpenDetails = { dismiss() } })
            } }
        }
        listAdapter.notifyDataSetChanged()
        recycler.scrollToPosition(0)
    }
    private val listAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) { is String -> 0; is UiAgentItem -> 1; else -> 2 }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val view = if (type == 0) HeaderCell(requireActivity()) else TitleValueCell(requireActivity())
            SettingsVisuals.decorateRow(view, requireActivity(), type != 0, SettingsAppearanceItem.overlayPalette(requireActivity()))
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = rows[position]) {
                is String -> (holder.itemView as HeaderCell).title = item
                is UiAgentItem -> item.bindView(holder, position, requireActivity())
                is FeatureCatalog.Group -> (holder.itemView as TitleValueCell).apply {
                    title = item.title; summary = "${item.sections.sumOf { it.features.size }} 项独立设置"; value = "›"
                    setOnClickListener { groupId = item.id; populate() }
                }
            }
        }
    }
    companion object {
        const val TAG = "qself-options"
        fun restore(activity: FragmentActivity, args: Bundle) {
            if (!activity.supportFragmentManager.isStateSaved && activity.supportFragmentManager.findFragmentByTag(TAG) == null)
                SettingsOptionSheet().apply { arguments = args }.showNow(activity.supportFragmentManager, TAG)
        }
        fun show(activity: FragmentActivity, home: String? = null, group: String? = null, focus: String? = null) {
            if (activity.supportFragmentManager.isStateSaved || activity.supportFragmentManager.findFragmentByTag(TAG) != null) return
            SettingsOptionSheet().apply { arguments = Bundle().apply { putString("home", home); putString("group", group); putString("focus", focus) } }
                .showNow(activity.supportFragmentManager, TAG)
        }
    }
}
