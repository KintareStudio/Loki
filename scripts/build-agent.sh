#!/bin/sh
#
# Builds the agent jar the real server tests attach, once, with Java 8.
#
# Java 8 because loki.properties targets 1.5 and a modern javac refuses that outright. The jar this
# produces is version independent, so one build serves every Minecraft version under test; building
# per version is both slower and a way to fail halfway through a matrix.
#
# Usage:  scripts/build-agent.sh [jdk8-home]
#         LOKI_JDK8 or JDK8_HOME are used when no argument is given.
#
set -e

root=$(cd "$(dirname "$0")/.." && pwd)
jdk8=${1:-${LOKI_JDK8:-${JDK8_HOME:-}}}

topath() { echo "$1"; }
command -v cygpath >/dev/null 2>&1 && topath() { cygpath -m "$1"; }

mkdir -p "$root/build"
log="$root/build/build-agent.log"

if [ -n "$jdk8" ]; then
    JDK8_HOME=$(topath "$jdk8")
    export JDK8_HOME
else
    echo "  no Java 8 given, using whatever ant finds; expect 'Source option 5 is no longer supported'" >&2
fi

if ! (cd "$root" && ant > "$log" 2>&1); then
    echo "  agent build failed, see $log" >&2
    tail -6 "$log" >&2
    exit 1
fi

agent=$(ls "$root/build/dist"/Loki-*.jar | head -1)
echo "  agent: $(basename "$agent") ($(date -r "$agent" '+%H:%M:%S'))"
