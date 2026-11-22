#!/usr/bin/env bash
# Compiles the core-adapter module + its tests with the standalone kotlinc
# and runs the assertion-based test runner. No Gradle, no network, no deps.
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/.jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"

KOTLINC="${KOTLINC:-$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc}"
OUT="out"
mkdir -p "$OUT"

"$KOTLINC" \
  src/main/kotlin/autoconnect/adapter/*.kt \
  src/test/kotlin/autoconnect/adapter/*.kt \
  -include-runtime -d "$OUT/adapter-tests.jar"

java -jar "$OUT/adapter-tests.jar"
