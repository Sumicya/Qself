/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.hostapi

/**
 * Port: stable queries about the host environment (RFC-02 §6.2 finally
 * cashed in). Born pull-based with exactly one member — the first feature
 * that needed it (GagInfoDisclosure) asked only "is this the NT kernel?".
 * Do not speculatively extend; grow it when a migrated feature asks.
 */
interface HostEnvironmentApi {

    /** True when the host runs the NT kernel (QQNT base activity present). */
    fun isNtKernel(): Boolean
}
