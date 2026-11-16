// elf_tls.cpp -- see elf_tls.h.

#include "winapk/elf_tls.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

#include <malloc.h>
#include <windows.h>

namespace winapk {

namespace {

// Ids are reused after tls_unregister_module, so the table is bounded by the
// number of libraries with TLS loaded at once, not by how many were ever
// loaded; 1024 is far above what an app has.
constexpr size_t kMaxModules = 1024;

struct Module {
    const void* image = nullptr;
    size_t filesz = 0, memsz = 0, align = 1;
    bool live = false;
};

// Indexed by module id; slot 0 is unused so that a zero DTPMOD64 is invalid.
std::mutex g_lock;
std::vector<Module> g_modules(1);

struct ThreadDtor {
    void* fn;
    void* obj;
    void* dso;
};

// A thread's blocks, indexed by module id. The array is fixed so the owner's
// unlocked fast path and another thread's unregister never resize under each
// other; the slots are atomic for the same reason.
struct ThreadBlocks {
    std::atomic<void*> blocks[kMaxModules];
    std::vector<ThreadDtor> dtors;  // the owning thread's only
    ThreadBlocks() {
        for (auto& b : blocks) b.store(nullptr, std::memory_order_relaxed);
    }
};

// Every live thread's record, so unregister can reach every block.
std::vector<ThreadBlocks*> g_threads;

std::atomic<TlsDsoHook> g_dso_added{nullptr};
std::atomic<TlsDsoHook> g_dso_removed{nullptr};

void run_dtors(ThreadBlocks* t) {
    using Dtor = void(WINAPK_GUEST_ABI*)(void*);
    // A destructor may register another; newest first until none is left.
    while (!t->dtors.empty()) {
        const ThreadDtor d = t->dtors.back();
        t->dtors.pop_back();
        reinterpret_cast<Dtor>(d.fn)(d.obj);
        if (TlsDsoHook removed = g_dso_removed.load()) removed(d.dso);
    }
}

void WINAPI free_thread_blocks(void* p) {
    auto* t = static_cast<ThreadBlocks*>(p);
    if (!t) return;
    run_dtors(t);
    {
        std::lock_guard<std::mutex> hold(g_lock);
        for (size_t i = 0; i < g_threads.size(); ++i) {
            if (g_threads[i] == t) {
                g_threads[i] = g_threads.back();
                g_threads.pop_back();
                break;
            }
        }
        for (auto& b : t->blocks) {
            if (void* block = b.exchange(nullptr)) _aligned_free(block);
        }
    }
    delete t;
}

DWORD fls_slot() {
    static const DWORD slot = FlsAlloc(free_thread_blocks);
    return slot;
}

ThreadBlocks* thread_blocks() {
    const DWORD slot = fls_slot();
    auto* t = static_cast<ThreadBlocks*>(FlsGetValue(slot));
    if (!t) {
        t = new ThreadBlocks();
        FlsSetValue(slot, t);
        std::lock_guard<std::mutex> hold(g_lock);
        g_threads.push_back(t);
    }
    return t;
}

void* allocate_block(size_t id) {
    Module m;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        if (id >= g_modules.size() || !g_modules[id].live) return nullptr;
        m = g_modules[id];
    }
    size_t align = m.align < sizeof(void*) ? sizeof(void*) : m.align;
    void* block = _aligned_malloc(m.memsz ? m.memsz : 1, align);
    if (!block) return nullptr;
    std::memcpy(block, m.image, m.filesz);
    std::memset(static_cast<char*>(block) + m.filesz, 0, m.memsz - m.filesz);
    return block;
}

}  // namespace

size_t tls_register_module(const void* image, size_t filesz, size_t memsz, size_t align) {
    fls_slot();
    std::lock_guard<std::mutex> hold(g_lock);
    Module m;
    m.image = image;
    m.filesz = filesz;
    m.memsz = memsz;
    // p_align is a power of two or 0/1 for "no constraint".
    m.align = align > 1 ? align : 1;
    m.live = true;
    for (size_t id = 1; id < g_modules.size(); ++id) {
        if (!g_modules[id].live) {
            g_modules[id] = m;
            return id;
        }
    }
    if (g_modules.size() >= kMaxModules) return 0;
    g_modules.push_back(m);
    return g_modules.size() - 1;
}

void tls_unregister_module(size_t id) {
    std::lock_guard<std::mutex> hold(g_lock);
    if (id == 0 || id >= g_modules.size()) return;
    g_modules[id] = Module{};
    for (ThreadBlocks* t : g_threads) {
        if (void* block = t->blocks[id].exchange(nullptr)) _aligned_free(block);
    }
}

WINAPK_GUEST_ABI void* tls_get_addr(const TlsIndex* index) {
    ThreadBlocks* t = thread_blocks();
    const size_t id = static_cast<size_t>(index->module);
    void* block = id < kMaxModules ? t->blocks[id].load(std::memory_order_relaxed) : nullptr;
    if (!block && id < kMaxModules) {
        block = allocate_block(id);
        if (block) t->blocks[id].store(block, std::memory_order_relaxed);
    }
    if (!block) {
        // Bionic aborts on a bad module id too; there is no address to return.
        std::fprintf(stderr, "winapk: __tls_get_addr for unknown TLS module %zu\n", id);
        std::abort();
    }
    return static_cast<char*>(block) + index->offset;
}

void tls_thread_atexit(void* fn, void* obj, void* dso) {
    thread_blocks()->dtors.push_back({fn, obj, dso});
    if (TlsDsoHook added = g_dso_added.load()) added(dso);
}

void tls_run_thread_dtors() {
    const DWORD slot = fls_slot();
    if (auto* t = static_cast<ThreadBlocks*>(FlsGetValue(slot))) run_dtors(t);
}

void tls_set_dso_hooks(TlsDsoHook added, TlsDsoHook removed) {
    g_dso_added.store(added);
    g_dso_removed.store(removed);
}

}  // namespace winapk
