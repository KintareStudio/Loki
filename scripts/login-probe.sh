#!/bin/sh
#
# The login packet of every pre-1.7 version, taken off the wire from the real client.
#
# The marker goes into a field the client sends and nobody reads, and which field that is depends on
# the shape of the packet — which changed six times below 1.7. The shapes were read out of the jars
# by legacy-protocol-survey.sh, but a shape is only half of it: the filter has to decide, at run
# time and from the bytes alone, which of the six it is looking at. The protocol version is first in
# the packet and is exactly that discriminator, so this records it per version from the client
# itself rather than from a table someone else typed up.
#
# Usage:  scripts/login-probe.sh [version ...]
# Writes build/legacy-protocol/login.txt: version, protocol, and the bytes after the username.
#
set -e

root=$(cd "$(dirname "$0")/.." && pwd)
. "$root/scripts/lib-server.sh"

out="$root/build/legacy-protocol/login.txt"
mkdir -p "$(dirname "$out")"

jdk=${LOKI_JDK8:-}
[ -n "$jdk" ] || { echo "set LOKI_JDK8" >&2; exit 2; }

versions="$*"
[ -n "$versions" ] || versions="a1.0.15 a1.0.16 a1.0.17_04 a1.1.2_01 a1.2.0 a1.2.6 b1.1_02 b1.2_02 b1.3_01 b1.4_01 b1.5_01 b1.6.6 b1.7.3 b1.8.1 1.0 1.1 1.2.1 1.2.5 1.3.2 1.4.7 1.5.2 1.6.4"

echo "# login packet per version, read off the wire from the real client" > "$out"
echo "# version  protocol  bytes-after-the-username" >> "$out"

port=${LOKI_PROBE_PORT:-25720}
for version in $versions; do
    echo "== $version =="
    log="$root/build/legacy-protocol/$version-probe.log"

    "$jdk/bin/java" -cp "$(topath "$root/build/test-classes")" LoginProbe "$port" > "$log" 2>&1 &
    probe_pid=$!

    waited=0
    while [ $waited -lt 15 ]; do
        grep -q '^PORT=' "$log" 2>/dev/null && break
        sleep 1
        waited=$((waited + 1))
    done

    # Without Loki on purpose: what is wanted here is the packet the game writes, not the one the
    # filter would have left behind.
    unset LOKI_AGENT_ARG
    seconds=${LOKI_CLIENT_SECONDS:-20}
    LOKI_CLIENT_SECONDS=$seconds \
        sh "$root/scripts/legacy-client.sh" "$version" 127.0.0.1 "$port" Probe 0 \
        > "$root/build/legacy-protocol/$version-client.log" 2>&1 &
    client_pid=$!

    # Killed from out here rather than trusted to stop on its own: a client of this age that fails
    # to join leaves its window thread running, and the packet this wants was already sent long
    # before that. One version refusing to exit must not be the end of the sweep.
    waited=0
    limit=$((seconds + 60))
    while [ $waited -lt $limit ] && kill -0 $client_pid 2>/dev/null; do
        sleep 2
        waited=$((waited + 2))
    done
    # The JVM is the child of that shell, and killing the shell alone leaves it behind holding the
    # port the next version wants.
    for child in $(ps | awk -v parent="$client_pid" '$2 == parent { print $1 }'); do
        kill -9 "$child" 2>/dev/null || true
    done
    kill -9 $client_pid 2>/dev/null || true
    wait $client_pid 2>/dev/null || true

    # Bounded: the client has already exited by here, so either the probe has its packet or no
    # client ever reached it, and neither is worth holding up the versions that come after.
    waited=0
    while [ $waited -lt 10 ] && kill -0 $probe_pid 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done
    kill $probe_pid 2>/dev/null || true
    wait $probe_pid 2>/dev/null || true

    protocol=$(grep '^PROTOCOL=' "$log" | cut -d= -f2- || true)
    after=$(grep '^AFTERNAME=' "$log" | cut -d= -f2- || true)
    login=$(grep '^LOGIN=' "$log" | cut -d= -f2- || true)
    if [ -z "$login" ]; then
        echo "$version  -  no login packet reached the probe" >> "$out"
        echo "  no login packet; see $log"
    else
        echo "$version  ${protocol:--}  ${after:--}" >> "$out"
        echo "  protocol ${protocol:--}, after the username: ${after:--}"
    fi

    port=$((port + 1))
done

echo
echo "wrote $out"
