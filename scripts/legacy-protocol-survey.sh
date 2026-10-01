#!/bin/sh
#
# What the login and handshake packets look like, version by version, read out of the client jars.
#
# The pre-1.7 announcement puts its marker in a field the client sends and nobody reads, and where
# that field is depends on the shape of the packet. Those shapes were assumed to change once per
# era, and they do not: Alpha's login carries a second string, 1.6's handshake carries a host and a
# port, and both open with the same packet id as Beta's. Guessing that from five jars is how the
# filter came to write into the wrong field.
#
# So this reads every version instead. For each one it finds the packet registry — the class that
# complains about "Skipping packet" — takes the classes registered for ids 1 and 2, and prints the
# order of types their write method puts on the wire. That order is the whole answer: it says where
# the unused field is, and whether it is where the code thinks.
#
# Usage:  scripts/legacy-protocol-survey.sh [version ...]
#         with no arguments, every version the manifest has below 1.7
#
# Writes build/legacy-protocol/survey.txt, one line per packet per version.
#
set -e

root=$(cd "$(dirname "$0")/.." && pwd)
work="$root/build/legacy-protocol"
out="$work/survey.txt"
mkdir -p "$work"

topath() { echo "$1"; }
command -v cygpath >/dev/null 2>&1 && topath() { cygpath -m "$1"; }

jdk=${LOKI_JDK21:-${LOKI_JDK17:-${LOKI_JDK8:-}}}
[ -n "$jdk" ] || { echo "set LOKI_JDK21 (or 17, or 8)" >&2; exit 2; }
jar="$jdk/bin/jar"
javap="$jdk/bin/javap"

manifest="$root/build/real-server/version_manifest_v2.json"
[ -f "$manifest" ] || curl -sS --max-time 60 -o "$(topath "$manifest")" \
    https://piston-meta.mojang.com/mc/game/version_manifest_v2.json

versions="$*"
if [ -z "$versions" ]; then
    # Everything from when multiplayer arrived to the last version before the ping carries the
    # declaration on its own. Snapshots are left out: they change the protocol mid-week and nobody
    # runs them now, and a release either kept the shape or did not.
    versions=$(grep -o '"id": "[^"]*", "type": "\(old_alpha\|old_beta\|release\)"' "$manifest" \
        | sed 's/.*"id": "//; s/".*//' \
        | grep -E '^(a1\.[0-9]|b1\.[0-9]|1\.[0-6])' \
        | grep -vE '^1\.(7|8|9|1[0-9]|2[0-9])' )
fi

echo "# packet shapes below 1.7, read from the client jars" > "$out"
echo "# version  packet  wire order" >> "$out"

for version in $versions; do
    dir="$work/$version"
    mkdir -p "$dir"

    if [ ! -f "$dir/client.jar" ]; then
        # `|| true` throughout: a grep that finds nothing exits non-zero, and under set -e that
        # ends the survey on the first version the manifest happens not to carry
        url=$(grep -o "{\"id\": \"$version\", \"type\": \"[a-z_]*\", \"url\": \"[^\"]*\"" "$manifest" \
            | head -1 | grep -o 'https://[^"]*' || true)
        [ -n "$url" ] || { echo "$version  -  not in the manifest" >> "$out"; continue; }
        curl -sS --max-time 60 -o "$(topath "$dir/version.json")" "$url" || continue
        client=$(tr ',' '\n' < "$dir/version.json" | grep -A3 '"client"' \
            | grep -o 'https://[^"]*client.jar' | head -1 || true)
        [ -n "$client" ] || { echo "$version  -  publishes no client" >> "$out"; continue; }
        curl -sS --max-time 300 -o "$(topath "$dir/client.jar")" "$client" || continue
    fi

    if [ ! -d "$dir/x" ]; then
        mkdir -p "$dir/x"
        (cd "$dir/x" && "$jar" xf "../client.jar" 2>/dev/null) || true
    fi

    registry=$(grep -rl "Skipping packet\|Duplicate packet" --include="*.class" "$dir/x" \
        2>/dev/null | head -1 || true)
    if [ -z "$registry" ]; then
        echo "$version  -  no packet registry (no multiplayer yet)" >> "$out"
        continue
    fi

    # Each packet is registered as four instructions — the id, two booleans, then the class — so
    # the id is three back from the class, not the value immediately before it. Reading it the
    # other way round is how the first version of this reported a packet's neighbour as itself.
    pairs=$("$javap" -p -c -cp "$(topath "$dir/x")" "$(basename "$registry" .class)" 2>/dev/null \
        | sed 's/^ *//' \
        | awk '
            function push(line) {
                if (line ~ /iconst_[0-5]$/)  { sub(/.*iconst_/, "", line); return line }
                if (line ~ /bipush/)         { sub(/.*bipush */, "", line); return line }
                if (line ~ /sipush/)         { sub(/.*sipush */, "", line); return line }
                return ""
            }
            # Anchored on the call, because it is the call that says how many arguments there are:
            # Alpha registers (id, class), and from Beta it is (id, boolean, boolean, class). The
            # id is the same distance back only within one of those.
            /invokestatic.*\(I(ZZ)?Ljava\/lang\/Class;\)V/ {
                two = $0 ~ /\(IZZ/
                cls = l1
                id  = two ? push(l4) : push(l2)
                if (id != "" && cls ~ /\/\/ class /) {
                    sub(/.*\/\/ class /, "", cls)
                    print id, cls
                }
            }
            { l4 = l3; l3 = l2; l2 = l1; l1 = $0 }'
    )

    for id in 1 2; do
        class=$(echo "$pairs" | awk -v want="$id" '$1 == want { print $2; exit }')
        if [ -z "$class" ]; then
            echo "$version  0x0$id  not registered" >> "$out"
            continue
        fi

        # The wire order, from the method that writes it: a primitive shows up as its writeX call,
        # and a string as the field it reads before handing it to whatever writes strings in this
        # version, which is not always the same method.
        shape=$("$javap" -p -c -cp "$(topath "$dir/x")" "$(echo "$class" | tr '/' '.')" 2>/dev/null \
            | sed -n '/(java.io.DataOutput/,/^$/p' \
            | grep -oE "write(Int|Long|Byte|Short|Boolean|Float|Double)|:Ljava/lang/String;" \
            | sed 's/:Ljava\/lang\/String;/String/; s/write//' \
            | tr '\n' ' ')
        echo "$version  0x0$id  ${class}: ${shape:-(no write method)}" >> "$out"
    done
done

echo
echo "wrote $out"
