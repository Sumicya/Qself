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

import io.github.qauxv.base.IDynamicHook
import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.step.DexDeobfStep
import io.github.qauxv.step.Step
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.DexKitFinder
import io.github.qauxv.util.dexkit.DexKitTarget
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Base class for function hooks - separates hook logic from UI representation.
 * This is the core abstraction for all feature hooks.
 * 
 * @param hookKey Unique identifier for this hook
 * @param defaultEnabled Default enabled state
 * @param targets DexKit targets for preparation steps
 */
abstract class FunctionHook(
    private val hookKey: String? = null,
    private val defaultEnabled: Boolean = false,
    private val targets: Array<DexKitTarget>? = null
) : IDynamicHook {

    // Lazy initialization cache for performance
    private var mInitialized = false
    private var mInitializeResult = false
    private val mErrors = CopyOnWriteArrayList<Throwable>()
    
    // Computed properties
    private val actualHookKey: String get() = hookKey ?: this::class.java.simpleName
    private val enableConfigKey: String get() = "$actualHookKey.enabled"
    
    // IDynamicHook implementation
    override val isInitialized: Boolean get() = mInitialized
    
    override val isInitializationSuccessful: Boolean get() = mInitializeResult
    
    override val runtimeErrors: List<Throwable> get() = mErrors.toList()
    
    override val targetProcesses: Int get() = SyncUtils.PROC_MAIN
    
    override val isTargetProcess: Boolean by lazy { SyncUtils.isTargetProcess(targetProcesses) }
    
    override val isAvailable: Boolean get() = true
    
    override val isApplicationRestartRequired: Boolean get() = false
    
    override val isEnabled: Boolean
        get() = enableAllHook() || ConfigManager.getDefaultConfig().getBooleanOrDefault(enableConfigKey, defaultEnabled)
        set(value) {
            ConfigManager.getDefaultConfig().putBoolean(enableConfigKey, value)
        }
    
    override val isPreparationRequired: Boolean
        get() {
            if (this is DexKitFinder && (this as DexKitFinder).isNeedFind) {
                return true
            }
            return targets?.any { DexKit.isRunDexDeobfuscationRequired(it) } ?: false
        }
    
    override fun makePreparationSteps(): Array<Step>? {
        return targets?.map { DexDeobfStep(it) }?.toTypedArray()
    }
    
    /**
     * Initialize the hook. Thread-safe and idempotent.
     * @return true if initialization successful
     */
    final override fun initialize(): Boolean {
        if (mInitialized) {
            return mInitializeResult
        }
        
        synchronized(this) {
            if (mInitialized) {
                return mInitializeResult
            }
            
            mInitializeResult = try {
                initOnce()
            } catch (e: Throwable) {
                traceError(e)
                if (e is Error && e !is AssertionError && e !is LinkageError) {
                    throw e
                }
                false
            }
            
            mInitialized = true
        }
        
        return mInitializeResult
    }
    
    /**
     * Called once during initialization. Implement hook logic here.
     * @return true if initialization successful
     * @throws Exception on failure
     */
    @Throws(Exception::class)
    protected abstract fun initOnce(): Boolean
    
    /**
     * Record an error that occurred during hook execution.
     * Deduplicates errors by message and stack trace.
     */
    fun traceError(e: Throwable) {
        val alreadyLogged = mErrors.any { error ->
            error.message == e.message && error.stackTrace.contentEquals(e.stackTrace)
        }
        
        if (!alreadyLogged) {
            mErrors.add(e)
        }
        Log.e(e)
    }
    
    /**
     * Check if debug mode has "enable all hooks" setting active.
     */
    private fun enableAllHook(): Boolean {
        return false // Simplified for production builds
    }
}
