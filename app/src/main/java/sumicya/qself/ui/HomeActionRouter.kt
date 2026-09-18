/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.view.View
import io.github.qauxv.activity.SettingsUiFragmentHostActivity
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.func.IDslFragmentNode
import sumicya.qself.diagnostics.FeatureJournal

/**
 * Every tap on the settings home is one named route. The router resolves the
 * action string to a destination and records the outcome in the feature
 * journal, so a dead destination is never silent: each route either reports
 * success ([Outcome.Opened]) or a dead reason ([Outcome.Dead]).
 *
 * Route table:
 * ```
 * group:<id>        option sheet, that feature group
 * search            the global search overlay
 * <section id>      option sheet, that home section
 * catalog           option sheet, the consolidated catalog
 * diagnostics       the diagnostics agent's own click listener
 * theme             option sheet, group cfg-theme
 * backup / about    the upstream anycast fragment for that id
 * ```
 */
class HomeActionRouter(
    private val activity: () -> SettingsUiFragmentHostActivity,
    private val openSearch: () -> Unit,
    private val listAnchor: () -> View?,
) {

    sealed class Outcome {
        object Opened : Outcome()
        data class Dead(val reason: String) : Outcome()
    }

    fun route(action: String): Outcome {
        FeatureJournal.record("UI", "home.action", "action=$action")
        return when {
            action.startsWith(PREFIX_GROUP) -> openGroup(action.removePrefix(PREFIX_GROUP))
            action == HomeCatalog.SEARCH -> openSearchRoute()
            HomeCatalog.sections.any { it.id == action } -> openSection(action)
            action == HomeCatalog.CATALOG -> openSection(null)
            action == HomeCatalog.DIAGNOSTICS -> openDiagnostics()
            action == HomeCatalog.THEME -> openGroup(GROUP_THEME)
            action == HomeCatalog.BACKUP -> presentAnycast(ID_BACKUP)
            action == HomeCatalog.ABOUT -> presentAnycast(ID_ABOUT)
            else -> dead("dead=unknown action=$action")
        }
    }

    /* -------------------------------------------------------------- routes */

    private fun openGroup(group: String): Outcome {
        SettingsOptionSheet.show(activity(), group = group)
        return Outcome.Opened
    }

    private fun openSearchRoute(): Outcome {
        openSearch()
        return Outcome.Opened
    }

    private fun openSection(home: String?): Outcome {
        SettingsOptionSheet.show(activity(), home = home)
        return Outcome.Opened
    }

    /**
     * The diagnostics entry opens through its own click listener: the sheet
     * route once derived a group id from the anycast location, which is not a
     * catalog group, and the panel opened empty — the row looked dead.
     */
    private fun openDiagnostics(): Outcome {
        val provider = FunctionEntryRouter.queryAnnotatedUiItemAgentEntries()
            .firstOrNull { it.itemAgentProviderUniqueIdentifier == HomeCatalog.DIAGNOSTICS }
        val agent = provider?.uiItemAgent
        val click = agent?.onClickListener
        if (agent == null || click == null) {
            return dead("diagnostics-missing")
        }
        val anchor = listAnchor() ?: return dead("diagnostics-no-anchor")
        click.invoke(agent, activity(), anchor)
        FeatureJournal.record("UI", "home.action", "diagnostics-opened")
        return Outcome.Opened
    }

    private fun presentAnycast(id: String): Outcome {
        val location = FunctionEntryRouter.resolveUiItemAnycastLocation(
            arrayOf(FunctionEntryRouter.Locations.ANY_CAST_PREFIX, id))
            ?: return dead("dead=no-location id=$id")
        val desc = FunctionEntryRouter.findDescriptionByLocation(location) as? IDslFragmentNode
            ?: return dead("dead=no-description id=$id")
        val fragment = desc.getTargetFragmentClass(location).newInstance()
        fragment.arguments = desc.getTargetFragmentArguments(location)
        activity().presentFragment(fragment)
        FeatureJournal.record("UI", "home.action", "fragment-presented id=$id")
        return Outcome.Opened
    }

    /** Records the exact dead-route detail and reports it to the caller. */
    private fun dead(detail: String): Outcome {
        FeatureJournal.record("UI", "home.action", detail)
        val reason = detail.removePrefix("dead=").substringBefore(' ')
        return Outcome.Dead(reason)
    }

    private companion object {
        const val PREFIX_GROUP = "group:"
        const val GROUP_THEME = "cfg-theme"
        const val ID_BACKUP = "cfg-backup-restore"
        const val ID_ABOUT = "other-about"
    }
}
