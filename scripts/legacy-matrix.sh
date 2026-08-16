#!/bin/sh
#
# The pre-1.7 announcement, per version, with the real client and the real server.
#
# Both ends are Mojang's own binaries and the login is a real authenticated one: the client is
# handed a token, its joinserver.jsp becomes a modern join, the server's checkserver.jsp becomes a
# hasJoined, and the API server on the other end of both checks the token rather than agreeing with
# whatever it is sent.
#
# Two runs per version, because the interesting failure is not "the declaration did not arrive", it
# is "the game stopped working":
#
#   vanilla client  ->  Loki server   the player must still get in, and be told nothing
#   Loki client     ->  Loki server   the player must get in, and be told where profiles live
#
# EULA: this writes eula=true into the server directories it creates, accepting
# https://aka.ms/MinecraftEULA for them.
#
# Usage:  scripts/legacy-matrix.sh [version ...]
#
set -e

loki_root=$(cd "$(dirname "$0")/.." && pwd)
. "$loki_root/scripts/lib-server.sh"

versions="$*"
[ -n "$versions" ] || versions="b1.5_01 b1.6.6 b1.7.3 b1.8.1 1.0 1.1"

work="$loki_root/build/legacy-matrix"
mkdir -p "$work"

say() { echo "  $*"; }
ok() { echo "  ok   $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
failures=0

loki_agent || exit 1
jdk=${LOKI_JDK8:-}
[ -n "$jdk" ] || { echo "set LOKI_JDK8" >&2; exit 2; }

# ----------------------------------------------------------------- the API server
stub_log="$work/stub.log"
rm -f "$stub_log"
"$jdk/bin/java" -cp "$(topath "$loki_agent_jar")$cp_sep$(topath "$loki_root/build/test-classes")" \
    StubYggdrasil A > "$stub_log" 2>&1 &
stub_pid=$!
trap 'kill $stub_pid 2>/dev/null || true; loki_stop_server' EXIT INT TERM

waited=0
while [ $waited -lt 30 ]; do
    grep -q '^ROOT=' "$stub_log" 2>/dev/null && break
    sleep 1
    waited=$((waited + 1))
done
api=$(grep '^ROOT=' "$stub_log" | cut -d= -f2-)
token=$(grep '^TOKEN=' "$stub_log" | cut -d= -f2-)
uuid=$(grep '^UUID=' "$stub_log" | cut -d= -f2-)
name=$(grep '^NAME=' "$stub_log" | cut -d= -f2-)
[ -n "$api" ] || { echo "the API server never came up, see $stub_log" >&2; exit 1; }
say "API server: $api  (player $name)"

# ----------------------------------------------------------------- per version
port=${LOKI_TEST_PORT:-25650}
for version in $versions; do
    echo
    echo "== $version =="
    dir="$work/$version"
    mkdir -p "$dir"

    loki_needs_java=8
    loki_java_home=$jdk
    loki_java_bin="$jdk/bin/java"
    loki_fetch_server "$version" "$dir" || { fail "no server for $version"; continue; }

    echo "eula=true" > "$dir/eula.txt"

    # A server per mode, because the two ask different things of the client. Online mode is the
    # point of the Loki run — it is what makes the client authenticate, and what makes the token
    # real — but a client without Loki cannot pass it: its joinserver.jsp goes to Mojang, which has
    # never heard of this token. Asking it to would be testing that Mojang is not our API server.
    for mode in vanilla loki; do
        marker="$dir/$mode-client.log"
        if [ "$mode" = loki ]; then
            LOKI_AGENT_ARG="$api"; export LOKI_AGENT_ARG
            online=true
        else
            unset LOKI_AGENT_ARG
            online=false
        fi

        printf 'server-port=%s\nonline-mode=%s\nlevel-name=w\nmax-players=4\n' "$port" "$online" \
            > "$dir/server.properties"
        loki_start_server "$dir" server "-javaagent:$(topath "$loki_agent_jar")=$api" \
            || { fail "$version server did not start"; continue; }

        LOKI_CLIENT_SECONDS=${LOKI_CLIENT_SECONDS:-25} \
            sh "$loki_root/scripts/legacy-client.sh" "$version" 127.0.0.1 "$port" \
            "$name" "token:$token:$uuid" > "$marker" 2>&1 || true

        if grep -q "logged in with entity id" "$dir/server.log"; then
            ok "$mode client got into the game"
        else
            fail "$mode client never logged in, see $marker and $dir/server.log"
        fi

        if [ "$mode" = loki ]; then
            if grep -q "Server declared where profiles come from" "$marker"; then
                ok "and was told where profiles come from"
            else
                fail "no declaration reached the client, see $marker"
            fi
        else
            if grep -q "loki" "$marker"; then
                fail "a vanilla client saw something of Loki's, see $marker"
            else
                ok "and a vanilla one was told nothing"
            fi
        fi

        loki_stop_server
    done

    port=$((port + 1))
done

echo
if [ $failures -eq 0 ]; then
    echo "legacy-matrix: PASSED"
else
    echo "legacy-matrix: $failures FAILED"
fi
exit $failures
