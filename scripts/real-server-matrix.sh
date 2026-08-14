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
# Each version needs a JDK it can actually run on, and says which in its own metadata, so set
# LOKI_JDK<major> for the ones you have: LOKI_JDK8, LOKI_JDK17, LOKI_JDK21, LOKI_JDK25. The spread
# below currently wants 8, 17, 21 and 25.
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

# Which JDK a version needs is not guessed here: the per-version script reads it out of Mojang's own
# version metadata and looks up LOKI_JDK<major>. All this has to do is notice when one is missing.

# One agent for the whole matrix. It is version independent, so rebuilding per version only buys
# the chance of failing halfway through, which is exactly what it did.
if [ -z "$LOKI_TEST_SKIP_BUILD" ]; then
    sh "$here/build-agent.sh" || exit 1
    LOKI_TEST_SKIP_BUILD=1
    export LOKI_TEST_SKIP_BUILD
    echo
fi

passed=""
failed=""
skipped=""

for version in $versions; do
    status=0
    sh "$here/real-server-test.sh" "$version" || status=$?
    case $status in
        0) passed="$passed $version" ;;
        3) skipped="$skipped $version" ;;  # no JDK configured for what this version needs
        *) failed="$failed $version" ;;
    esac
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
