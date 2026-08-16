#!/bin/sh
#
# Stands up a real Minecraft server with Loki attached and checks that its Server List Ping
# declares the API server Loki was pointed at.
#
# Everything `ant test` covers is stood up in process; this is the part that cannot be, because it
# needs Mojang's own server jar and therefore the network and Mojang's EULA. What a client does with
# the declaration is the other half, and lives in cross-system-test.sh.
#
# EULA: this script writes `eula=true` into the server directory it creates. Running it is how you
# accept https://aka.ms/MinecraftEULA for these throwaway servers. It says so on every run.
#
# Usage:  scripts/real-server-test.sh <minecraft-version> [java-home]
# e.g.    scripts/real-server-test.sh 1.21.1
#         scripts/real-server-test.sh 1.8.9 "/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot"
#
set -e

version=$1
if [ -z "$version" ]; then
    echo "usage: $0 <minecraft-version> [java-home]" >&2
    exit 2
fi

loki_root=$(cd "$(dirname "$0")/.." && pwd)
. "$loki_root/scripts/lib-server.sh"

work="$loki_root/build/real-server/$version"
port=${LOKI_TEST_PORT:-25599}

# Need not resolve: Loki stores the root before it tries to read anything from it, and a refused
# connection keeps the run quick. Pass LOKI_TEST_API to point at a real one.
api=${LOKI_TEST_API:-http://127.0.0.1:1/authlib-injector}

say() { echo "  $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
ok() { echo "  ok   $*"; }
failures=0

echo "== $version =="
say "EULA: this run writes eula=true into $work, accepting https://aka.ms/MinecraftEULA"

loki_agent || exit 1
say "agent: $(basename "$loki_agent_jar") ($(date -r "$loki_agent_jar" '+%H:%M:%S'))"

loki_fetch_version "$version" "$work" || exit 1
if [ -n "$2" ]; then
    loki_java_home=$2
    loki_java_bin="$loki_java_home/bin/java"
else
    loki_jdk "$loki_needs_java" || exit $?
fi
say "java $loki_needs_java: $loki_java_home"
loki_fetch_server "$version" "$work" || exit 1

# Compiled per version, with that version's JDK, because a shared directory would hand a class file
# built by Java 25 to the Java 8 run that comes after it. Against the shipped jar rather than the
# class directory, so what runs here is what an operator would attach.
mkdir -p "$work/pinger"
"$loki_java_home/bin/javac" -cp "$(topath "$loki_agent_jar")" -d "$(topath "$work/pinger")" \
    "$(topath "$loki_root/src/test/java/ServerPing.java")" \
    "$(topath "$loki_root/src/test/java/NettyRig.java")"
classpath="$(topath "$loki_agent_jar")$cp_sep$(topath "$work/pinger")"

# 1.7 and up only, which is where a status response exists to put a declaration in. Below that the
# declaration travels on the game connection instead, and legacy-matrix.sh is what tests it — with
# a real client on the other end, which is the only way to know it arrived.
if [ -n "$(loki_legacy_server_url "$version")" ]; then
    echo "  $version has no status response to declare in; use scripts/legacy-matrix.sh" >&2
    exit 3
fi
pinger=ServerPing
declared_marker="\"session\":\"$api/sessionserver\""

# ----------------------------------------------------------------- run and ping
ping_json=""
start_and_ping() {
    label=$1
    shift
    ping_json=""
    loki_start_server "$work" "$label" "$@" || return 1

    # Once it is up, a ping either works or something is wrong. Retrying for minutes only turns a
    # broken response into a long silence.
    attempt=0
    while [ $attempt -lt 3 ]; do
        ping_json=$("$loki_java_bin" -cp "$classpath" $pinger 127.0.0.1 "$port" 5000 \
            2>>"$work/$label-ping.log" || true)
        [ -n "$ping_json" ] && break
        attempt=$((attempt + 1))
        sleep 2
    done

    loki_stop_server
    if [ -z "$ping_json" ]; then
        echo "  server never answered a ping, see $work/$label.log" >&2
        return 1
    fi
    return 0
}

# The pipeline on its own first. When something is wrong at that level this says which method and
# why, where the full server below can only say that a ping timed out.
say "netty rig"
rig_status=0
"$loki_java_bin" -cp "$classpath" NettyRig "$(topath "$work/server.jar")" "$((port + 1))" \
    2>>"$work/rig.log" || rig_status=$?
case $rig_status in
    0) ;;
    3) say "  rig skipped for this version" ;;
    *) fail "the netty rig failed, see $work/rig.log" ;;
esac

# Again, with the answer written the way a proxy writes it: the frame's length in a buffer of its
# own, and the packet in the next one. Velocity does that, and Loki used to read the first of those
# as a whole frame, give up on it, and let the response through undeclared.
say "netty rig, answering in two writes"
rig_status=0
"$loki_java_bin" -cp "$classpath" NettyRig "$(topath "$work/server.jar")" "$((port + 2))" split \
    2>>"$work/rig.log" || rig_status=$?
case $rig_status in
    0) ;;
    3) say "  rig skipped for this version" ;;
    *) fail "the netty rig failed on a split frame, see $work/rig.log" ;;
esac

loki_write_server_dir "$work" "$port"

say "starting without Loki, as a control"
if start_and_ping control; then
    case "$ping_json" in
        *'"loki"'*|*"loki="*) fail "a plain server already declares a profile API" ;;
        *) ok "a plain server declares nothing" ;;
    esac
else
    fail "control run did not come up"
fi

say "starting with Loki"
if start_and_ping loki "-javaagent:$(topath "$loki_agent_jar")=$api" \
        -DLoki.enforce_secure_profile=true; then
    case "$ping_json" in
        *"$declared_marker"*) ok "Loki declared its API server" ;;
        *'"loki"'*|*"loki="*) fail "declared something unexpected: $ping_json" ;;
        *) fail "no declaration in the status response" ;;
    esac
    case "$ping_json" in
        *'"version"'*) ok "the rest of the status survived the rewrite" ;;
        *) fail "the status response lost its own fields: $ping_json" ;;
    esac
    case "$ping_json" in
        *'"enforceSecureProfile":true'*) ok "and passed on that it checks signatures here" ;;
        *) fail "did not declare secure profile enforcement, which it was started with" ;;
    esac
else
    fail "Loki run did not come up"
fi

echo
if [ $failures -eq 0 ]; then
    echo "real-server-test[$version]: PASSED"
else
    echo "real-server-test[$version]: $failures FAILED"
fi
exit $failures
