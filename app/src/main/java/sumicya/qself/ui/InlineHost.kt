/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.widget.LinearLayout

/**
 * A settings row that can host an inline panel underneath itself.
 *
 * The host mounts the panel into [inlineContent]; a re-tap collapses it back.
 * Both the upstream DSL row ([io.github.qauxv.dsl.cell.TitleValueCell]) and the
 * Material 3 rows in this package implement it, so the panel host never has to
 * care which kind of row it is dealing with.
 */
interface InlineHost {

    /** Space directly below the row's header, where a panel is mounted. */
    val inlineContent: LinearLayout
}
