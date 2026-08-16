#!/bin/sh
#
# Runs a real Minecraft client of a pre-1.7 version, with Loki attached, connecting to a server.
#
# Everything before this drove a client written for the test, and that is not the same thing: the
# first one sent a protocol version and a login packet no real client of that version would have
# sent. What it proved is that servers reject nonsense. The packets this sends are the game's own.
#
# The client is assembled from Mojang's own version metadata — the jar, the libraries, the natives —
# and started as the applet, which is how a page used to tell the game where to connect. That is
# still in every client jar of the era, and it is why no window has to be clicked.
#
# Usage:  scripts/legacy-client.sh <version> <host> <port> [username] [session]
# e.g.    scripts/legacy-client.sh b1.7.3 127.0.0.1 25640 PlayerA token:abc:uuid
#
# LOKI_CLIENT_JVM_ARGS is passed to the JVM; LOKI_AGENT_ARG is the agent's own argument, and
# leaving it empty runs the client without Loki, which is the other half of every comparison.
#
set -e

version=$1
host=$2
port=$3
username=${4:-Tester}
session=${5:-0}
[ -n "$version" ] && [ -n "$host" ] && [ -n "$port" ] || {
    echo "usage: $0 <version> <host> <port> [username] [session]" >&2
    exit 2
}

root=$(cd "$(dirname "$0")/.." && pwd)
work="$root/build/legacy-client/$version"
mkdir -p "$work/natives" "$work/libs" "$work/game"

topath() { echo "$1"; }
cp_sep=":"
if command -v cygpath >/dev/null 2>&1; then
    cp_sep=";"
    topath() { cygpath -m "$1"; }
    MSYS_NO_PATHCONV=1
    MSYS2_ARG_CONV_EXCL="*"
    export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL
fi

jdk=${LOKI_JDK8:-}
[ -n "$jdk" ] || { echo "set LOKI_JDK8: these versions want Java 8 or older" >&2; exit 2; }

manifest="$root/build/real-server/version_manifest_v2.json"
[ -f "$manifest" ] || curl -sS --max-time 60 -o "$(topath "$manifest")" \
    https://piston-meta.mojang.com/mc/game/version_manifest_v2.json

# ----------------------------------------------------------------- assemble
if [ ! -f "$work/version.json" ]; then
    url=$(grep -o "{\"id\": \"$version\", \"type\": \"[a-z_]*\", \"url\": \"[^\"]*\"" "$manifest" \
        | head -1 | grep -o 'https://[^"]*' || true)
    [ -n "$url" ] || { echo "  no such version: $version" >&2; exit 1; }
    curl -sS --max-time 60 -o "$(topath "$work/version.json")" "$url"
fi

if [ ! -f "$work/client.jar" ]; then
    client=$(tr ',' '\n' < "$work/version.json" | grep -A3 '"client"' \
        | grep -o 'https://[^"]*client.jar' | head -1)
    echo "  downloading the client"
    curl -sS --max-time 300 -o "$(topath "$work/client.jar")" "$client"
fi

# Every jar the metadata names, natives included. Which of them this version actually wants is the
# metadata's business, not this script's, and downloading one too many costs nothing.
if [ ! -f "$work/libs/.done" ]; then
    echo "  downloading libraries"
    for lib in $(grep -o 'https://libraries.minecraft.net/[^"]*\.jar' "$work/version.json" | sort -u); do
        name=$(basename "$lib")
        [ -f "$work/libs/$name" ] || curl -sS --max-time 120 -o "$(topath "$work/libs/$name")" "$lib" || true
    done
    touch "$work/libs/.done"
fi

# The natives have to be on disk as files, not inside a jar, because that is where LWJGL looks
if [ ! -f "$work/natives/.done" ]; then
    for native in "$work/libs"/*natives-windows*.jar; do
        [ -f "$native" ] || continue
        # Converted, because path conversion is turned off above and jar.exe is a Windows program
        (cd "$work/natives" && "$jdk/bin/jar" xf "$(topath "$native")") || true
    done
    rm -rf "$work/natives/META-INF"
    touch "$work/natives/.done"
fi

classpath="$(topath "$work/client.jar")"
for lib in "$work/libs"/*.jar; do
    case "$lib" in *natives*) continue ;; esac
    classpath="$classpath$cp_sep$(topath "$lib")"
done
classpath="$classpath$cp_sep$(topath "$root/build/test-classes")"

# ----------------------------------------------------------------- run
agent=""
if [ -n "${LOKI_AGENT_ARG+set}" ]; then
    jar=$(ls "$root/build/dist"/Loki-*.jar | head -1)
    agent="-javaagent:$(topath "$jar")"
    [ -n "$LOKI_AGENT_ARG" ] && agent="$agent=$LOKI_AGENT_ARG"
fi

echo "  $version -> $host:$port as $username${agent:+ (with Loki)}"
cd "$work/game"
"$jdk/bin/java" $agent $LOKI_CLIENT_JVM_ARGS \
    "-Djava.library.path=$(topath "$work/natives")" \
    -Dloki.seconds="${LOKI_CLIENT_SECONDS:-40}" \
    -cp "$classpath" AppletHost "$username" "$session" "$host" "$port"
