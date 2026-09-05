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
package sumicya.qself.hostapi

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of host capability states, the seam where the
 * future ports-and-adapters boundary reports version coupling outcomes
 * (see docs/refactoring/01-architecture-analysis.md §3).
 *
 * Design principles:
 * - **Pure JVM**: no Android types, so the degradation policy is unit-testable.
 * - **Fail-safe degradation over crash**: adapters report [CapabilityState.ABSENT]
 *   instead of letting resolution exceptions escape; features consult the
 *   registry and self-disable, upgrading the per-hook exception fence
 *   (BaseFunctionHook) into capability-level degradation.
 * - **Monotonic absence**: within one process lifetime a capability reported
 *   ABSENT stays ABSENT. Rationale: the host class set is immutable after
 *   startup, so a "recovery" would indicate a probe race, and re-resolution
 *   flapping costs more than the feature is worth.
 * - **Recoverable degradation**: AVAILABLE <-> DEGRADED transitions are
 *   allowed because fallback paths may themselves recover.
 * - **Diagnostics**: the last cause for a non-AVAILABLE state is retained for
 *   the future diagnostics aggregation UI; reporters are encouraged to log
 *   at the call site as well.
 *
 * This class is thread-safe.
 */
object CapabilityRegistry {

    private val states = ConcurrentHashMap<String, CapabilityState>(16)
    private val causes = ConcurrentHashMap<String, Throwable>(16)

    /** Current state of [key], or [CapabilityState.UNKNOWN] if never reported. */
    @JvmStatic
    fun stateOf(key: String): CapabilityState = states[key] ?: CapabilityState.UNKNOWN

    /** Last cause attached to a non-AVAILABLE report, if any. */
    @JvmStatic
    fun causeOf(key: String): Throwable? = causes[key]

    /**
     * Report a new state for [key] and return the effective state after
     * applying the policy described in the class doc.
     *
     * @param key   stable capability identifier, must be non-blank;
     *              convention: `"<domain>.<capability>"`, e.g. `"chat.send_msg"`
     * @param state the freshly probed state
     * @param cause optional cause for DEGRADED/ABSENT reports
     * @throws IllegalArgumentException if [key] is blank
     */
    @JvmStatic
    @JvmOverloads
    fun report(key: String, state: CapabilityState, cause: Throwable? = null): CapabilityState {
        require(key.isNotBlank()) { "capability key must not be blank" }
        if (state == CapabilityState.ABSENT) {
            // terminal: first reporter wins, later probes cannot resurrect
            states.putIfAbsent(key, CapabilityState.ABSENT)
            if (cause != null) {
                causes.putIfAbsent(key, cause)
            }
            return CapabilityState.ABSENT
        }
        // AVAILABLE / DEGRADED: ignored once ABSENT, otherwise stored
        val effective = states.compute(key) { _, current ->
            if (current == CapabilityState.ABSENT) CapabilityState.ABSENT else state
        }!!
        when (effective) {
            CapabilityState.AVAILABLE -> causes.remove(key)
            CapabilityState.DEGRADED -> if (cause != null) causes[key] = cause
            else -> {}
        }
        return effective
    }

    /** Whether a capability may be used right now (AVAILABLE or DEGRADED). */
    @JvmStatic
    fun isUsable(key: String): Boolean = when (stateOf(key)) {
        CapabilityState.AVAILABLE, CapabilityState.DEGRADED -> true
        else -> false
    }

    /** Test only: drop all state. */
    @JvmStatic
    fun resetForTest() {
        states.clear()
        causes.clear()
    }
}
