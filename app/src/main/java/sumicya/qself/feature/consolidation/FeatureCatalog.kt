/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.consolidation

import io.github.qauxv.gen.getFeatureCatalogRows

/** One model feeds registration, home groups, search and deep links. No feature configuration is written here. */
object FeatureCatalog {
    data class Section(val id: String, val title: String, val features: List<String>)
    data class Group(val id: String, val home: String, val title: String, val sections: List<Section>) {
        val path: Array<String> get() = arrayOf(if (id == "cfg-theme") "module-config" else "features", id)
    }

    @JvmStatic
    fun parse(rows: Array<String>): List<Group> {
        val fields = rows.map { it.split('\t') }
        require(fields.all { it.size == 6 && it[5].isNotBlank() }) { "Malformed catalog row" }
        require(fields.map { it[5] }.distinct().size == fields.size) { "Duplicate capability" }
        return fields.groupBy { it[0] }.map { (id, group) ->
            require(group.all { it[1] == group[0][1] && it[2] == group[0][2] })
            Group(id, group[0][1], group[0][2], group.groupBy { it[3] }.map { (section, members) ->
                require(members.all { it[4] == members[0][4] })
                Section("$id-$section", members[0][4], members.map { it[5] })
            })
        }
    }

    @JvmStatic val groups: List<Group> by lazy { parse(getFeatureCatalogRows()) }
    private val locations: Map<String, Array<String>> by lazy {
        buildMap {
            for (group in groups) for (section in group.sections) for (feature in section.features) {
                put(feature, arrayOf(*group.path, section.id))
            }
        }
    }
    @JvmStatic fun locationFor(feature: String): Array<String>? = locations[feature]?.clone()
    @JvmStatic fun groupPath(id: String): Array<String>? = groups.firstOrNull { it.id == id }?.path
    @JvmStatic fun groupsForHome(home: String): List<Group> = groups.filter { it.home == home }
    @JvmStatic fun featuresForHome(home: String): List<String> = groupsForHome(home).flatMap { it.sections }.flatMap { it.features }
}
