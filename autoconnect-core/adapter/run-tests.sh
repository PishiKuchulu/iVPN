#!/usr/bin/env bash
# Compiles the core-adapter module + its tests with the standalone kotlinc
# and runs the assertion-based test runner. No Gradle, no network, no deps.
set -euo pipefail

cd "$(dirname "$0")"

# Toolchain resolution (AUD-007): env first, then PATH, then the
# owner's legacy workspace layout — so a fresh clone / CI runner works
# without editing this file.
if [ -z "${JAVA_HOME:-}" ] && [ -x "$HOME/workspace/.jdk17/bin/java" ]; then
  export JAVA_HOME="$HOME/workspace/.jdk17"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -z "${KOTLINC:-}" ]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="$(command -v kotlinc)"
  elif [ -x "$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc" ]; then
    KOTLINC="$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc"
  else
    echo "kotlinc not found: set KOTLINC or put kotlinc on PATH" >&2
    exit 1
  fi
fi
OUT="out"
mkdir -p "$OUT"

"$KOTLINC" \
  src/main/kotlin/autoconnect/adapter/*.kt \
  src/test/kotlin/autoconnect/adapter/*.kt \
  -include-runtime -d "$OUT/adapter-tests.jar"

java -jar "$OUT/adapter-tests.jar"
