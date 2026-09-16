/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.profile

import io.github.qauxv.gen.getExcludedFeatureClassNames

/** Generated inventory guards registered features; unregistered implementation components remain dependencies. */
object SimplifiedProfile {
    private val excluded by lazy { getExcludedFeatureClassNames().toHashSet() }

    @JvmStatic fun isAllowed(instance: Any): Boolean = isAllowedClass(instance.javaClass.name)
    @JvmStatic fun isAllowedClass(name: String): Boolean = name.substringBefore('$') !in excluded
    @JvmStatic fun dormantEntryCount(): Int = excluded.size
}
