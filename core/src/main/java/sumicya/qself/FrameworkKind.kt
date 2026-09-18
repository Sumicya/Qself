/*
 * Qself — a free, modern Xposed module for QQ.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

/** The hooking environment the module is currently loaded in. */
enum class FrameworkKind(val displayName: String) {
    XPOSED("Xposed"),
    LSPosed_1X("LSPosed 1.x"),
    LSPosed_10X("LSPosed 10.x"),
    UNKNOWN("unknown"),
}
