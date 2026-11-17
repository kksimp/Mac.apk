// elf_tls.h -- dynamic ELF TLS for guest libraries.
//
// Android libraries built for API 29 and later may use ELF TLS (`thread_local`,
// `__thread`) instead of emulated TLS. A shared library reaches its TLS through
// the general-dynamic model: the compiler emits `__tls_get_addr(&index)`, where
// `index` is a GOT pair the loader fills from R_AARCH64_TLS_DTPMOD64 (the module id)
// and R_AARCH64_TLS_DTPREL64 (the offset inside the module's TLS block).
//
// Each library with a PT_TLS segment registers here and gets a module id. A
// thread's block for a module is allocated on its first access, from the
// module's initialization image (.tdata, then zeroed .tbss), and freed when
// the thread exits (an FLS callback, so it also covers threads the guest did
// not create through pthread_create) or when the module is unregistered
// (dlclose): every thread's block for it is freed then, so a module id can be
// handed out again once its library is gone.
//
// C++ thread_local destructors (Bionic's __cxa_thread_atexit_impl) are kept
// here too, next to the blocks their objects live in: a thread runs them,
// newest first, before its blocks are freed. Each registration tells the
// linker (tls_set_dso_hooks) which library it belongs to, so that library is
// not unmapped while a thread still has one of its destructors to run.
//
// The static models (initial-exec and local-exec, R_AARCH64_TLS_TPREL64) read
// TPIDR_EL0 and are handled by the ELF loader (elf_loader.h), not here.

#pragma once

#include <cstddef>
#include <cstdint>

#include "winapk/guest_abi.h"

namespace winapk {

// The GOT pair `__tls_get_addr` receives.
struct TlsIndex {
    uint64_t module;
    uint64_t offset;
};

// Registers a module's TLS template. `image` must stay valid (and hold the
// relocated .tdata) for as long as the module is loaded. Returns its id
// (>= 1), or 0 when every id is in use.
size_t tls_register_module(const void* image, size_t filesz, size_t memsz, size_t align);
// Frees every thread's block for `id` and makes the id available again. A
// thread still running the module's code is the app's bug, as on Android.
void tls_unregister_module(size_t id);

// The guest's `__tls_get_addr`.
WINAPK_GUEST_ABI void* tls_get_addr(const TlsIndex* index);

// __cxa_thread_atexit_impl: `fn` (guest ABI, one argument) runs with `obj` when
// the calling thread exits; `dso` is an address inside the library the
// destructor belongs to (its __dso_handle).
void tls_thread_atexit(void* fn, void* obj, void* dso);
// Runs the calling thread's registered destructors now (pthread_exit does
// this before the key destructors; the FLS callback catches the rest).
void tls_run_thread_dtors();
// Told of every registration and of every destructor that has run, with the
// `dso` it was registered with. The linker keeps the library loaded between
// the two. Either may be null.
using TlsDsoHook = void (*)(const void* dso);
void tls_set_dso_hooks(TlsDsoHook added, TlsDsoHook removed);

}  // namespace winapk
