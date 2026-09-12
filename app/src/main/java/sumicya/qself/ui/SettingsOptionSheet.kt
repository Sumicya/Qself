/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Dialog
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.DialogFragment
import androidx.activity.ComponentDialog
import com.google.android.material.button.MaterialButton
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import kotlinx.coroutines.launch
import sumicya.qself.feature.consolidation.FeatureCatalog

/** One restorable modal, not a stack of sliding full-screen category pages. */
class SettingsOptionSheet : DialogFragment() {
    private var groupId: String? = null
    private lateinit var recycler: RecyclerView
    private lateinit var deck: FrameLayout
    private var transitioning = false
    private var homeScroll: android.os.Parcelable? = null
    private lateinit var listAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    private lateinit var heading: TextView
    private lateinit var back: MaterialButton
    private val rows = ArrayList<Any>()
    internal var providerLookup: () -> Map<String, io.github.qauxv.base.IUiItemAgentProvider> = {
        FunctionEntryRouter.queryAnnotatedUiItemAgentEntries().associateBy { it.javaClass.name }
    }
    private val providers by lazy { providerLookup() }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { navigate(null, false) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        homeScroll = state?.getParcelable("homeScroll") ?: arguments?.getParcelable("homeScroll")
        groupId = when {
            state != null -> state.getString("currentGroup")
            arguments?.getBoolean("hostRestore") == true -> arguments?.getString("currentGroup")
            else -> arguments?.getString("group")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putString("currentGroup", groupId); out.putParcelable("homeScroll", homeScroll)
        if (::recycler.isInitialized) out.putParcelable("scroll", recycler.layoutManager?.onSaveInstanceState())
    }
    fun saveForHost(): Bundle = Bundle(arguments ?: Bundle()).apply {
        putBoolean("hostRestore", true); putString("currentGroup", groupId); putParcelable("homeScroll", homeScroll)
        if (::recycler.isInitialized) putParcelable("scroll", recycler.layoutManager?.onSaveInstanceState())
    }
    override fun onCreateDialog(state: Bundle?): Dialog = ComponentDialog(requireContext(), theme).also {
        it.onBackPressedDispatcher.addCallback(this, backCallback)
        it.requestWindowFeature(Window.FEATURE_NO_TITLE)
        it.window?.setWindowAnimations(if (SettingsMotion.enabled()) io.github.qauxv.R.style.QselfSmallWindowAnimation else 0)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireActivity()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(SettingsVisuals.dp(context, 8), SettingsVisuals.dp(context, 8), SettingsVisuals.dp(context, 8), 0) }
        back = MaterialButton(context).apply { text = "‹"; textSize = 24f; setPadding(0, 0, 0, 0); contentDescription = "返回分类"; setOnClickListener { navigate(null, false) } }
        top.addView(back, LinearLayout.LayoutParams(SettingsVisuals.dp(context, 56), -2))
        heading = TextView(context).apply { textSize = 19f; gravity = Gravity.CENTER; setTextColor(SettingsAppearanceItem.overlayPalette(context).text) }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(MaterialButton(context).apply { text = "×"; textSize = 24f; contentDescription = "关闭小窗"; setPadding(0, 0, 0, 0); setOnClickListener { dismiss() } },
            LinearLayout.LayoutParams(SettingsVisuals.dp(context, 56), -2))
        root.addView(top)
        root.addView(TextView(context).apply {
            text = "✓ 开启   × 关闭   − 不支持或出错 · 各项独立"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(SettingsAppearanceItem.overlayPalette(context).secondary)
            setPadding(8, 8, 8, 8)
        })
        deck = FrameLayout(context)
        root.addView(deck, LinearLayout.LayoutParams(-1, 0, 1f))
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
        val window = dialog?.window ?: return
        val dm = resources.displayMetrics
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setDimAmount(.12f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.CENTER)
        window.setLayout(minOf(dm.widthPixels - SettingsVisuals.dp(requireContext(), 32), SettingsVisuals.dp(requireContext(), 560)),
            minOf((dm.heightPixels * .72f).toInt(), SettingsVisuals.dp(requireContext(), 640)))
        view?.let { root ->
            root.background = SettingsAppearanceItem.material(requireContext(), root)
            root.clipToOutline = true
            SettingsMotion.enter(root)
        }
    }
    override fun onStop() {
        view?.animate()?.cancel()
        super.onStop()
    }
    override fun onDestroyView() {
        TransitionManager.endTransitions(deck)
        view?.animate()?.cancel()
        recycler.adapter = null
        super.onDestroyView()
    }
    private fun navigate(id: String?, forward: Boolean) {
        if (transitioning || id == groupId) return
        if (groupId == null) homeScroll = recycler.layoutManager?.onSaveInstanceState()
        groupId = id
        populate(true, forward)
    }
    private fun populate(animate: Boolean = false, forward: Boolean = true) {
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
        val previous = if (::recycler.isInitialized) recycler else null
        listAdapter = adapterFor(rows.toList())
        recycler = RecyclerView(requireActivity()).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            SettingsVisuals.addListSpacing(this)
        }
        if (animate && SettingsMotion.enabled()) {
            transitioning = true
            val motion = MaterialSharedAxis(MaterialSharedAxis.Y, forward).apply {
                duration = SettingsMotion.duration(requireContext())
                addListener(object : TransitionListenerAdapter() {
                    override fun onTransitionEnd(transition: Transition) { transitioning = false; previous?.adapter = null }
                    override fun onTransitionCancel(transition: Transition) { transitioning = false; previous?.adapter = null }
                })
            }
            TransitionManager.beginDelayedTransition(deck, motion)
        } else previous?.adapter = null
        deck.removeAllViews()
        deck.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        if (groupId == null) recycler.layoutManager?.onRestoreInstanceState(homeScroll)
        if (animate && SettingsMotion.enabled()) {
            heading.alpha = 0f
            heading.animate().alpha(1f).setDuration(SettingsMotion.duration(requireContext(), true)).start()
        }
    }
    private fun adapterFor(items: List<Any>) = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) { is String -> 0; is UiAgentItem -> 1; else -> 2 }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val view = if (type == 0) HeaderCell(requireActivity()) else TitleValueCell(requireActivity())
            val palette = SettingsAppearanceItem.overlayPalette(requireActivity())
            SettingsVisuals.decorateRow(view, requireActivity(), type != 0, palette)
            if (type != 0) view.background = SettingsVisuals.surface(requireActivity(), palette.copy(surface = android.graphics.Color.TRANSPARENT), 12, true)
            return object : RecyclerView.ViewHolder(view) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is String -> (holder.itemView as HeaderCell).title = item
                is UiAgentItem -> item.bindView(holder, position, requireActivity())
                is FeatureCatalog.Group -> (holder.itemView as TitleValueCell).apply {
                    title = item.title; summary = "${item.sections.sumOf { it.features.size }} 项独立设置"; value = "›"
                    setOnClickListener { navigate(item.id, true) }
                }
            }
        }
    }
    companion object {
        const val TAG = "qself-options"
        fun restore(activity: FragmentActivity, args: Bundle) {
            args.classLoader = activity.javaClass.classLoader
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
