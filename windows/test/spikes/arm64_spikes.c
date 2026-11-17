// What Windows on Arm allows native guest code. Not part of the build; on an ARM64 Windows PC:
//   clang -O1 -fms-extensions -Wno-inline-asm -include string.h arm64_spikes.c -o spikes.exe && spikes.exe [x18|tpidr|exec]
// x18 ends in a crash by design (a fault with a foreign x18).
#include <windows.h>
#include <stdint.h>
#include <stdio.h>

static inline uint64_t rd_x18(void) { uint64_t v; __asm__ volatile("mov %0, x18" : "=r"(v)); return v; }
static inline uint64_t rd_tp(void) { uint64_t v; __asm__ volatile("mrs %0, tpidr_el0" : "=r"(v)); return v; }
static inline void wr_tp(uint64_t v) { __asm__ volatile("msr tpidr_el0, %0" ::"r"(v)); }
static inline uint64_t rd_tpro(void) { uint64_t v; __asm__ volatile("mrs %0, tpidrro_el0" : "=r"(v)); return v; }

// ---- TPIDR_EL0 ----
static volatile LONG g_tpidr_fail;
static DWORD WINAPI tpidr_thread(void* arg) {
    uint64_t mine = 0xA000 + (uint64_t)(uintptr_t)arg;
    uint64_t initial = rd_tp();
    wr_tp(mine);
    for (int i = 0; i < 2000; ++i) {
        Sleep(0);
        if (i % 50 == 0) Sleep(1);
        if (rd_tp() != mine) { InterlockedIncrement(&g_tpidr_fail); break; }
    }
    printf("  thread %llu: initial tpidr_el0=%#llx, kept %#llx\n", (unsigned long long)(uintptr_t)arg,
           (unsigned long long)initial, (unsigned long long)rd_tp());
    return 0;
}
static void test_tpidr(void) {
    printf("TPIDR_EL0\n");
    printf("  main: tpidr_el0=%#llx tpidrro_el0=%#llx x18(TEB)=%#llx NtCurrentTeb=%p\n",
           (unsigned long long)rd_tp(), (unsigned long long)rd_tpro(), (unsigned long long)rd_x18(), (void*)NtCurrentTeb());
    HANDLE h[8];
    for (int i = 0; i < 8; ++i) h[i] = CreateThread(NULL, 0, tpidr_thread, (void*)(uintptr_t)(i + 1), 0, NULL);
    WaitForMultipleObjects(8, h, TRUE, INFINITE);
    // Survives a SEH exception round trip and a Windows call?
    wr_tp(0xBEEF);
    __try { RaiseException(0xE0000001, 0, 0, NULL); } __except (EXCEPTION_EXECUTE_HANDLER) {}
    char buf[64]; GetCurrentDirectoryA(sizeof buf, buf);
    printf("  after RaiseException + API call: tpidr_el0=%#llx\n", (unsigned long long)rd_tp());
    printf("  %s\n", g_tpidr_fail || rd_tp() != 0xBEEF ? "FAIL: value lost" : "OK: user code can own TPIDR_EL0");
}

// ---- x18 ----
static volatile uint64_t g_veh_x18, g_veh_ctx_x18;
static LONG CALLBACK veh(PEXCEPTION_POINTERS e) {
    if (e->ExceptionRecord->ExceptionCode != EXCEPTION_ACCESS_VIOLATION) return EXCEPTION_CONTINUE_SEARCH;
    g_veh_x18 = rd_x18();
    g_veh_ctx_x18 = e->ContextRecord->X18;
    e->ContextRecord->Pc += 4;  // skip the faulting load
    return EXCEPTION_CONTINUE_EXECUTION;
}
static void test_x18(void) {
    printf("x18\n");
    uint64_t teb = rd_x18();
    // (a) pure user code with a foreign x18 for ~1.5 s, across timer interrupts and context switches.
    DWORD t0 = GetTickCount();
    uint64_t sum = 0;
    __asm__ volatile("mov x18, %1\n"
                     "mov %0, #0\n"
                     "1: add %0, %0, #1\n"
                     "   mov x18, %1\n"
                     "   cmp %0, %2\n"
                     "   b.lo 1b\n"
                     : "=&r"(sum)
                     : "r"((uint64_t)0x1122334455667788ull), "r"((uint64_t)400000000ull)
                     : "cc", "x18");
    __asm__ volatile("mov x18, %0" ::"r"(teb) : "x18");
    printf("  (a) 4e8 iterations with x18 foreign: survived, %lu ms\n", (unsigned long)(GetTickCount() - t0));
    // (b) a fault with a foreign x18: what do the handler and the CONTEXT see?
    AddVectoredExceptionHandler(1, veh);
    __asm__ volatile("mov x18, %0\n"
                     "mov x1, #0\n"
                     "ldr x0, [x1]\n"  // faults, VEH skips it
                     "mov x18, %1\n"
                     :
                     : "r"((uint64_t)0xDEADBEEF), "r"(teb)
                     : "x0", "x1", "x18", "memory");
    printf("  (b) fault with x18=0xDEADBEEF: handler's x18=%#llx, CONTEXT.X18=%#llx, TEB=%#llx\n",
           (unsigned long long)g_veh_x18, (unsigned long long)g_veh_ctx_x18, (unsigned long long)teb);
    printf("      x18 restored after continue: %s\n", rd_x18() == teb ? "yes (we restored it)" : "no");
    // (c) a SuspendThread/GetThreadContext on a thread spinning with a foreign x18.
    printf("  done (see lines above)\n");
}

// ---- executable mapping of arm64 code with PAC/BTI hints ----
static void test_exec(void) {
    printf("executable mapping\n");
    uint32_t code[] = {0xd503245f /*bti c*/, 0xd503233f /*paciasp*/, 0x52800540 /*mov w0,#42*/, 0xd50323bf /*autiasp*/,
                       0xd65f03c0 /*ret*/};
    void* p = VirtualAlloc(NULL, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    memcpy(p, code, sizeof code);
    DWORD old;
    if (!VirtualProtect(p, 4096, PAGE_EXECUTE_READ, &old)) { printf("  VirtualProtect failed %lu\n", GetLastError()); return; }
    FlushInstructionCache(GetCurrentProcess(), p, sizeof code);
    int r = ((int (*)(void))p)();
    printf("  bti/paciasp/autiasp function returned %d (%s)\n", r, r == 42 ? "OK" : "FAIL");
    void* q = VirtualAlloc(NULL, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_EXECUTE_READWRITE);
    printf("  RWX VirtualAlloc: %s\n", q ? "allowed" : "refused");
}

int main(int argc, char** argv) {
    const char* which = argc > 1 ? argv[1] : "all";
    setvbuf(stdout, NULL, _IONBF, 0);
    if (!strcmp(which, "all") || !strcmp(which, "exec")) test_exec();
    if (!strcmp(which, "all") || !strcmp(which, "tpidr")) test_tpidr();
    if (!strcmp(which, "all") || !strcmp(which, "x18")) test_x18();
    return 0;
}
