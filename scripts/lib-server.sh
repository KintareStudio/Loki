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

    # 1.17 asks for 16, which few people have; a later JDK runs it. Only upwards from 16, because a
    # version that asks for 8 means it, and those do not start on anything newer.
    if [ -z "$loki_java_home" ] && [ "$major" -ge 16 ]; then
        for newer in 17 21 25; do
            [ "$newer" -gt "$major" ] || continue
            loki_java_home=$(eval "echo \${LOKI_JDK$newer:-}")
            [ -n "$loki_java_home" ] && { major=$newer; break; }
        done
    fi

    if [ -z "$loki_java_home" ]; then
        echo "  needs Java $major; set LOKI_JDK$major to a JDK $major" >&2
        return 3
    fi
    loki_java_bin="$loki_java_home/bin/java"
}

# Whether this version predates the status response, which is what decides which test applies to it.
# Asked of the version rather than of where its jar comes from: Mojang still publishes the servers
# from 1.2.5 on, and those are pre-1.7 all the same.
loki_is_legacy() {
    case $1 in
        c*|a*|b*|1.0|1.0.*|1.[1-6]|1.[1-6].*) return 0 ;;
        *) return 1 ;;
    esac
}

# Servers Mojang no longer publishes, from betacraft.uk, which archives them unmodified.
#
# Beta is filed under the client's own id, so that needs no table: b1.7.3 is served by b1.7.3.
# Written out below are the ones where the id does not carry over — Alpha, whose numbering does not
# line up with the client's at all, Classic, and 1.0, whose jar is called 1.0.0.
loki_legacy_server_url() {
    case $1 in
        1.0)    echo "https://files.betacraft.uk/server-archive/release/1.0/1.0.0.jar"; return ;;
        b*)     echo "https://files.betacraft.uk/server-archive/beta/$1.jar"; return ;;
        1.1)    echo "https://files.betacraft.uk/server-archive/release/1.1/1.1.jar"; return ;;
        # 1.2.5 is the oldest server Mojang still publishes, so from there the manifest is the
        # better source: it is the original, and it is the one an operator would have.
        1.2.[1-4])
                echo "https://files.betacraft.uk/server-archive/release/1.2/$1.jar"; return ;;
    esac
    case $1 in
        b1.8.1) echo "https://files.betacraft.uk/server-archive/beta/b1.8.1.jar" ;;
        b1.6.6) echo "https://files.betacraft.uk/server-archive/beta/b1.6.6.jar" ;;
        b1.5_01) echo "https://files.betacraft.uk/server-archive/beta/b1.5_01.jar" ;;
        b1.4_01) echo "https://files.betacraft.uk/server-archive/beta/b1.4_01.jar" ;;
        b1.2_01) echo "https://files.betacraft.uk/server-archive/beta/b1.2_01.jar" ;;
        b1.1_02) echo "https://files.betacraft.uk/server-archive/beta/b1.1_02.jar" ;;
        # The Alpha pairings are the protocol version each server checks for, read out of its own
        # bytecode next to the "Outdated client!" it prints: a0.1.0 wants 13, a0.1.2_01 wants 14,
        # a0.1.4 wants 1, a0.2.0 wants 2, a0.2.2 wants 3 and a0.2.8 wants 6. The numbering restarted
        # at a1.0.17, which is why the servers do not sort the way their names do.
        a1.2.6)     echo "https://files.betacraft.uk/server-archive/alpha/a0.2.8.jar" ;;
        a1.2.0)     echo "https://files.betacraft.uk/server-archive/alpha/a0.2.2.jar" ;;
        a1.1.2_01)  echo "https://files.betacraft.uk/server-archive/alpha/a0.2.0.jar" ;;
        a1.0.17_04) echo "https://files.betacraft.uk/server-archive/alpha/a0.1.4.jar" ;;
        a1.0.16)    echo "https://files.betacraft.uk/server-archive/alpha/a0.1.2_01.jar" ;;
        a1.0.15|a1.0.14|a1.0.11)
                    echo "https://files.betacraft.uk/server-archive/alpha/a0.1.0.jar" ;;
        b1.8)   echo "https://files.betacraft.uk/server-archive/beta/b1.8.jar" ;;
        b1.7.3) echo "https://files.betacraft.uk/server-archive/beta/b1.7.3.jar" ;;
        a0.2.8) echo "https://files.betacraft.uk/server-archive/alpha/a0.2.8.jar" ;;
        c1.10|c0.30_01c)
                echo "https://files.betacraft.uk/server-archive/classic/c1.10.jar" ;;
        *)      echo "" ;;
    esac
}

# Note for callers: this and the functions above assign to "version" and "work", which sh has no
# way of keeping to themselves. A caller that keeps its base directory in a variable of either name
# will find it overwritten — see the note in legacy-matrix.sh.
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
    if loki_is_legacy "$version"; then
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
view-distance=3
simulation-distance=3
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
    # A Classic server has no main class in its manifest and no nogui to give it, so it is started
    # by name. Asked of the jar rather than decided by version id, like everything else here.
    loki_entry="-jar server.jar nogui"
    if "$loki_java_home/bin/jar" tf "$(topath "$work/server.jar")" 2>/dev/null \
            | grep -q "^com/mojang/minecraft/server/MinecraftServer.class$"; then
        loki_entry="-cp server.jar com.mojang.minecraft.server.MinecraftServer"
    fi

    "$loki_java_bin" -Xmx1G $LOKI_TEST_JVM_ARGS "$@" $loki_entry > "$work/$label.log" 2>&1 &
    loki_server_pid=$!
    cd "$loki_previous_dir" || return 1

    waited=0
    while [ $waited -lt 300 ]; do
        if ! kill -0 "$loki_server_pid" 2>/dev/null; then
            # A server of this age stops when its console reaches end of file, and a run with no
            # console attached occasionally hands it one straight away — it reads as "Stopping
            # server" partway through generating the world. Worth one more go before calling it a
            # failure, because it is not one, and the second attempt finds the world already there.
            if [ -z "$loki_retried" ] && grep -q 'Stopping server' "$work/$label.log" 2>/dev/null; then
                echo "  server stopped before it finished starting; trying once more" >&2
                loki_retried=yes
                loki_start_server "$work" "$label" "$@"
                return $?
            fi
            echo "  server exited early, see $work/$label.log" >&2
            tail -5 "$work/$label.log" >&2
            return 1
        fi
        # "Done (1.234s)!" from Beta on, and a bare "Done!" in Alpha. Both are followed by the same
        # offer of help, which is the part that has not changed since a0.1.0. Classic says neither,
        # and announces the port it is listening on instead.
        if grep -qE 'For help, type|Now accepting input on' "$work/$label.log" 2>/dev/null; then
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
    loki_retried=""   # each server gets its own second chance, not one between them all
    [ -n "$loki_server_pid" ] || return 0
    kill "$loki_server_pid" 2>/dev/null || true
    wait "$loki_server_pid" 2>/dev/null || true
    loki_server_pid=""
}
