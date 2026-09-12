/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
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
import android.view.*
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cc.ioctl.dialog.WsaWarningDialog
import io.github.qauxv.util.LayoutHelper.MATCH_PARENT
import io.github.qauxv.R
import io.github.qauxv.config.SafeModeManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.func.*
import io.github.qauxv.dsl.item.*
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.UiThread
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.isInHostProcess
import sumicya.qself.ui.HomeCatalog
import sumicya.qself.ui.SettingsListLayout
import sumicya.qself.ui.SettingsHomeItem
import sumicya.qself.ui.SettingsHomeView
import sumicya.qself.ui.SettingsVisuals
import sumicya.qself.ui.SettingsAppearanceItem
import sumicya.qself.diagnostics.ReportDiagnostics

class SettingsMainFragment : BaseRootLayoutFragment() {

    private lateinit var mFragmentLocations: Array<String>
    private lateinit var mFragmentDescription: FragmentDescription
    private var mTargetUiAgentNavId: String? = null
    private var mTargetUiAgentNavigated: Boolean = false

    // search
    private var mSearchSubFragment: SearchOverlaySubFragment? = null
    private var mSearchRootLayout: ViewGroup? = null
    private var mSearchMenuItem: MenuItem? = null

    // DSL stuff below
    private var adapter: RecyclerView.Adapter<*>? = null
    private var listLayoutManager: LinearLayoutManager? = null
    private var recyclerListView: RecyclerView? = null
    private var rootFrameLayout: FrameLayout? = null

    private lateinit var typeList: Array<Class<*>>
    private lateinit var itemList: ArrayList<TMsgListItem>
    private lateinit var itemTypeIds: Array<Int>
    private lateinit var itemTypeDelegate: Array<TMsgListItem>

    override fun onAttach(context: Context) {
        super.onAttach(context)
        var location: Array<String> = arguments?.getStringArray(TARGET_FRAGMENT_LOCATION)
            ?: throw IllegalArgumentException("target fragment location is null")
        // Migrate a saved search/deep link by capability ID, not by its old category.
        arguments?.getString(TARGET_UI_AGENT_IDENTIFIER)?.let { id ->
            FunctionEntryRouter.locationForFeature(id)?.let { location = it.dropLast(1).toTypedArray() }
        }
        var desc = FunctionEntryRouter.findDescriptionByLocation(location)
        if (desc !is FragmentDescription) {
            // Old category-only saved state: show the consolidated catalog rather than crash.
            location = emptyArray()
            desc = FunctionEntryRouter.settingsUiItemDslTree
            arguments?.putBoolean(SHOW_CATALOG, true)
        }
        mFragmentLocations = location
        val section = HomeCatalog.sections.firstOrNull { it.id == arguments?.getString(HOME_SECTION) }
        mFragmentDescription = if (section != null) {
            FragmentDescription(section.id, section.title, false) {
                for (group in sumicya.qself.feature.consolidation.FeatureCatalog.groupsForHome(section.id)) {
                    FunctionEntryRouter.findDescriptionByLocation(group.path)?.let { addChild(it) }
                }
            }
        } else desc
        title = when {
            section != null -> section.title
            arguments?.getBoolean(SHOW_CATALOG) == true -> "功能与设置"
            else -> mFragmentDescription.name ?: "设置"
        }
        mTargetUiAgentNavId = arguments?.getString(TARGET_UI_AGENT_IDENTIFIER)
    }

    override fun doOnCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = layoutInflater.context
        val rootView = SettingsListLayout(context).apply {
            background = SettingsVisuals.backdrop(SettingsVisuals.palette(context, SettingsAppearanceItem.mode))
        }
        rootFrameLayout = rootView
        val tmsgDslTree = if (isHome()) arrayListOf<DslTMsgListItemInflatable>(SettingsHomeItem(
            { SettingsHomeView.State(
                if (isInHostProcess) "${hostInfo.hostName} ${hostInfo.versionName}" else "模块管理",
                ReportDiagnostics.isEnabled, SafeModeManager.getManager().isEnabledForThisTime
            ) }, { SettingsAppearanceItem.mode }, ::openHomeAction
        ))
            else convertFragmentDslToTMsgDslItemTree(context, mFragmentDescription)
        // inflate DSL tree, the most awful code in the world
        itemList = ArrayList()
        // inflate hierarchy recycler list view items, each item will have its own view holder type
        tmsgDslTree.forEach {
            itemList.addAll(it.inflateTMsgListItems(context))
        }
        // group items by java class
        typeList = itemList.map { it.javaClass }.distinct().toTypedArray()
        // item id to type id mapping
        itemTypeIds = Array(itemList.size) {
            typeList.indexOf(itemList[it].javaClass)
        }
        // item type delegate is used to create view holder
        itemTypeDelegate = Array(typeList.size) {
            itemList[itemTypeIds.indexOf(it)]
        }
        recyclerListView = rootView.recycler
        listLayoutManager = rootView.recycler.layoutManager as LinearLayoutManager
        if (!isHome()) SettingsVisuals.addListSpacing(rootView.recycler)
        // init adapter
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): RecyclerView.ViewHolder {
                val delegate = itemTypeDelegate[viewType]
                val vh = delegate.createViewHolder(context, parent)
                return vh
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = itemList[position]
                item.bindView(holder, position, context)
                if (!item.isVoidBackground) SettingsVisuals.decorateRow(holder.itemView, context, item.isClickable)
            }

            override fun getItemCount() = itemList.size

            override fun getItemViewType(position: Int) = itemTypeIds[position]
        }

        recyclerListView!!.adapter = adapter

        rootLayoutView = recyclerListView
        if (isInHostProcess) {
            WsaWarningDialog.showWsaWarningDialogIfNecessary(requireContext())
        }
        return rootView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        requireActivity().onBackPressedDispatcher.addCallback(this, mSearchModeOnBackPressedCallback)
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
        if (isHome()) viewLifecycleOwner.lifecycleScope.launchWhenResumed {
            ReportDiagnostics.valueState.collect { adapter?.notifyItemChanged(0) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHome()) adapter?.notifyItemChanged(0)
        if (!mTargetUiAgentNavId.isNullOrEmpty() && !mTargetUiAgentNavigated) {
            navigateToTargetUiAgentItem()
        }
        val hostName = hostInfo.hostName
        val safeModeNow = SafeModeManager.getManager().isEnabledForThisTime
        val safeModeNextTime = SafeModeManager.getManager().isEnabledForNextTime
        subtitle = when {
            safeModeNow && safeModeNextTime -> "安全模式（停用所有功能）"
            !safeModeNow && safeModeNextTime -> "安全模式需要重启 $hostName 生效"
            safeModeNow && !safeModeNextTime -> "重新启动 $hostName 后将退出安全模式"
            else -> null
        }
    }

    @UiThread
    private fun navigateToTargetUiAgentItem() {
        if (!isResumed) {
            return
        }
        // wait for the view to be created and animation to finish
        val recycler = recyclerListView ?: return
        SyncUtils.postDelayed(300) {
            if (!isResumed || recyclerListView !== recycler) return@postDelayed
            var index = -1
            // find the UI agent index
            for (i in itemList.indices) {
                val item = itemList[i]
                if (item is UiAgentItem && item.agentProvider.itemAgentProviderUniqueIdentifier == mTargetUiAgentNavId) {
                    index = i
                    break
                }
            }
            if (index >= 0) {
                val layoutManager = recycler.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(index, recycler.paddingTop + SettingsVisuals.dp(requireContext(), 12))
                recycler.postDelayed({
                    if (isResumed && recyclerListView === recycler) {
                        layoutManager.findViewByPosition(index)?.let { itemView ->
                            highlightRect(Rect(itemView.left, itemView.top, itemView.right, itemView.bottom))
                        }
                    }
                }, 100)
                mTargetUiAgentNavigated = true
            }
        }
    }

    @UiThread
    private fun highlightRect(rect: Rect) {
        if (!isResumed) {
            return
        }
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

    override fun onDestroyView() {
        abortSearchMode()
        mSearchMenuItem = null
        adapter = null
        super.onDestroyView()
        recyclerListView?.let {
            it.adapter = null
        }
        recyclerListView = null
        rootFrameLayout = null
    }

    private fun convertFragmentDslToTMsgDslItemTree(context: Context, fragmentDsl: FragmentDescription): ArrayList<DslTMsgListItemInflatable> {
        val resultDslItems: ArrayList<DslTMsgListItemInflatable> = ArrayList()
        for (child: IDslItemNode in fragmentDsl.children) {
            if (child.isEndNode()) {
                val item: DslTMsgListItemInflatable = convertEndNode(context, child)
                resultDslItems.add(item)
            } else {
                val item: DslTMsgListItemInflatable = convertParentNodeRecursive(context, child as IDslParentNode)
                resultDslItems.add(item)
            }
        }
        return resultDslItems
    }

    private fun convertParentNodeRecursive(context: Context, parentNode: IDslParentNode): DslTMsgListItemInflatable {
        if (parentNode is CategoryDescription) {
            return CategoryItem(parentNode.name, null).also {
                // init category item, which should only be end nodes here
                for (item: IDslItemNode in parentNode.children) {
                    val child = convertEndNode(context, item)
                    it.add(child)
                }
            }
        }
        if (parentNode.isEndNode()) {
            return convertEndNode(context, parentNode)
        }
        throw UnsupportedOperationException("unsupported node type: " + parentNode.javaClass.name)
    }

    private fun convertEndNode(context: Context, endNode: IDslItemNode): DslTMsgListItemInflatable {
        if (endNode is UiItemAgentDescription) {
            return UiAgentItem(endNode.identifier, endNode.name, endNode.itemAgentProvider)
        } else if (endNode is IDslFragmentNode) {
            return SimpleListItem(endNode.identifier, endNode.name ?: endNode.toString(), null).apply {
                onClickListener = {
                    val targetLocation = FunctionEntryRouter.resolveUiItemAnycastLocation(
                        arrayOf(
                            FunctionEntryRouter.Locations.ANY_CAST_PREFIX, endNode.identifier
                        )
                    ) ?: throw IllegalStateException("can not resolve anycast location for '${endNode.identifier}'")
                    // jump to target fragment
                    val location: Array<String> = targetLocation
                    val fragmentClass = endNode.getTargetFragmentClass(location)
                    val bundle = endNode.getTargetFragmentArguments(location)
                    val fragment = fragmentClass.newInstance() as BaseSettingFragment
                    fragment.arguments = bundle
                    settingsHostActivity!!.presentFragment(fragment)
                }
            }
        }
        throw UnsupportedOperationException("unsupported node type: " + endNode.javaClass.name)
    }

    private fun IDslItemNode.isEndNode(): Boolean {
        return this !is IDslParentNode || this is IDslFragmentNode
    }

    private fun isRootFragmentDescription(): Boolean {
        return mFragmentLocations.isEmpty() || mFragmentLocations.size == 1 && mFragmentLocations[0].isEmpty()
    }

    private fun isHome(): Boolean = isRootFragmentDescription() &&
        arguments?.getString(HOME_SECTION) == null && arguments?.getBoolean(SHOW_CATALOG) != true &&
        arguments?.getString(TARGET_UI_AGENT_IDENTIFIER) == null

    private fun openHomeAction(action: String) {
        if (action == HomeCatalog.SEARCH) {
            mSearchMenuItem?.let { item ->
                item.expandActionView()
                enterSearchMode(item.actionView as SearchView)
            }
            return
        }
        if (HomeCatalog.sections.any { it.id == action } || action == HomeCatalog.CATALOG) {
            val fragment = newInstance(emptyArray())
            if (action == HomeCatalog.CATALOG) fragment.arguments!!.putBoolean(SHOW_CATALOG, true)
            else fragment.arguments!!.putString(HOME_SECTION, action)
            requireSettingsHostActivity().presentFragment(fragment)
            return
        }
        if (action == HomeCatalog.DIAGNOSTICS) {
            // Open the existing agent's real page and highlight it; never bypass its interaction contract.
            val provider = FunctionEntryRouter.queryAnnotatedUiItemAgentEntries()
                .firstOrNull { it.itemAgentProviderUniqueIdentifier == action } ?: return
            val location = FunctionEntryRouter.locationForProvider(provider).dropLast(1).toTypedArray()
            requireSettingsHostActivity().presentFragment(newInstance(location, action))
            return
        }
        val id = when (action) {
            HomeCatalog.THEME -> "cfg-theme"
            HomeCatalog.BACKUP -> "cfg-backup-restore"
            HomeCatalog.ABOUT -> "other-about"
            else -> return
        }
        val location = FunctionEntryRouter.resolveUiItemAnycastLocation(arrayOf(FunctionEntryRouter.Locations.ANY_CAST_PREFIX, id)) ?: return
        val desc = FunctionEntryRouter.findDescriptionByLocation(location) as? IDslFragmentNode ?: return
        val fragment = desc.getTargetFragmentClass(location).newInstance()
        fragment.arguments = desc.getTargetFragmentArguments(location)
        requireSettingsHostActivity().presentFragment(fragment)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.main_settings_toolbar, menu)
        mSearchMenuItem = menu.findItem(R.id.menu_item_action_search)
        menu.findItem(R.id.menu_item_action_search)?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (item.itemId == R.id.menu_item_action_search) {
                    exitSearchMode()
                }
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_item_action_search) {
            // always use global search, or search in current fragment?
            mSearchMenuItem = item
            val searchView = item.actionView as SearchView
            enterSearchMode(searchView)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private val mSearchModeOnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (mSearchMenuItem?.collapseActionView() != true) exitSearchMode()
        }
    }

    /** Toolbar expansion is native. The overlay itself has no race-prone delayed transitions. */
    private fun enterSearchMode(searchView: SearchView) {
        val recycler = recyclerListView ?: return
        val root = rootFrameLayout ?: return
        if (mSearchSubFragment == null) {
            val fragment = SearchOverlaySubFragment().also {
                it.parent = this
                it.context = requireContext()
                it.settingsHostActivity = requireSettingsHostActivity()
            }
            val overlay = fragment.onCreateView(requireActivity().layoutInflater, root, null) as ViewGroup
            mSearchSubFragment = fragment
            mSearchRootLayout = overlay
            root.addView(overlay)
            recycler.visibility = View.GONE
            rootLayoutView = overlay
            applyRootLayoutPaddingFor(overlay)
            fragment.onResume()
            searchView.setOnCloseListener { exitSearchMode(); true }
        }
        mSearchSubFragment!!.initForSearchView(searchView)
        mSearchModeOnBackPressedCallback.isEnabled = true
    }

    private fun exitSearchMode() = abortSearchMode()

    private fun abortSearchMode() {
        mSearchRootLayout?.let { rootFrameLayout?.removeView(it) }
        mSearchSubFragment?.onDestroyView()
        mSearchRootLayout = null
        mSearchSubFragment = null
        mSearchModeOnBackPressedCallback.isEnabled = false
        recyclerListView?.let { recycler ->
            recycler.visibility = View.VISIBLE
            rootLayoutView = recycler
            applyRootLayoutPaddingFor(recycler)
        }
    }

    fun onNavigateToOtherFragment() {
        abortSearchMode()
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
            val bundle = getBundleForLocation(location, targetUiAgentId)
            fragment.arguments = bundle
            return fragment
        }

        @JvmStatic
        @JvmOverloads
        fun getBundleForLocation(location: Array<String>, targetUiAgentId: String? = null): Bundle {
            // check destination fragment
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
