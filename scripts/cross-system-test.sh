#!/bin/sh
#
# Three clients on three different API servers, all joining one server that runs on a fourth.
#
# real-server-test.sh checks that a server declares its API server. This checks what a client does
# about that declaration, which is where the feature either works or renders everyone as Steve:
#
#   client on Mojang        joins a server whose players live on Yggdrasil A
#   client on Yggdrasil A   the same server, its own API server, so nothing should move
#   client on Yggdrasil B   the same server, a third party to it in every direction
#
# Each is a real JVM with the agent attached the way a launcher attaches it, driving the Netty that
# version shipped, connecting to a real server. Nothing here is stubbed except, optionally, the two
# API servers themselves.
#
# EULA: this script writes eula=true into the server directory it creates. Running it is how you
# accept https://aka.ms/MinecraftEULA for these throwaway servers. It says so on every run.
#
# Usage:  scripts/cross-system-test.sh <minecraft-version>
#
# Configured through the environment, since tokens have no business in a process listing:
#   LOKI_A_ROOT     the API server the test servers run on, and the one clients are redirected to
#   LOKI_B_ROOT     a second, unrelated API server, for the third client
#   LOKI_A_TOKEN    a token valid on A          (optional; skips the credential check without it)
#   LOKI_B_TOKEN    a token valid on B          (optional)
#   LOKI_MOJANG_TOKEN                           (optional, and rarely worth having)
#   LOKI_A_UUID     a player that exists on A   (optional; skips the profile checks without it)
#   LOKI_STUB=1     stand up two stub API servers instead, for a run with nothing real to point at
#
set -e

version=$1
if [ -z "$version" ]; then
    echo "usage: $0 <minecraft-version>" >&2
    exit 2
fi

loki_root=$(cd "$(dirname "$0")/.." && pwd)
. "$loki_root/scripts/lib-server.sh"

work="$loki_root/build/cross-system/$version"
port=${LOKI_TEST_PORT:-25601}

say() { echo "  $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
failures=0

echo "== $version =="
say "EULA: this run writes eula=true into $work, accepting https://aka.ms/MinecraftEULA"

loki_agent || exit 1
say "agent: $(basename "$loki_agent_jar") ($(date -r "$loki_agent_jar" '+%H:%M:%S'))"

loki_fetch_version "$version" "$work" || exit 1
loki_jdk "$loki_needs_java" || exit $?
say "java $loki_needs_java: $loki_java_home"
loki_fetch_server "$version" "$work" || exit 1

# Compiled per version with that version's JDK, because a shared directory would hand a class file
# built by Java 25 to the Java 8 run that comes after it.
mkdir -p "$work/classes"
"$loki_java_home/bin/javac" -cp "$(topath "$loki_agent_jar")" -d "$(topath "$work/classes")" \
    "$(topath "$loki_root/src/test/java/NettyRig.java")" \
    "$(topath "$loki_root/src/test/java/StubYggdrasil.java")" \
    "$(topath "$loki_root/src/test/java/CrossSystemClient.java")"
classpath="$(topath "$loki_agent_jar")$cp_sep$(topath "$work/classes")"

# ----------------------------------------------------------------- the two API servers
stub_pids=""
cleanup() {
    loki_stop_server
    for pid in $stub_pids; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

start_stub() {
    label=$1
    log="$work/stub-$label.log"
    rm -f "$log"
    "$loki_java_bin" -cp "$classpath" StubYggdrasil "$label" > "$log" 2>&1 &
    stub_pids="$stub_pids $!"

    waited=0
    while [ $waited -lt 100 ]; do
        if grep -q '^ROOT=' "$log" 2>/dev/null; then return 0; fi
        sleep 1
        waited=$((waited + 1))
    done
    echo "  stub $label never came up, see $log" >&2
    return 1
}

stub_value() { grep "^$2=" "$work/stub-$1.log" | head -1 | cut -d= -f2-; }

if [ -n "$LOKI_STUB" ]; then
    say "standing up two stub API servers, since none were given"
    start_stub A || exit 1
    start_stub B || exit 1
    a_root=$(stub_value A ROOT); a_token=$(stub_value A TOKEN); a_uuid=$(stub_value A UUID)
    b_root=$(stub_value B ROOT); b_token=$(stub_value B TOKEN)
    say "A publishes $(stub_value A KEYS) property keys and signs with the last of them"
else
    a_root=$LOKI_A_ROOT
    b_root=$LOKI_B_ROOT
    a_token=$LOKI_A_TOKEN
    b_token=$LOKI_B_TOKEN
    a_uuid=$LOKI_A_UUID
    if [ -z "$a_root" ] || [ -z "$b_root" ]; then
        echo "  set LOKI_A_ROOT and LOKI_B_ROOT, or LOKI_STUB=1" >&2
        exit 2
    fi
fi
say "A: $a_root"
say "B: $b_root"
[ -n "$a_uuid" ] || say "no LOKI_A_UUID given, so the profile and signature checks are skipped"

# ----------------------------------------------------------------- the server, on A
loki_write_server_dir "$work" "$port"
say "starting a $version server on A"
loki_start_server "$work" server "-javaagent:$(topath "$loki_agent_jar")=$a_root" || exit 1

# ----------------------------------------------------------------- the three clients
run_client() {
    label=$1
    api=$2
    token=$3

    agent="-javaagent:$(topath "$loki_agent_jar")"
    [ -n "$api" ] && agent="$agent=$api"

    status=0
    "$loki_java_bin" "$agent" -DLoki.enforce_secure_profile=true $LOKI_CLIENT_JVM_ARGS -cp "$classpath" \
        CrossSystemClient "$(topath "$work/server.jar")" 127.0.0.1 "$port" \
        "$label" "$a_root" "${a_uuid:--}" "${token:--}" 2>>"$work/client-$label.log" || status=$?

    case $status in
        0) ;;
        3) say "  client on $label skipped for this version" ;;
        *) fail "the client on $label did not hold up, see $work/client-$label.log" ;;
    esac
}

echo
run_client mojang "" "$LOKI_MOJANG_TOKEN"
echo
run_client A "$a_root" "$a_token"
echo
run_client B "$b_root" "$b_token"

loki_stop_server

echo
if [ $failures -eq 0 ]; then
    echo "cross-system-test[$version]: PASSED"
else
    echo "cross-system-test[$version]: $failures FAILED"
fi
exit $failures
