/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.hostapi.chat

/**
 * Port: troop gag (禁言) notices as **domain events** — the terminal form
 * of the pilot series (RFC-03 §10). The adapter owns every volatile detail
 * (which method to hook per generation, raw byte-array offsets, signed
 * uin fixups, legacy push-parameter extraction) and emits semantic events;
 * features only decide wording and what to insert into the chat.
 */
interface GagNoticeApi {

    /** Normalized gag event; features pattern-match on the two shapes. */
    sealed interface GagEvent

    /** 全员禁言开关（opUin 操作者）。 */
    class AllGag(
        val troopUin: String,
        val opUin: String,
        val enabled: Boolean,
    ) : GagEvent

    /** 单成员禁言/解禁（seconds == 0 表示解禁）。 */
    class MemberGag(
        val troopUin: String,
        val opUin: String,
        val victimUin: String,
        val seconds: Long,
    ) : GagEvent

    /**
     * Resolve the host notice source for the running generation and start
     * delivering normalized events to [onEvent] (only while [isEnabled]
     * holds; interception errors go to [onError]).
     *
     * @return false when the host capability is absent — nothing installed
     */
    fun installGagNotice(
        classLoader: ClassLoader,
        onEvent: (GagEvent) -> Unit,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
