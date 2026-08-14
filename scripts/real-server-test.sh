#!/bin/sh
#
# Stands up a real Minecraft server with Loki attached and checks that its Server List Ping
# declares the API server Loki was pointed at.
#
# Everything `ant test` covers is stood up in process; this is the part that cannot be, because it
# needs Mojang's own server jar and therefore the network and Mojang's EULA.
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

java_home=$2
root=$(cd "$(dirname "$0")/.." && pwd)
work="$root/build/real-server/$version"
port=${LOKI_TEST_PORT:-25599}

# Need not resolve: Loki stores the root before it tries to read anything from it, and a refused
# connection keeps the run quick. Pass LOKI_TEST_API to point at a real one.
api=${LOKI_TEST_API:-http://127.0.0.1:1/authlib-injector}

# Extra JVM arguments for the server under test, e.g. LOKI_TEST_JVM_ARGS=-DLoki.debug=true
LOKI_TEST_JVM_ARGS=${LOKI_TEST_JVM_ARGS:-}

# Git Bash rewrites anything that looks like a path before handing it to a Windows program, which
# turns the URL in the agent argument into a filename. Turn that off and convert the paths here.
cp_sep=":"
topath() { echo "$1"; }
if command -v cygpath >/dev/null 2>&1; then
    cp_sep=";"
    topath() { cygpath -m "$1"; }
    MSYS_NO_PATHCONV=1
    MSYS2_ARG_CONV_EXCL="*"
    export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL
fi

say() { echo "  $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
ok() { echo "  ok   $*"; }
failures=0

echo "== $version =="
say "EULA: this run writes eula=true into $work, accepting https://aka.ms/MinecraftEULA"

# ----------------------------------------------------------------- build and fetch
# Rebuilt unless someone already did it: a stale agent quietly tests the code you had before you
# changed it, which is the one outcome this whole exercise cannot afford. The matrix builds once up
# front and sets LOKI_TEST_SKIP_BUILD, since the same jar serves every version.
if [ -z "$LOKI_TEST_SKIP_BUILD" ]; then
    sh "$root/scripts/build-agent.sh" || exit 1
fi
agent=$(ls "$root/build/dist"/Loki-*.jar | head -1)
say "agent: $(basename "$agent") ($(date -r "$agent" '+%H:%M:%S'))"

mkdir -p "$work"
manifest="$root/build/real-server/version_manifest_v2.json"
[ -f "$manifest" ] || curl -sS --max-time 60 -o "$(topath "$manifest")" \
    https://piston-meta.mojang.com/mc/game/version_manifest_v2.json

if [ ! -f "$work/version.json" ]; then
    version_url=$(grep -o "{\"id\": \"$version\", \"type\": \"[a-z]*\", \"url\": \"[^\"]*\"" "$manifest" \
        | head -1 | grep -o 'https://[^"]*')
    if [ -z "$version_url" ]; then
        echo "  no such version in the manifest: $version" >&2
        exit 1
    fi
    curl -sS --max-time 60 -o "$(topath "$work/version.json")" "$version_url"
fi

# Mojang says which Java a version needs, so ask rather than keep a table of guesses in step with
# them. Versions old enough to predate the field are the ones that want 8.
needs_java=$(grep -o '"majorVersion": *[0-9]*' "$work/version.json" | head -1 | grep -o '[0-9]*')
[ -n "$needs_java" ] || needs_java=8
if [ -z "$java_home" ]; then
    java_home=$(eval "echo \${LOKI_JDK$needs_java:-}")
fi
if [ -z "$java_home" ]; then
    echo "  $version needs Java $needs_java; set LOKI_JDK$needs_java to a JDK $needs_java" >&2
    exit 3
fi
say "java $needs_java: $java_home"
java_bin="$java_home/bin/java"

if [ ! -f "$work/server.jar" ]; then
    server_url=$(tr ',' '\n' < "$work/version.json" | grep -A2 '"server"' \
        | grep -o 'https://[^"]*server.jar' | head -1)
    [ -n "$server_url" ] || server_url=$(grep -o 'https://[^"]*/server.jar' "$work/version.json" | head -1)
    if [ -z "$server_url" ]; then
        echo "  $version publishes no server jar" >&2
        exit 1
    fi
    say "downloading $server_url"
    curl -sS --max-time 300 -o "$(topath "$work/server.jar")" "$server_url"
fi

# ----------------------------------------------------------------- server directory
echo "eula=true" > "$work/eula.txt"
cat > "$work/server.properties" <<EOF
server-port=$port
online-mode=false
max-players=1
view-distance=2
simulation-distance=2
level-type=flat
spawn-protection=0
sync-chunk-writes=false
enable-status=true
motd=Loki real server test
EOF

# ----------------------------------------------------------------- run and ping
ping_json=""
start_and_ping() {
    label=$1
    shift
    "$java_bin" -Xmx1G $LOKI_TEST_JVM_ARGS "$@" -jar server.jar nogui > "$work/$label.log" 2>&1 &
    server_pid=$!

    # Wait for the server to say it is done rather than for the port to open. It binds well before
    # it has filled in its status, and a ping that lands in between gets an empty document, which
    # is a true answer to a question worth nobody's time.
    ping_json=""
    ready=""
    waited=0
    while [ $waited -lt 300 ]; do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            echo "  server exited early, see $work/$label.log" >&2
            tail -5 "$work/$label.log" >&2
            return 1
        fi
        if grep -q 'Done (' "$work/$label.log" 2>/dev/null; then
            ready=yes
            break
        fi
        sleep 2
        waited=$((waited + 2))
    done

    # Once it is up, a ping either works or something is wrong. Retrying for minutes only turns a
    # broken response into a long silence.
    if [ -n "$ready" ]; then
        attempt=0
        while [ $attempt -lt 3 ]; do
            ping_json=$("$java_bin" -cp "$classpath" ServerPing 127.0.0.1 "$port" 5000 \
                2>>"$work/$label-ping.log" || true)
            [ -n "$ping_json" ] && break
            attempt=$((attempt + 1))
            sleep 2
        done
    fi

    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true

    if [ -z "$ping_json" ]; then
        echo "  server never answered a ping, see $work/$label.log" >&2
        return 1
    fi
    return 0
}

# Compiled per version, with that version's JDK, because a shared directory would hand a class file
# built by Java 25 to the Java 8 run that comes after it.
mkdir -p "$work/pinger"
"$java_home/bin/javac" -cp "$(topath "$root/build/classes")" -d "$(topath "$work/pinger")" \
    "$(topath "$root/src/test/java/ServerPing.java")"

classpath="$(topath "$root/build/classes")$cp_sep$(topath "$work/pinger")"

cd "$work"

say "starting without Loki, as a control"
if start_and_ping control; then
    case "$ping_json" in
        *'"loki"'*) fail "a plain server already declares a profile API" ;;
        *) ok "a plain server declares nothing" ;;
    esac
else
    fail "control run did not come up"
fi

say "starting with Loki"
if start_and_ping loki "-javaagent:$(topath "$agent")=$api"; then
    case "$ping_json" in
        *"\"profileApi\":\"$api\""*) ok "Loki declared $api" ;;
        *'"loki"'*) fail "declared something unexpected: $ping_json" ;;
        *) fail "no declaration in the status response" ;;
    esac
    case "$ping_json" in
        *'"version"'*) ok "the rest of the status survived the rewrite" ;;
        *) fail "the status response lost its own fields: $ping_json" ;;
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
