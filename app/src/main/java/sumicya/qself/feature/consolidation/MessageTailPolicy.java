/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.consolidation;

/** Shared by the single tail renderer: delivery warnings win, gray tips never acquire a tail. */
public final class MessageTailPolicy {
    public enum Kind { NONE, DETAILS, DELIVERY_WARNING }
    private MessageTailPolicy() {}
    public static Kind resolve(boolean detailsEnabled, boolean warningEnabled, boolean noSeq, boolean grayTip) {
        if (grayTip) return Kind.NONE;
        if (warningEnabled && noSeq) return Kind.DELIVERY_WARNING;
        return detailsEnabled ? Kind.DETAILS : Kind.NONE;
    }
}
