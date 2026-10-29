# Native executable helper layout

A guest library scanner can enumerate every directory inside an ABI library directory. Keeping raw executable backups in `<libroot>/<abi>/.macapk-elfexec` adds an unexpected subtree and can make such scanners recurse over backups.

Backups now live outside ABI directories at `<libroot>/.macapk-elfexec/<abi>`. Reconciliation migrates the old layout and refreshes the executable helper; the helper recognizes both layouts. Disabling `MACAPK_ELFEXEC` restores the original ELF bytes.

`debug/check_native_helper_layout.sh` uses disposable ELF fixtures and the production reconciliation code. It verifies no extra ABI subdirectory, repeat-install idempotence, old-layout migration and byte-exact restoration. It does not execute a downloaded guest application.
