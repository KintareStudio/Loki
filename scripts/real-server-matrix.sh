#!/bin/sh
#
# Runs real-server-test.sh across the eras Loki has to deal with, since what a server does with the
# status response has changed shape several times and each era needs seeing for itself:
#
#   1.7 - 1.18.2   Netty relocated in 1.7.x, plain io.netty afterwards
#   1.19 - 1.19.4  services key info arrives, status unchanged
#   1.20 +         key set fetched, status still unchanged
#   26.3 +         endpoint discovery, snapshots only for now
#
# Each version needs a JDK it can actually run on, so point these at yours:
#
#   LOKI_JDK8   1.7  - 1.16.5
#   LOKI_JDK17  1.17 - 1.20.4
#   LOKI_JDK21  1.20.5 and later
#
# A version whose JDK is not set is skipped with a note rather than failing the run.
#
# Usage:  scripts/real-server-matrix.sh [version ...]
#         with no arguments, runs the default spread below
#
set -e

here=$(cd "$(dirname "$0")" && pwd)

versions="$*"
[ -n "$versions" ] || versions="1.7.10 1.8.9 1.12.2 1.16.5 1.18.2 1.19.4 1.20.6 1.21.1 26.2 26.3-snapshot-8"

jdk_for() {
    case "$1" in
        1.7*|1.8*|1.9*|1.10*|1.11*|1.12*|1.13*|1.14*|1.15*|1.16*) echo "$LOKI_JDK8" ;;
        1.17*|1.18*|1.19*|1.20|1.20.1|1.20.2|1.20.3|1.20.4)       echo "$LOKI_JDK17" ;;
        *)                                                        echo "$LOKI_JDK21" ;;
    esac
}

passed=""
failed=""
skipped=""

for version in $versions; do
    jdk=$(jdk_for "$version")
    if [ -z "$jdk" ]; then
        echo "== $version =="
        echo "  skipped: no JDK configured for this version"
        skipped="$skipped $version"
        echo
        continue
    fi

    if sh "$here/real-server-test.sh" "$version" "$jdk"; then
        passed="$passed $version"
    else
        failed="$failed $version"
    fi
    echo
done

echo "================================"
[ -n "$passed" ] && echo "passed: $passed"
[ -n "$skipped" ] && echo "skipped:$skipped"
if [ -n "$failed" ]; then
    echo "FAILED:$failed"
    exit 1
fi
echo "real-server-matrix: PASSED"
