#
# Standing up a real Minecraft server, for the scripts that need one.
#
# Sourced, never run. Two scripts want a downloaded server jar, the right JDK for it and a server
# that is actually finished starting; a second copy of that is a second thing to get wrong, and the
# fiddly parts here — Mojang's own metadata deciding the JDK, waiting for "Done (" rather than for
# the port — are exactly the parts that were got wrong before.
#
# EULA: loki_write_server_dir writes eula=true into the directory it prepares. Callers say so.
#

# Git Bash rewrites anything that looks like a path before handing it to a Windows program, which
# turns a URL in an agent argument into a filename. Turn that off and convert the paths here.
cp_sep=":"
topath() { echo "$1"; }
if command -v cygpath >/dev/null 2>&1; then
    cp_sep=";"
    topath() { cygpath -m "$1"; }
    MSYS_NO_PATHCONV=1
    MSYS2_ARG_CONV_EXCL="*"
    export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL
fi

# Builds the agent unless the caller already did. A stale agent quietly tests the code you had
# before you changed it, which is the one outcome this whole exercise cannot afford.
loki_agent() {
    if [ -z "$LOKI_TEST_SKIP_BUILD" ]; then
        sh "$loki_root/scripts/build-agent.sh" || return 1
    fi
    loki_agent_jar=$(ls "$loki_root/build/dist"/Loki-*.jar | head -1)
}

# Reads the version metadata, and with it which Java this version needs: Mojang says so themselves,
# so ask rather than keep a table of guesses in step with them.
#
# Sets loki_needs_java. Versions old enough to predate the field are the ones that want 8.
loki_fetch_version() {
    version=$1
    work=$2
    mkdir -p "$work"

    # An archived server has no entry in Mojang's manifest to read, and every one of them predates
    # the field that says which Java it needs. They all want 8.
    if [ -n "$(loki_legacy_server_url "$version")" ]; then
        loki_needs_java=8
        return 0
    fi

    manifest="$loki_root/build/real-server/version_manifest_v2.json"
    mkdir -p "$loki_root/build/real-server"
    [ -f "$manifest" ] || curl -sS --max-time 60 -o "$(topath "$manifest")" \
        https://piston-meta.mojang.com/mc/game/version_manifest_v2.json || return 1

    if [ ! -f "$work/version.json" ]; then
        version_url=$(grep -o "{\"id\": \"$version\", \"type\": \"[a-z]*\", \"url\": \"[^\"]*\"" "$manifest" \
            | head -1 | grep -o 'https://[^"]*')
        if [ -z "$version_url" ]; then
            echo "  no such version in the manifest: $version" >&2
            return 1
        fi
        curl -sS --max-time 60 -o "$(topath "$work/version.json")" "$version_url" || return 1
    fi

    loki_needs_java=$(grep -o '"majorVersion": *[0-9]*' "$work/version.json" | head -1 | grep -o '[0-9]*')
    [ -n "$loki_needs_java" ] || loki_needs_java=8
}

# The JDK for a major version, from LOKI_JDK<major>. Sets loki_java_home and loki_java_bin, and
# returns 3 — "skipped", not "failed" — when the caller has not got that one.
loki_jdk() {
    major=$1
    loki_java_home=$(eval "echo \${LOKI_JDK$major:-}")
    if [ -z "$loki_java_home" ]; then
        echo "  needs Java $major; set LOKI_JDK$major to a JDK $major" >&2
        return 3
    fi
    loki_java_bin="$loki_java_home/bin/java"
}

# Servers Mojang no longer publishes. Its manifest carries client jars for these versions and no
# server, so the originals come from betacraft.uk, which archives them unmodified.
#
# The ids are the server's own, which are not the client's: the Alpha client a1.2.6 was served by
# a0.2.8, and Classic clients by the c1.x line. That is why this is a table and not a rule.
loki_legacy_server_url() {
    case $1 in
        b1.8.1) echo "https://files.betacraft.uk/server-archive/beta/b1.8.1.jar" ;;
        b1.8)   echo "https://files.betacraft.uk/server-archive/beta/b1.8.jar" ;;
        b1.7.3) echo "https://files.betacraft.uk/server-archive/beta/b1.7.3.jar" ;;
        a0.2.8) echo "https://files.betacraft.uk/server-archive/alpha/a0.2.8.jar" ;;
        c1.10)  echo "https://files.betacraft.uk/server-archive/classic/c1.10.jar" ;;
        *)      echo "" ;;
    esac
}

loki_fetch_server() {
    version=$1
    work=$2
    [ -f "$work/server.jar" ] && return 0

    legacy=$(loki_legacy_server_url "$version")
    if [ -n "$legacy" ]; then
        mkdir -p "$work"
        echo "  downloading $legacy"
        curl -sS --max-time 300 -o "$(topath "$work/server.jar")" "$legacy"
        return $?
    fi

    server_url=$(tr ',' '\n' < "$work/version.json" | grep -A2 '"server"' \
        | grep -o 'https://[^"]*server.jar' | head -1)
    [ -n "$server_url" ] || server_url=$(grep -o 'https://[^"]*/server.jar' "$work/version.json" | head -1)
    if [ -z "$server_url" ]; then
        echo "  $version publishes no server jar" >&2
        return 1
    fi
    echo "  downloading $server_url"
    curl -sS --max-time 300 -o "$(topath "$work/server.jar")" "$server_url"
}

# Offline mode on purpose: it is the case this feature is for. An online mode server hands the
# client a profile with its textures already attached, so nothing is ever looked up.
loki_write_server_dir() {
    work=$1
    port=$2
    echo "eula=true" > "$work/eula.txt"

    # A server of the Beta era reads a handful of keys and refuses the modern ones outright: it
    # rejects view-distance=2 with "Too small view radius!" and never finishes starting. Give those
    # only what they understood.
    if [ -n "$(loki_legacy_server_url "$version")" ]; then
        cat > "$work/server.properties" <<EOF
server-port=$port
online-mode=false
max-players=4
level-name=world
motd=Loki test server
EOF
        return 0
    fi

    cat > "$work/server.properties" <<EOF
server-port=$port
online-mode=false
max-players=4
view-distance=2
simulation-distance=2
level-type=flat
spawn-protection=0
sync-chunk-writes=false
enable-status=true
motd=Loki test server
EOF
}

# Starts a server and waits until it says it is done, rather than until the port opens: it binds
# well before it has filled in its status, and anything that lands in between gets an empty
# document, which is a true answer to a question worth nobody's time.
#
# Sets loki_server_pid. Extra arguments are passed to the JVM, before -jar.
loki_start_server() {
    work=$1
    label=$2
    shift 2

    # Started from here rather than from a subshell, so that $! is the JVM itself. A subshell's pid
    # is what a kill would reach instead, and the server would go on holding the world it locked,
    # which the next run then fails to start on.
    loki_previous_dir=$(pwd)
    cd "$work" || return 1
    "$loki_java_bin" -Xmx1G $LOKI_TEST_JVM_ARGS "$@" -jar server.jar nogui > "$work/$label.log" 2>&1 &
    loki_server_pid=$!
    cd "$loki_previous_dir" || return 1

    waited=0
    while [ $waited -lt 300 ]; do
        if ! kill -0 "$loki_server_pid" 2>/dev/null; then
            echo "  server exited early, see $work/$label.log" >&2
            tail -5 "$work/$label.log" >&2
            return 1
        fi
        if grep -q 'Done (' "$work/$label.log" 2>/dev/null; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done

    echo "  server never finished starting, see $work/$label.log" >&2
    loki_stop_server
    return 1
}

loki_stop_server() {
    [ -n "$loki_server_pid" ] || return 0
    kill "$loki_server_pid" 2>/dev/null || true
    wait "$loki_server_pid" 2>/dev/null || true
    loki_server_pid=""
}
