#!/usr/bin/env bash
#
# Checks that a brotli4j JAR built on JDK 22+ really contains its FFM replacements:
#   - the manifest has "Multi-Release: true";
#   - every class in src/main/java22 is packaged under META-INF/versions/22/;
#   - jar --validate accepts the versioned entries.
#
# Runs once in the shared JDK 25 build, right after packaging. Running it by hand needs the
# jar tool of JDK 17+ on the PATH: older ones have no --validate.
#
# Usage: check-multi-release-jar.sh <brotli4j.jar> <src/main/java22 directory>
#
set -euo pipefail

JAR="${1:?usage: check-multi-release-jar.sh <jar> <java22-src-dir>}"
SRC="${2:?usage: check-multi-release-jar.sh <jar> <java22-src-dir>}"
errors=0

if ! unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | grep -qx 'Multi-Release: true'; then
    echo "FAIL  manifest has no 'Multi-Release: true'"
    errors=1
fi

entries="$(unzip -Z1 "$JAR")"
count=0
if [ -d "$SRC" ]; then
    while IFS= read -r source; do
        class="META-INF/versions/22/${source%.java}.class"
        count=$((count + 1))
        if ! grep -Fxq "$class" <<< "$entries"; then
            echo "FAIL  missing $class"
            errors=1
        fi
    done < <(cd "$SRC" && find . -name '*.java' | sed 's|^\./||' | sort)
fi

if ! jar --validate --file "$JAR"; then
    echo "FAIL  jar --validate"
    errors=1
fi

[ "$errors" -eq 0 ] && echo "OK    $JAR (Multi-Release, $count classes under META-INF/versions/22)"
exit "$errors"
