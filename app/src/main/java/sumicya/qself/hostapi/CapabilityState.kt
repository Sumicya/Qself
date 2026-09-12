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

/**
 * Availability state of a host capability (a stable feature of the host app,
 * e.g. "nt kernel msg sending"), as resolved by an adapter.
 *
 * The lifecycle is: [UNKNOWN] (default, not yet probed) then any of
 * [AVAILABLE]/[DEGRADED]/[ABSENT] after the first report.
 */
enum class CapabilityState {

    /** Not probed yet in this process. */
    UNKNOWN,

    /** Resolved and fully functional. */
    AVAILABLE,

    /** Resolved via a fallback path; feature works with reduced fidelity. */
    DEGRADED,

    /**
     * The host does not provide this capability (class/method missing,
     * version too old, ...). Terminal within the process lifetime.
     */
    ABSENT,
}
