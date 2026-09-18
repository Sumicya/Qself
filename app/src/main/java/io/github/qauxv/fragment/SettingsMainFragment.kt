/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or (at
 * your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.fragment

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cc.ioctl.dialog.WsaWarningDialog
import io.github.qauxv.R
import io.github.qauxv.config.SafeModeManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.func.CategoryDescription
import io.github.qauxv.dsl.func.FragmentDescription
import io.github.qauxv.dsl.func.IDslFragmentNode
import io.github.qauxv.dsl.func.IDslItemNode
import io.github.qauxv.dsl.func.IDslParentNode
import io.github.qauxv.dsl.func.UiItemAgentDescription
import io.github.qauxv.dsl.item.CategoryItem
import io.github.qauxv.dsl.item.DslTMsgListItemInflatable
import io.github.qauxv.dsl.item.SimpleListItem
import io.github.qauxv.dsl.item.TMsgListItem
import io.github.qauxv.dsl.item.UiAgentItem
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.UiThread
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.isInHostProcess
import sumicya.qself.feature.consolidation.FeatureCatalog
import sumicya.qself.ui.HomeActionRouter
import sumicya.qself.ui.HomeCatalog
import sumicya.qself.ui.SettingsAppearanceItem
import sumicya.qself.ui.SettingsHomeItem
import sumicya.qself.ui.SettingsHomeView
import sumicya.qself.ui.SettingsListLayout
import sumicya.qself.ui.SettingsOptionSheet
import sumicya.qself.ui.SettingsVisuals

/**
 * The module's settings screen. Two shapes share one fragment:
 *
 * - **home** — the Qself dashboard ([SettingsHomeView]); taps dispatch
 *   through [HomeActionRouter], which records every outcome.
 * - **content** — a DSL-described feature page inflated into the same list.
 */
class SettingsMainFragment : BaseRootLayoutFragment() {

    private lateinit var locations: Array<String>
    private lateinit var description: FragmentDescription
    private var targetUiAgentNavId: String? = null
    private var targetUiAgentNavigated = false

    private var searchSubFragment: SearchOverlaySubFragment? = null
    private var searchRootLayout: ViewGroup? = null
    private var searchMenuItem: MenuItem? = null

    private var adapter: RecyclerView.Adapter<*>? = null
    private var homeItem: SettingsHomeItem? = null
    private var homeViewState: android.os.Parcelable? = null
    private var recyclerListView: RecyclerView? = null
    private var rootFrameLayout: FrameLayout? = null
    private var router: HomeActionRouter? = null

    private lateinit var typeList: Array<Class<*>>
    private lateinit var itemList: ArrayList<TMsgListItem>
    private lateinit var itemTypeIds: Array<Int>
    private lateinit var itemTypeDelegate: Array<TMsgListItem>

    /* ------------------------------------------------------------- attach */

    override fun onAttach(context: Context) {
        super.onAttach(context)
        var location: Array<String> = arguments?.getStringArray(TARGET_FRAGMENT_LOCATION)
            ?: throw IllegalArgumentException("target fragment location is null")
        // Migrate a saved deep link by capability ID, not by its old category.
        arguments?.getString(TARGET_UI_AGENT_IDENTIFIER)?.let { id ->
            FunctionEntryRouter.locationForFeature(id)?.let { location = it.dropLast(1).toTypedArray() }
        }
        var desc = FunctionEntryRouter.findDescriptionByLocation(location)
        if (desc !is FragmentDescription) {
            // Old category-only saved state: show the catalog instead of crashing.
            location = emptyArray()
            desc = FunctionEntryRouter.settingsUiItemDslTree
            arguments?.putBoolean(SHOW_CATALOG, true)
        }
        locations = location
        val section = HomeCatalog.sections.firstOrNull { it.id == arguments?.getString(HOME_SECTION) }
        description = if (section != null) {
            FragmentDescription(section.id, section.title, false) {
                for (group in FeatureCatalog.groupsForHome(section.id)) {
                    FunctionEntryRouter.findDescriptionByLocation(group.path)?.let { addChild(it) }
                }
            }
        } else desc
        title = when {
            section != null -> section.title
            arguments?.getBoolean(SHOW_CATALOG) == true -> "功能与设置"
            // Home owns its in-content header (centered title, leading search).
            isHome() -> ""
            else -> description.name ?: "设置"
        }
        targetUiAgentNavId = arguments?.getString(TARGET_UI_AGENT_IDENTIFIER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        requireActivity().onBackPressedDispatcher.addCallback(this, searchBackCallback)
        router = HomeActionRouter(
            activity = { requireSettingsHostActivity() },
            openSearch = { openSearch() },
            listAnchor = { recyclerListView },
        )
    }

    /* --------------------------------------------------------------- view */

    override fun doOnCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = layoutInflater.context
        val rootView = SettingsListLayout(context).apply {
            background = SettingsVisuals.backdrop(SettingsVisuals.palette(context, SettingsAppearanceItem.mode))
        }
        rootFrameLayout = rootView
        if (homeViewState == null) homeViewState = savedInstanceState?.getParcelable("homeExpansion")

        val dslTree = if (isHome()) {
            arrayListOf<DslTMsgListItemInflatable>(createHomeItem())
        } else {
            convertFragmentDsl(context, description)
        }
        itemList = ArrayList()
        dslTree.forEach { itemList.addAll(it.inflateTMsgListItems(context)) }
        typeList = itemList.map { it.javaClass }.distinct().toTypedArray()
        itemTypeIds = Array(itemList.size) { typeList.indexOf(itemList[it].javaClass) }
        itemTypeDelegate = Array(typeList.size) { itemList[itemTypeIds.indexOf(it)] }

        recyclerListView = rootView.recycler
        if (!isHome()) SettingsVisuals.addListSpacing(rootView.recycler)
        rootView.recycler.adapter = buildAdapter(context)
        rootLayoutView = rootView.recycler
        if (isInHostProcess) WsaWarningDialog.showWsaWarningDialogIfNecessary(requireContext())
        return rootView
    }

    private fun createHomeItem(): SettingsHomeItem = SettingsHomeItem(
        {
            SettingsHomeView.State(
                if (isInHostProcess) "${hostInfo.hostName} ${hostInfo.versionName}" else "模块管理",
                sumicya.qself.diagnostics.FeatureJournal.enabled,
                SafeModeManager.getManager().isEnabledForThisTime
            )
        },
        { SettingsAppearanceItem.mode },
        ::openHomeAction
    ).also {
        it.savedState = homeViewState
        homeItem = it
    }

    private fun buildAdapter(context: Context): RecyclerView.Adapter<RecyclerView.ViewHolder> {
        val created = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                itemTypeDelegate[viewType].createViewHolder(context, parent)

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = itemList[position]
                item.bindView(holder, position, context)
                if (!item.isVoidBackground) SettingsVisuals.decorateRow(holder.itemView, context, item.isClickable)
            }

            override fun getItemCount() = itemList.size
            override fun getItemViewType(position: Int) = itemTypeIds[position]
        }
        adapter = created
        return created
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A view recreation must not keep observers pointing at a discarded row index.
        for ((index, item) in itemList.withIndex()) {
            val state = (item as? UiAgentItem)?.agentProvider?.uiItemAgent?.valueState ?: continue
            viewLifecycleOwner.lifecycleScope.launchWhenResumed {
                state.collect { adapter?.notifyItemChanged(index) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHome()) adapter?.notifyItemChanged(0)
        if (!targetUiAgentNavId.isNullOrEmpty() && !targetUiAgentNavigated) {
            navigateToTargetUiAgentItem()
        }
        subtitle = safeModeSubtitle()
    }

    private fun safeModeSubtitle(): String? {
        val hostName = hostInfo.hostName
        val now = SafeModeManager.getManager().isEnabledForThisTime
        val next = SafeModeManager.getManager().isEnabledForNextTime
        return when {
            now && next -> "安全模式（停用所有功能）"
            !now && next -> "安全模式需要重启 $hostName 生效"
            now && !next -> "重新启动 $hostName 后将退出安全模式"
            else -> null
        }
    }

    /* --------------------------------------------------------------- home */

    /** The dashboard's single dispatch point; every outcome is journaled. */
    private fun openHomeAction(action: String) {
        router?.route(action)
    }

    /* -------------------------------------------------------------- search */

    private fun openSearch() {
        val item = searchMenuItem
        if (item != null) {
            item.expandActionView()
            enterSearchMode(item.actionView as SearchView)
        } else {
            // Home has no toolbar menu: feed the overlay a standalone SearchView.
            enterSearchMode(SearchView(requireContext()).apply { isIconified = false })
        }
    }

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchMenuItem?.collapseActionView() != true) exitSearchMode()
        }
    }

    private fun enterSearchMode(searchView: SearchView) {
        val recycler = recyclerListView ?: return
        val root = rootFrameLayout ?: return
        if (searchSubFragment == null) {
            val fragment = SearchOverlaySubFragment().also {
                it.parent = this
                it.context = requireContext()
                it.settingsHostActivity = requireSettingsHostActivity()
            }
            val overlay = fragment.onCreateView(requireActivity().layoutInflater, root, null) as ViewGroup
            searchSubFragment = fragment
            searchRootLayout = overlay
            root.addView(overlay)
            recycler.visibility = View.GONE
            rootLayoutView = overlay
            applyRootLayoutPaddingFor(overlay)
            fragment.onResume()
            searchView.setOnCloseListener { exitSearchMode(); true }
        }
        searchSubFragment!!.initForSearchView(searchView)
        searchBackCallback.isEnabled = true
    }

    private fun exitSearchMode() = abortSearchMode()

    private fun abortSearchMode() {
        searchRootLayout?.let { rootFrameLayout?.removeView(it) }
        searchSubFragment?.onDestroyView()
        searchRootLayout = null
        searchSubFragment = null
        searchBackCallback.isEnabled = false
        recyclerListView?.let { recycler ->
            recycler.visibility = View.VISIBLE
            rootLayoutView = recycler
            applyRootLayoutPaddingFor(recycler)
        }
    }

    fun onNavigateToOtherFragment() {
        searchMenuItem?.collapseActionView()
        abortSearchMode()
    }

    /* ---------------------------------------------------- deep-link scroll */

    @UiThread
    private fun navigateToTargetUiAgentItem() {
        if (!isResumed) return
        val recycler = recyclerListView ?: return
        SyncUtils.postDelayed(300) {
            if (!isResumed || recyclerListView !== recycler) return@postDelayed
            val index = itemList.indexOfFirst {
                it is UiAgentItem && it.agentProvider.itemAgentProviderUniqueIdentifier == targetUiAgentNavId
            }
            if (index < 0) return@postDelayed
            val layoutManager = recycler.layoutManager as LinearLayoutManager
            layoutManager.scrollToPositionWithOffset(index, recycler.paddingTop + SettingsVisuals.dp(requireContext(), 12))
            recycler.postDelayed({
                if (isResumed && recyclerListView === recycler) {
                    layoutManager.findViewByPosition(index)?.let { itemView ->
                        highlightRect(Rect(itemView.left, itemView.top, itemView.right, itemView.bottom))
                    }
                }
            }, 100)
            targetUiAgentNavigated = true
        }
    }

    @UiThread
    private fun highlightRect(rect: Rect) {
        if (!isResumed) return
        val context = requireContext()
        val parent = rootFrameLayout ?: return
        val highlight = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(ResourcesCompat.getColor(context.resources, R.color.rippleColor, context.theme))
        }
        parent.addView(highlight, FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            setMargins(rect.left, rect.top, 0, 0)
        })
        // Native property animator respects the system animation scale, including animations off.
        highlight.animate().alpha(0f).setDuration(900).withEndAction { parent.removeView(highlight) }.start()
    }

    /* ------------------------------------------------------------- content */

    private fun convertFragmentDsl(context: Context, fragmentDsl: FragmentDescription): ArrayList<DslTMsgListItemInflatable> {
        val items = ArrayList<DslTMsgListItemInflatable>()
        for (child in fragmentDsl.children) {
            items += if (child.isEndNode()) convertEndNode(context, child)
            else convertParentNode(context, child as IDslParentNode)
        }
        return items
    }

    private fun convertParentNode(context: Context, parentNode: IDslParentNode): DslTMsgListItemInflatable {
        if (parentNode is CategoryDescription) {
            return CategoryItem(parentNode.name, null).also { category ->
                for (item in parentNode.children) category.add(convertEndNode(context, item))
            }
        }
        if (parentNode.isEndNode()) return convertEndNode(context, parentNode)
        throw UnsupportedOperationException("unsupported node type: " + parentNode.javaClass.name)
    }

    private fun convertEndNode(context: Context, endNode: IDslItemNode): DslTMsgListItemInflatable {
        if (endNode is UiItemAgentDescription) {
            return UiAgentItem(endNode.identifier, endNode.name, endNode.itemAgentProvider)
        }
        if (endNode is IDslFragmentNode) {
            return SimpleListItem(endNode.identifier, endNode.name ?: endNode.toString(), null).apply {
                onClickListener = click@{
                    FeatureCatalog.groupPath(endNode.identifier)?.let {
                        SettingsOptionSheet.show(requireSettingsHostActivity(), group = endNode.identifier)
                        return@click
                    }
                    val targetLocation = FunctionEntryRouter.resolveUiItemAnycastLocation(
                        arrayOf(FunctionEntryRouter.Locations.ANY_CAST_PREFIX, endNode.identifier)
                    ) ?: throw IllegalStateException("can not resolve anycast location for '${endNode.identifier}'")
                    val fragmentClass = endNode.getTargetFragmentClass(targetLocation)
                    val fragment = fragmentClass.newInstance() as BaseSettingFragment
                    fragment.arguments = endNode.getTargetFragmentArguments(targetLocation)
                    settingsHostActivity!!.presentFragment(fragment)
                }
            }
        }
        throw UnsupportedOperationException("unsupported node type: " + endNode.javaClass.name)
    }

    private fun IDslItemNode.isEndNode(): Boolean = this !is IDslParentNode || this is IDslFragmentNode

    /* ----------------------------------------------------------- lifecycle */

    override fun ownsHeader(): Boolean = isHome()

    private fun isRootFragmentDescription(): Boolean =
        locations.isEmpty() || locations.size == 1 && locations[0].isEmpty()

    private fun isHome(): Boolean = isRootFragmentDescription() &&
        arguments?.getString(HOME_SECTION) == null &&
        arguments?.getBoolean(SHOW_CATALOG) != true &&
        arguments?.getString(TARGET_UI_AGENT_IDENTIFIER) == null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // RecyclerView freezes only itself, not its child hierarchy.
        outState.putParcelable("homeExpansion", homeItem?.savedState ?: homeViewState)
    }

    override fun onDestroyView() {
        homeViewState = homeItem?.savedState ?: homeViewState
        homeItem = null
        abortSearchMode()
        searchMenuItem = null
        adapter = null
        super.onDestroyView()
        recyclerListView?.adapter = null
        recyclerListView = null
        rootFrameLayout = null
    }

    /* --------------------------------------------------------------- menu */

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        // Home hosts its own leading search icon in the in-content header.
        if (isHome()) return
        inflater.inflate(R.menu.main_settings_toolbar, menu)
        searchMenuItem = menu.findItem(R.id.menu_item_action_search)
        searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (item.itemId == R.id.menu_item_action_search) exitSearchMode()
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_item_action_search) {
            searchMenuItem = item
            enterSearchMode(item.actionView as SearchView)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val HOME_SECTION = "qself.settings.section"
        private const val SHOW_CATALOG = "qself.settings.catalog"
        const val TARGET_FRAGMENT_LOCATION = "SettingsMainFragment.TARGET_FRAGMENT_LOCATION"
        const val TARGET_UI_AGENT_IDENTIFIER = "SettingsMainFragment.TARGET_UI_AGENT_IDENTIFIER"

        @JvmStatic
        @JvmOverloads
        fun newInstance(location: Array<String>, targetUiAgentId: String? = null): SettingsMainFragment {
            val fragment = SettingsMainFragment()
            fragment.arguments = getBundleForLocation(location, targetUiAgentId)
            return fragment
        }

        @JvmStatic
        @JvmOverloads
        fun getBundleForLocation(location: Array<String>, targetUiAgentId: String? = null): Bundle {
            val desc = FunctionEntryRouter.findDescriptionByLocation(location)
                ?: throw IllegalArgumentException("unable to find fragment description by location: " + location.contentToString())
            if (desc !is FragmentDescription) {
                throw IllegalArgumentException("fragment description is not FragmentDescription, got: " + desc.javaClass.name)
            }
            val bundle = Bundle()
            bundle.putStringArray(TARGET_FRAGMENT_LOCATION, location)
            targetUiAgentId?.let { bundle.putString(TARGET_UI_AGENT_IDENTIFIER, it) }
            return bundle
        }
    }
}
