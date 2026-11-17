#!/usr/bin/env bash
# check_overlay.sh -- checks windows/runtime/ against its register, UPSTREAM.tsv.
#
#   - every overlay file is registered, and every registered file exists;
#   - a replaced file's recorded blob is still upstream's runtime/<path>
#     (if not, upstream changed it: merge the change into our copy, record the
#     new blob with --update <path>);
#   - a "new" file does not exist upstream (if it does, upstream added it too);
#   - with UPSTREAM_REF set (e.g. upstream/main), runtime/ is unchanged since
#     the merge base with it: our changes belong in the overlay.
#
#   windows/scripts/check_overlay.sh [--update <path under runtime/>]

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
REG=windows/runtime/UPSTREAM.tsv

if [[ "${1:-}" == --update ]]; then
    path="$2"
    blob="$(git rev-parse "HEAD:runtime/$path")"
    awk -F'\t' -v p="$path" -v b="$blob" 'BEGIN{OFS="\t"} $1==p{$2=b} {print}' "$REG" > "$REG.tmp"
    mv "$REG.tmp" "$REG"
    echo "recorded $path at $blob"
    exit 0
fi

fail=0
err() { echo "check_overlay: $*" >&2; fail=1; }

declare -A reg
while IFS=$'\t' read -r path blob; do
    [[ -z "$path" || "$path" == \#* ]] && continue
    reg["$path"]="$blob"
    [[ -f "windows/runtime/$path" ]] || err "registered but missing: windows/runtime/$path"
    upstream="$(git rev-parse -q --verify "HEAD:runtime/$path" 2>/dev/null || true)"
    if [[ "$blob" == new ]]; then
        [[ -z "$upstream" ]] || err "upstream now has runtime/$path too: merge it, then register its blob"
    elif [[ -z "$upstream" ]]; then
        err "upstream removed runtime/$path: drop our copy or register it as new"
    elif [[ "$upstream" != "$blob" ]]; then
        err "upstream changed runtime/$path: merge into our copy, then --update $path"
    fi
done < "$REG"

while IFS= read -r f; do
    rel="${f#windows/runtime/}"
    [[ "$rel" == UPSTREAM.tsv ]] && continue
    [[ -n "${reg[$rel]:-}" ]] || err "not registered: $f"
done < <(git ls-files windows/runtime)

if [[ -n "${UPSTREAM_REF:-}" ]]; then
    base="$(git merge-base HEAD "$UPSTREAM_REF")"
    changed="$(git diff --name-only "$base" HEAD -- runtime)"
    [[ -z "$changed" ]] || err "runtime/ differs from upstream; move these into windows/runtime/:"$'\n'"$changed"
fi

[[ $fail -eq 0 ]] && echo "check_overlay: ${#reg[@]} files OK"
exit $fail
