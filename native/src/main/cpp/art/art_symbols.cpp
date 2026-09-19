/*
 * Qself — libart.so symbol resolver (see art_symbols.h).
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "art_symbols.h"

#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

namespace qself::art {
namespace {

using SymbolMap = std::unordered_map<std::string_view, uintptr_t>;

struct Image {
    const uint8_t* data = nullptr;
    size_t size = 0;
    uintptr_t bias = 0;
    size_t dynsym = 0;
    size_t symtab = 0;
    SymbolMap symbols;
    std::string status = "not initialised";
    bool attempted = false;
};

Image& image() {
    static Image instance;
    return instance;
}

std::once_flag g_load;

struct ModuleSearch {
    const char* needle;
    const char* path = nullptr;
    uintptr_t base = 0;
    bool found = false;
};

int FindModule(dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<ModuleSearch*>(data);
    if (info == nullptr || info->dlpi_name == nullptr) {
        return 0;
    }
    if (std::strstr(info->dlpi_name, search->needle) == nullptr) {
        return 0;
    }
    search->path = info->dlpi_name;
    search->base = static_cast<uintptr_t>(info->dlpi_addr);
    search->found = true;
    return 1;  // stop iterating
}

/*
 * Copies [size] bytes at [offset] out of the image. Every header access goes
 * through this, so the parser cannot read past the mapped file.
 */
template <typename T>
bool ReadAt(const uint8_t* data, size_t size, uint64_t offset, T* out) {
    if (offset > size || size - offset < sizeof(T)) {
        return false;
    }
    std::memcpy(out, data + offset, sizeof(T));
    return true;
}

/*
 * Indexes every section of the wanted kind. Templates cover ELF32
 * (armeabi-v7a) and ELF64 (arm64-v8a) without duplicating the traversal, and
 * the caller runs this once per kind so that .dynsym wins the name clashes.
 */
template <typename Ehdr, typename Shdr, typename Sym>
void IndexSymbols(const uint8_t* data, size_t size, Image& out, uint32_t wanted) {
    Ehdr header{};
    if (!ReadAt(data, size, 0, &header)) {
        return;
    }
    if (header.e_shoff == 0 || header.e_shnum == 0 || header.e_shentsize == 0) {
        return;
    }
    const uint64_t table_bytes =
        static_cast<uint64_t>(header.e_shnum) * header.e_shentsize;
    if (header.e_shoff > size || table_bytes > size - header.e_shoff) {
        return;
    }

    for (unsigned i = 0; i < header.e_shnum; ++i) {
        Shdr section{};
        const uint64_t section_offset =
            header.e_shoff + static_cast<uint64_t>(i) * header.e_shentsize;
        if (!ReadAt(data, size, section_offset, &section)) {
            break;
        }
        if (section.sh_type != wanted) {
            continue;
        }
        if (section.sh_entsize == 0 || section.sh_link >= header.e_shnum) {
            continue;
        }
        Shdr strtab{};
        const uint64_t strtab_offset =
            header.e_shoff + static_cast<uint64_t>(section.sh_link) * header.e_shentsize;
        if (!ReadAt(data, size, strtab_offset, &strtab)) {
            continue;
        }
        if (strtab.sh_offset > size || strtab.sh_size > size - strtab.sh_offset) {
            continue;
        }
        if (section.sh_offset > size || section.sh_size > size - section.sh_offset) {
            continue;
        }

        const char* strings = reinterpret_cast<const char*>(data + strtab.sh_offset);
        const size_t strings_size = strtab.sh_size;
        const uint64_t entries = section.sh_size / section.sh_entsize;
        for (uint64_t e = 0; e < entries; ++e) {
            Sym symbol{};
            const uint64_t entry_offset =
                section.sh_offset + e * section.sh_entsize;
            if (!ReadAt(data, size, entry_offset, &symbol)) {
                break;
            }
            if (symbol.st_shndx == SHN_UNDEF || symbol.st_name == 0 ||
                symbol.st_name >= strings_size) {
                continue;
            }
            const char* name = strings + symbol.st_name;
            const size_t remaining = strings_size - symbol.st_name;
            const size_t length = ::strnlen(name, remaining);
            if (length == 0) {
                continue;
            }
            const auto inserted = out.symbols.emplace(
                std::string_view(name, length),
                static_cast<uintptr_t>(out.bias) + static_cast<uintptr_t>(symbol.st_value));
            if (inserted.second) {
                if (wanted == SHT_DYNSYM) {
                    ++out.dynsym;
                } else {
                    ++out.symtab;
                }
            }
        }
    }
}

template <typename Ehdr, typename Shdr, typename Sym>
void IndexImage(const uint8_t* data, size_t size, Image& out) {
    IndexSymbols<Ehdr, Shdr, Sym>(data, size, out, SHT_DYNSYM);
    IndexSymbols<Ehdr, Shdr, Sym>(data, size, out, SHT_SYMTAB);
}

void BuildIndex(Image& out) {
    ModuleSearch search{"libart.so"};
    dl_iterate_phdr(FindModule, &search);
    if (!search.found || search.path == nullptr) {
        out.status = "libart.so is not mapped";
        return;
    }
    out.bias = search.base;

    const int fd = ::open(search.path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        out.status = "cannot open libart.so";
        return;
    }
    struct stat info {};
    if (::fstat(fd, &info) != 0 || info.st_size <= 0) {
        ::close(fd);
        out.status = "cannot stat libart.so";
        return;
    }
    const size_t size = static_cast<size_t>(info.st_size);
    void* mapping = ::mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (mapping == MAP_FAILED) {
        out.status = "cannot map libart.so";
        return;
    }

    const auto* data = static_cast<const uint8_t*>(mapping);
    if (size < EI_NIDENT || data[EI_MAG0] != ELFMAG0 || data[EI_MAG1] != ELFMAG1 ||
        data[EI_MAG2] != ELFMAG2 || data[EI_MAG3] != ELFMAG3) {
        out.status = "libart.so is not ELF";
        ::munmap(mapping, size);
        return;
    }

    out.data = data;
    out.size = size;
    if (data[EI_CLASS] == ELFCLASS64) {
        IndexImage<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym>(data, size, out);
    } else if (data[EI_CLASS] == ELFCLASS32) {
        IndexImage<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym>(data, size, out);
    } else {
        out.status = "unknown ELF class";
        return;
    }

    if (out.symbols.empty()) {
        out.status = "no symbols in libart.so";
        return;
    }

    // A stripped libart.so carries .dynsym only, which still works.
    char buffer[128];
    std::snprintf(buffer, sizeof(buffer), "ok: %zu symbols (dynsym %zu, symtab %zu)",
                  out.symbols.size(), out.dynsym, out.symtab);
    out.status = buffer;
}

Image& EnsureLoaded() {
    Image& out = image();
    std::call_once(g_load, [&out] {
        out.attempted = true;
        BuildIndex(out);
    });
    return out;
}

/** Last resort for symbols the linker does export. */
void* ResolveViaLinker(std::string_view name) {
    static std::once_flag flag;
    static void* handle = nullptr;
    std::call_once(flag, [] {
        handle = ::dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    });
    if (handle == nullptr) {
        return nullptr;
    }
    std::string owned(name);
    return ::dlsym(handle, owned.c_str());
}

}  // namespace

std::size_t SymbolCount() {
    return EnsureLoaded().symbols.size();
}

const char* ResolverStatus() {
    return EnsureLoaded().status.c_str();
}

void* Resolve(std::string_view name) {
    Image& out = EnsureLoaded();
    const auto it = out.symbols.find(name);
    if (it != out.symbols.end()) {
        return reinterpret_cast<void*>(it->second);
    }
    return ResolveViaLinker(name);
}

void* ResolvePrefix(std::string_view prefix) {
    Image& out = EnsureLoaded();
    for (const auto& entry : out.symbols) {
        if (entry.first.size() >= prefix.size() &&
            std::memcmp(entry.first.data(), prefix.data(), prefix.size()) == 0) {
            return reinterpret_cast<void*>(entry.second);
        }
    }
    return nullptr;
}

}  // namespace qself::art
