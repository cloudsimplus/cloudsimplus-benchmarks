#!/usr/bin/env bash
# Compares the heap retained by each VM and Host before and after
# CloudSim Plus issue #586 (listener lists starting as the shared List.of()).
#
# It builds CloudSim Plus at the #586 commit and at its parent commit (using git worktrees,
# so the CloudSim Plus working copy isn't changed), then runs ListenersMemoryBenchmark with each JAR.
# The CloudSim Plus JAR comes first in the classpath, so its classes are used
# instead of the ones inside the benchmarks uber JAR.
#
# Usage: scripts/listeners-memory-before-after.sh [cloudsimplus-dir] [after-commit]
#   cloudsimplus-dir: CloudSim Plus repository (default: ../cloudsimplus)
#   after-commit:     commit with the change (default: the #586 commit). The "before" commit is its parent.

set -euo pipefail

readonly BENCHMARKS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
readonly CLOUDSIMPLUS_DIR="$(cd "${1:-$BENCHMARKS_DIR/../cloudsimplus}" && pwd)"
readonly AFTER_COMMIT="${2:-08134449e}"
readonly BEFORE_COMMIT="$AFTER_COMMIT~1"
readonly BENCHMARK_CLASS="org.cloudsimplus.listeners.ListenersMemoryBenchmark"
# A stop-the-world GC makes System.gc() do a full collection, so the used heap is accurate.
readonly JVM_OPTS=(-XX:+UseSerialGC -Xms2g -Xmx2g)

WORK_DIR="$(mktemp -d)"
readonly WORK_DIR

cleanup() {
    git -C "$CLOUDSIMPLUS_DIR" worktree remove --force "$WORK_DIR/before" 2>/dev/null || true
    git -C "$CLOUDSIMPLUS_DIR" worktree remove --force "$WORK_DIR/after" 2>/dev/null || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# Builds the CloudSim Plus JAR for a given commit and prints its path.
# $1: commit; $2: name for the worktree and the JAR ("before" or "after")
build_cloudsimplus() {
    local commit="$1" name="$2"
    local worktree="$WORK_DIR/$name"
    echo "Building CloudSim Plus $name #586 ($(git -C "$CLOUDSIMPLUS_DIR" rev-parse --short "$commit"))" >&2
    git -C "$CLOUDSIMPLUS_DIR" worktree add --quiet --detach "$worktree" "$commit"
    (cd "$worktree" && ./mvnw -q -B package -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true >&2)

    local jar
    jar="$(find "$worktree/target" -maxdepth 1 -name 'cloudsimplus-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
    cp "$jar" "$WORK_DIR/cloudsimplus-$name.jar"
    echo "$WORK_DIR/cloudsimplus-$name.jar"
}

build_benchmarks() {
    echo "Building the benchmarks" >&2
    (cd "$BENCHMARKS_DIR" && ./mvnw -q -B package >&2)
    find "$BENCHMARKS_DIR/target" -maxdepth 1 -name 'cloudsimplus-benchmarks-*-uber.jar' | head -1
}

# $1: CloudSim Plus JAR; $2: label printed in the results
run_benchmark() {
    java "${JVM_OPTS[@]}" -cp "$1:$UBER_JAR" "$BENCHMARK_CLASS" "$2"
    echo
}

BEFORE_JAR="$(build_cloudsimplus "$BEFORE_COMMIT" before)"
AFTER_JAR="$(build_cloudsimplus "$AFTER_COMMIT" after)"
UBER_JAR="$(build_benchmarks)"
readonly BEFORE_JAR AFTER_JAR UBER_JAR

echo
run_benchmark "$BEFORE_JAR" "before #586"
run_benchmark "$AFTER_JAR" "after #586"
