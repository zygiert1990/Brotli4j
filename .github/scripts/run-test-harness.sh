#!/usr/bin/env bash
#
# Runs .github/test-harness/pom.xml against artifacts downloaded from the shared builds:
#   <dir>/brotli4j/target/brotli4j-<version>.jar
#   <dir>/brotli4j/target/test-classes/
#   <dir>/service/target/service-<version>.jar
#   <dir>/native/native-<platform>-<version>.jar
#
# Usage: run-test-harness.sh <dir> <maven command...>    e.g. run-test-harness.sh ci-artifacts ./mvnw
#
set -euo pipefail

DIR="${1:?usage: run-test-harness.sh <dir> <maven command...>}"
shift
[ "$#" -gt 0 ] || { echo "usage: run-test-harness.sh <dir> <maven command...>" >&2; exit 1; }

# Absolute path; on Windows (Git Bash) in a form Java understands.
absolute() {
    local path
    path="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
    if command -v cygpath > /dev/null; then cygpath -m "$path"; else echo "$path"; fi
}

# The single main JAR matching <pattern> in <dir>, ignoring sources/javadoc JARs.
main_jar() {
    local found
    found="$(find "$DIR/$1" -maxdepth 1 -name "$2" ! -name '*-sources.jar' ! -name '*-javadoc.jar')"
    if [ -z "$found" ] || [ "$(echo "$found" | wc -l)" -ne 1 ]; then
        echo "expected exactly one $2 in $DIR/$1, found: ${found:-none}" >&2
        exit 1
    fi
    absolute "$found"
}

BROTLI4J_JAR="$(main_jar brotli4j/target 'brotli4j-*.jar')"
SERVICE_JAR="$(main_jar service/target 'service-*.jar')"
NATIVE_JAR="$(main_jar native 'native-*.jar')"
TEST_CLASSES="$(absolute "$DIR/brotli4j/target/test-classes")"

# !jdk17+ keeps JUnit at the root default (5.14.4), the version the tests were compiled against.
"$@" -B --show-version -ntp -f .github/test-harness/pom.xml -P '!jdk17+' verify \
    -Dbrotli4j.jar="$BROTLI4J_JAR" \
    -Dservice.jar="$SERVICE_JAR" \
    -Dnative.jar="$NATIVE_JAR" \
    -Dtest.classes="$TEST_CLASSES"
