#!/bin/sh
#
# The one part of the pre-1.7 announcement a proxy can see, put in front of a real one.
#
# From 1.3 the marker rides on the end of the host in the handshake — the address the client says it
# dialled — because that is the only field left that nothing reads. Nothing, that is, except a
# proxy, which reads it to decide where to send the player. Every other era's marker sits in a field
# no proxy has ever looked at, so this is the whole of the exposure and it is worth measuring rather
# than reasoning about.
#
# The proxy is BungeeCord #701, from September 2013, because it is a proxy that supports the
# versions this affects: the ones in use today start at 1.8 and can never meet the marker at all.
#
#   client -> BungeeCord -> server
#
# Run twice, with and without Loki on the client, and the thing that must hold is the same both
# times: the player gets into the game. Whether the declaration survives the trip is recorded rather
# than required — a proxy that rewrites the host is entitled to drop it, and dropping it costs a
# declaration, not a login.
#
# What this found, and what it is now here to keep true: the marker is fine, and the block was not.
# BungeeCord forwards the host to the backend byte for byte, marker and all. But the block used to
# be raw bytes, and a proxy is parsing that stream, so it came back as "Unknown packet id 79" — the
# O of 0xFE 'L' 'O' 'K' — and took the connection with it. From 1.3 the block is a plugin message
# instead, which is a packet a proxy can decode and pass on or drop, and this run is what says so.
#
# The run without Loki is given an HTTP proxy that is not there. A client of this age fetches
# s3.amazonaws.com/MinecraftResources while it connects, which stopped existing years ago; Loki
# intercepts that request, so only the control is left waiting on it.
#
# EULA: this writes eula=true into the server directory it prepares.
#
# Usage:  scripts/proxy-test.sh [version ...]
#
set -e

loki_root=$(cd "$(dirname "$0")/.." && pwd)
. "$loki_root/scripts/lib-server.sh"

versions="$*"
[ -n "$versions" ] || versions="1.6.4 1.4.7"

base="$loki_root/build/proxy-test"
mkdir -p "$base"

# The proxy runs in a subshell so that it can have its own working directory, which means the pid
# held here is the shell's and not the JVM's. Killing only the shell leaves the proxy holding the
# port, and the next run of it then reports that it never came up.
# Starts the proxy in its own directory and waits until it says it is listening. Generous, and it
# tries twice: a build this old prints an "outdated build" notice and then sits for fifteen seconds
# before doing anything, and a port it held a moment ago may not be free yet.
start_proxy() {
    # LOKI_PROXY_AGENT puts Loki on the proxy as well, which is a deployment worth being able to
    # try: on 1.7 and up the proxy's own status response carries the declaration, and the proxy is
    # arguably where it belongs, since its address is the one the player typed.
    proxy_agent=""
    [ -n "$LOKI_PROXY_AGENT" ] && \
        proxy_agent="-javaagent:$(topath "$loki_agent_jar")=$api_root -DLoki.debug=true"

    attempt=1
    while [ $attempt -le 2 ]; do
        (cd "$dir/proxy" && "$jdk/bin/java" -Xmx512M $proxy_agent \
            -cp "BungeeCord.jar$cp_sep$(topath "$loki_root/build/test-classes")" \
            OldProxyHost "$proxy_main" > proxy.log 2>&1) &
        proxy_pid=$!

        waited=0
        while [ $waited -lt 120 ]; do
            grep -q 'Listening on' "$dir/proxy/proxy.log" 2>/dev/null && return 0
            sleep 2
            waited=$((waited + 2))
        done

        stop_proxy
        attempt=$((attempt + 1))
        sleep 5
    done
    tail -4 "$dir/proxy/proxy.log" >&2 2>/dev/null || true
    return 1
}

stop_proxy() {
    [ -n "$proxy_pid" ] || return 0
    for child in $(ps | awk -v parent="$proxy_pid" '$2 == parent { print $1 }'); do
        kill -9 "$child" 2>/dev/null || true
    done
    kill -9 "$proxy_pid" 2>/dev/null || true
    wait "$proxy_pid" 2>/dev/null || true
    proxy_pid=""
}

ok() { echo "  ok   $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
note() { echo "  --   $*"; }
failures=0

loki_agent || exit 1
jdk=${LOKI_JDK8:-}
[ -n "$jdk" ] || { echo "set LOKI_JDK8" >&2; exit 2; }

# The backend runs Loki too, so that there is a declaration for the proxy to pass on or to drop.
stub_log="$base/stub.log"
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
api_root=$(grep '^ROOT=' "$stub_log" | cut -d= -f2-)
[ -n "$api_root" ] || { echo "the API server never came up, see $stub_log" >&2; exit 1; }
echo "  API server: $api_root"

# BungeeCord builds old enough to speak these protocols are not on the project's own site any more.
# mcjars keeps them, indexed by the Minecraft version each build supported.
bungee_url() {
    case $1 in
        1.6.4) echo "https://files.mcjars.app/bungeecord/1.6.4/701/server.jar" ;;
        1.6.2) echo "https://files.mcjars.app/bungeecord/1.6.2/669/server.jar" ;;
        1.6.1) echo "https://files.mcjars.app/bungeecord/1.6.1/596/server.jar" ;;
        1.5.2|1.5) echo "https://files.mcjars.app/bungeecord/1.5/559/server.jar" ;;
        1.4.7) echo "https://files.mcjars.app/bungeecord/1.4.7/269/server.jar" ;;
        *)     echo "" ;;
    esac
}

port=${LOKI_TEST_PORT:-26400}
for version in $versions; do
    echo
    echo "== $version through a proxy =="
    dir="$base/$version"
    mkdir -p "$dir/proxy"

    url=$(bungee_url "$version")
    if [ -z "$url" ]; then
        fail "no proxy of that era is indexed for $version"
        continue
    fi

    loki_needs_java=8
    loki_java_home=$jdk
    loki_java_bin="$jdk/bin/java"

    if [ -z "$(loki_legacy_server_url "$version")" ]; then
        loki_fetch_version "$version" "$dir" || { fail "no metadata for $version"; continue; }
    fi
    loki_fetch_server "$version" "$dir" || { fail "no server for $version"; continue; }
    echo "eula=true" > "$dir/eula.txt"

    if [ ! -f "$dir/proxy/BungeeCord.jar" ]; then
        echo "  downloading $url"
        curl -sSL --max-time 300 -o "$(topath "$dir/proxy/BungeeCord.jar")" "$url" || {
            fail "could not download the proxy"; continue; }
    fi

    # Where the entry point is depends on how old the build is — the launcher class was renamed
    # along the way — so it is read out of the jar rather than written down here.
    proxy_main=$(unzip -p "$dir/proxy/BungeeCord.jar" META-INF/MANIFEST.MF 2>/dev/null \
        | grep -i '^Main-Class:' | tr -d '\r' | sed 's/^[Mm]ain-[Cc]lass: *//')
    if [ -z "$proxy_main" ]; then
        fail "the proxy jar for $version names no main class"
        continue
    fi
    echo "  proxy entry point: $proxy_main"

    # Cleared per version, so that a run where one of the two passes never happened cannot be
    # compared against whatever the last one left behind.
    rm -f "$dir"/vanilla-proxy.faults "$dir"/loki-proxy.faults
    baseline=no

    backend=$port
    front=$((port + 1))

    # Offline throughout. What is being tested is whether a proxy of this era minds the marker in
    # the handshake, and authentication is a different question with its own tests: putting it in
    # the way here would only mean a login failing for a reason that is not the one under test.
    printf 'server-port=%s\nonline-mode=false\nlevel-name=w\nmax-players=4\n' "$backend" \
        > "$dir/server.properties"

    cat > "$dir/proxy/config.yml" <<EOF
listeners:
- query_port: $front
  motd: '&1Loki proxy test'
  tab_list: GLOBAL_PING
  query_enabled: false
  proxy_protocol: false
  forced_hosts: {}
  ping_passthrough: false
  priorities:
  - lobby
  bind_local_address: true
  host: 127.0.0.1:$front
  max_players: 4
  tab_size: 60
  force_default_server: false
servers:
  lobby:
    motd: '&1Loki backend'
    address: 127.0.0.1:$backend
    restricted: false
    fallback: true
online_mode: false
ip_forward: false
network_compression_threshold: 256
player_limit: -1
timeout: 30000
log_commands: false
disabled_commands:
- disabledcommandhere
groups: {}
permissions:
  default:
  - bungeecord.command.server
  - bungeecord.command.list
connection_throttle: -1
stats: null
EOF

    for mode in vanilla loki; do
        marker="$dir/$mode-client.log"
        if [ "$mode" = loki ]; then
            LOKI_AGENT_ARG="$api_root"; export LOKI_AGENT_ARG
        else
            unset LOKI_AGENT_ARG
        fi

        # With Loki on the proxy the backend is left plain on purpose, because that is the whole
        # claim being tested: on a proxied network the operator installs Loki once, on the thing
        # players connect to, and the servers behind it need know nothing about any of this.
        backend_agent="-javaagent:$(topath "$loki_agent_jar")=$api_root"
        [ -n "$LOKI_PROXY_AGENT" ] && backend_agent=""

        loki_start_server "$dir" harness $backend_agent \
            || { fail "$version server did not start"; continue; }

        if ! start_proxy; then
            fail "the proxy never came up, see $dir/proxy/proxy.log"
            loki_stop_server
            continue
        fi

        # A client of this age fetches http://s3.amazonaws.com/MinecraftResources/ while it connects,
        # on the same thread, and that address stopped existing years ago. Loki intercepts the
        # request, so the run with Loki never notices; the run without it can sit there until the
        # network gives up, which is longer than any sensible test waits and made the control fail
        # while the thing under test passed. Pointing it at an HTTP proxy that is not there makes it
        # fail at once, which is the same answer it would get from a working internet.
        seconds=${LOKI_CLIENT_SECONDS:-30}
        client_args=$LOKI_CLIENT_JVM_ARGS
        # And time as well as a dead-end proxy, because the wait is on the network rather than on
        # anything this can shorten: given ninety seconds the client of 2012 gives up on the address
        # and joins normally, which is how it was established that nothing is actually wrong with it.
        [ "$mode" = vanilla ] && seconds=$((seconds + 90))
        if [ "$mode" = vanilla ]; then
            client_args="$client_args -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=1"
        fi
        LOKI_CLIENT_SECONDS=$((seconds + 20)) LOKI_CLIENT_JVM_ARGS="$client_args" \
            sh "$loki_root/scripts/legacy-client.sh" "$version" 127.0.0.1 "$front" \
            ProxyTester 0 > "$marker" 2>&1 &
        client_pid=$!

        waited=0
        while [ $waited -lt $seconds ] && kill -0 $client_pid 2>/dev/null; do
            sleep 2
            waited=$((waited + 2))
        done

        for child in $(ps | awk -v parent="$client_pid" '$2 == parent { print $1 }'); do
            kill -9 "$child" 2>/dev/null || true
        done
        kill -9 $client_pid 2>/dev/null || true
        wait $client_pid 2>/dev/null || true

        # The whole point. A marker a proxy chokes on would show up here as a player who never
        # arrives, and it would be worse than no marker at all.
        if grep -qE "logged in" "$dir/harness.log"; then
            ok "$mode client got through the proxy and into the game"
            [ "$mode" = vanilla ] && baseline=yes
        else
            fail "$mode client never arrived, see $marker, $dir/proxy/proxy.log and $dir/harness.log"
        fi

        # Not "did the proxy log an exception": it logs one every time a client goes away without
        # saying goodbye, which is how this harness ends every run, and that happens with or without
        # Loki. What matters is whether the marked handshake makes it complain about something the
        # unmarked one did not, so the vanilla run is the baseline and the Loki run is compared
        # against it.
        cp "$dir/proxy/proxy.log" "$dir/$mode-proxy.log"
        grep -oE "[A-Za-z.]+(Exception|Error)" "$dir/$mode-proxy.log" 2>/dev/null | sort -u \
            > "$dir/$mode-proxy.faults"
        if [ "$mode" = vanilla ]; then
            note "the proxy's own baseline: $(tr '\n' ' ' < "$dir/vanilla-proxy.faults")"
        else
            if [ "$baseline" != yes ]; then
                note "no baseline to compare against: the run without Loki never arrived"
                extra=""
            else
                extra=$(comm -13 "$dir/vanilla-proxy.faults" "$dir/loki-proxy.faults" | tr '\n' ' ')
            fi
            if [ -n "$extra" ]; then
                fail "the marked handshake upset the proxy: $extra"
            else
                ok "and the proxy minded it no more than an unmarked one"
            fi
        fi

        if [ "$mode" = loki ]; then
            if [ -n "$LOKI_PROXY_AGENT" ]; then
                # Here it is not a nicety: the proxy is the only thing running Loki, so if the
                # client is not told, nothing told it.
                if grep -q "Profiles will be answered by" "$marker"; then
                    ok "and the proxy told it where profiles come from"
                else
                    fail "the proxy declared nothing to a marked client, see $marker"
                fi
            elif grep -q "Server declared where profiles come from" "$marker"; then
                note "the declaration survived the trip"
            else
                note "the declaration did not survive the proxy, which costs a declaration only"
            fi
        fi

        stop_proxy
        loki_stop_server
        rm -f "$dir/proxy/proxy.log"
    done

    # What the proxy actually forwards, read off the wire rather than inferred from its source: the
    # backend is replaced by a probe that answers nothing and dumps what it was sent. This is the
    # question the whole exercise is about — whether a proxy passes the host on, alters it, or
    # refuses it — and the answer is different for every proxy, so it is measured for each one.
    probe_port=$((port + 2))
    "$jdk/bin/java" -cp "$(topath "$loki_root/build/test-classes")" LoginProbe "$probe_port" \
        > "$dir/behind.log" 2>&1 &
    probe_pid=$!
    sleep 2

    perl -pi -e "s|address: 127\.0\.0\.1:[0-9]+|address: 127.0.0.1:$probe_port|" \
        "$dir/proxy/config.yml"
    start_proxy || fail "the proxy never came up for the forwarding check"

    LOKI_AGENT_ARG="$api_root"; export LOKI_AGENT_ARG
    LOKI_CLIENT_SECONDS=15 sh "$loki_root/scripts/legacy-client.sh" "$version" 127.0.0.1 "$front" \
        ProxyTester 0 > "$dir/behind-client.log" 2>&1 || true
    sleep 2
    kill -9 $probe_pid 2>/dev/null || true
    wait $probe_pid 2>/dev/null || true
    stop_proxy

    # Either field: which one the probe puts it in depends on whether the version opens with a
    # handshake, and the marker rides on the host of whichever packet carries it.
    forwarded=$(grep -h '^LOGIN=\|^HANDSHAKE=' "$dir/behind.log" | cut -d= -f2- | tr -d '\n')
    # NUL, "Loki", NUL, as a client of this era writes a string: two bytes a character
    if echo "$forwarded" | grep -q "0000004c006f006b00690000"; then
        ok "the proxy forwarded the marker to the backend untouched"
    elif [ -n "$forwarded" ]; then
        note "the proxy rewrote the host: the marker did not reach the backend"
    else
        fail "nothing reached the probe behind the proxy, see $dir/behind.log"
    fi

    port=$((port + 2))
done

echo
if [ $failures -eq 0 ]; then
    echo "proxy-test: PASSED"
else
    echo "proxy-test: $failures FAILED"
fi
exit $failures
