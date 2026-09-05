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

package io.github.qauxv.hook

import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.base.IUiItemAgent

/**
 * Interface for hooks that provide UI representation.
 * Separates UI concerns from hook logic following Single Responsibility Principle.
 */
interface HookUiProvider {
    /**
     * Get the UI item agent for this hook.
     * @return UI item agent or null if no UI representation
     */
    val uiItemAgent: IUiItemAgent?
    
    /**
     * Get the location path for this hook in UI hierarchy.
     * @return Array of location strings representing the path
     */
    val uiItemLocation: Array<String>
    
    /**
     * Get unique identifier for this UI provider.
     * @return Unique identifier string
     */
    val itemAgentProviderUniqueIdentifier: String get() = javaClass.name
}

/**
 * Extension interface for hooks that need both hook logic and UI representation.
 * Combines FunctionHook with HookUiProvider.
 */
abstract class UiEnabledFunctionHook(
    hookKey: String? = null,
    defaultEnabled: Boolean = false,
    targets: Array<io.github.qauxv.util.dexkit.DexKitTarget>? = null
) : FunctionHook(hookKey, defaultEnabled, targets), HookUiProvider {
    
    // Default implementation returns null - override to provide UI
    override val uiItemAgent: IUiItemAgent? get() = null
    
    // Default empty location - override to specify UI location
    override val uiItemLocation: Array<String> get() = emptyArray()
}
