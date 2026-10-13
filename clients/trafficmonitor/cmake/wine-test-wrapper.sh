#!/bin/bash
set -eu
# Test environment must be isolated from all real provider/account data.
test_prefix="${WINEPREFIX:?Set WINEPREFIX to a dedicated directory under /cache}"
case "$test_prefix" in /cache/*) ;; *) echo "WINEPREFIX must be under /cache" >&2; exit 2 ;; esac
executable="$1"
shift
args=()
for arg in "$@"; do
    if [[ "$arg" == /* ]]; then args+=("Z:${arg//\//\\}"); else args+=("$arg"); fi
done
export WINEARCH=win64
export WINEDEBUG=-all
export WINEDLLOVERRIDES=mscoree,mshtml=d
exec /usr/lib/wine/wine64 "$executable" "${args[@]}"
