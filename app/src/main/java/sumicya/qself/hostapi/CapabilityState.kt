/* SPDX-License-Identifier: GPL-3.0-or-later */
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
