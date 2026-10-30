# Opt-in guest executable-page writes

`MACAPK_GUEST_RWX=1` supports guest mprotect requests aligned to Android's 4 KiB pages on a 16 KiB macOS host. The default remains disabled. Only known guest executable image ranges participate.

Pages remain RX during normal execution. Supported AArch64 ordinary stores are decoded at a write fault, applied in a serialized RW window, followed by an instruction-cache flush and RX restoration. Unknown and atomic/exclusive stores fail instead of reporting a successful write. Code executing on the same host page is handled by store emulation rather than temporarily making the page non-executable under the faulting PC.

`debug/check_guest_code_writes.sh` exercises the production decoder and signal path on Apple Silicon: 4 KiB requests, writes to same-page executable code, concurrent readers/protection updates, cache visibility, RX restoration, READ/RW/NONE revocation and partial-range preservation. Permission downgrades revoke write grants, preserving unaffected host-page interval differences. Range lookup and metadata publication share the write lock so concurrent protection updates do not create transient authorization gaps. No application-specific offsets or archive modifications are used.
