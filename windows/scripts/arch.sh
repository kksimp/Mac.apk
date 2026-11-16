# arch.sh -- sourced by the scripts that check a program is ARM64.
#
# winapk_pe_arch <file.exe> prints "x64" or "arm64", the program's machine type (uname may be an emulated
# shell's).

winapk_pe_arch() {
    local f="$1" pe m
    pe=$(od -An -tu4 -j60 -N4 "$f" | tr -d ' ')
    m=$(od -An -tx2 -j$((pe + 4)) -N2 "$f" | tr -d ' ')
    case "$m" in
        aa64) echo arm64 ;;
        8664) echo x64 ;;
        *) echo "[arch] $f: unknown machine type $m" >&2; return 1 ;;
    esac
}
