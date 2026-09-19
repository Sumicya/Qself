/*
 * Qself — libart.so symbol resolver.
 *
 * LSPlant resolves ART internals by name, and those symbols are not exported
 * through the dynamic linker (Android hides them), so <dlfcn.h> alone is not
 * enough: the file has to be parsed for both .dynsym and .symtab.
 *
 * The mapping is read-only and lives for the whole process, so the index can
 * store std::string_view keys pointing straight into it.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef QSELF_ART_SYMBOLS_H
#define QSELF_ART_SYMBOLS_H

#include <cstddef>
#include <string_view>

namespace qself::art {

/** Number of indexed libart.so symbols; 0 until the first lookup. */
std::size_t SymbolCount();

/** Human-readable state, e.g. "ok: 58421 symbols (dynsym 1203, symtab 57218)". */
const char* ResolverStatus();

/** Exact symbol lookup (dynsym first, then symtab). Null when absent. */
void* Resolve(std::string_view name);

/** First symbol starting with [prefix], or null. */
void* ResolvePrefix(std::string_view prefix);

}  // namespace qself::art

#endif  // QSELF_ART_SYMBOLS_H
