#!/usr/bin/env bash
# Compiles the testable app-module sources + the app JVM tests with the
# standalone kotlinc and runs the assertion-based test runner.
# No Gradle, no network, no extra deps (mirrors the adapter's run-tests.sh).
#
# Scope: only the app sources that are JVM-testable through their seams
# are compiled here — ReportStore (pure), ReportQueue (delegates to the
# store), BundleClient (injected fetcher/watermark/key), Ed25519 (pure),
# AppLog and Prefs (compiled against the SDK stub jar; never executed
# against real Android APIs). Android-UI/service classes are covered by
# the Gradle build, not by this harness.
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/.jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"

KOTLINC="${KOTLINC:-$HOME/workspace/tools/kotlinc/kotlinc/bin/kotlinc}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/workspace/tools/android-sdk/platforms/android-35/android.jar}"
ADAPTER_SRC="$HOME/workspace/autoconnect-core/adapter/src/main/kotlin"
OUT="tests-out"
mkdir -p "$OUT"

"$KOTLINC" \
  "$ADAPTER_SRC"/autoconnect/adapter/*.kt \
  src/main/java/autoconnect/app/Ed25519.kt \
  src/main/java/autoconnect/app/AppLog.kt \
  src/main/java/autoconnect/app/Prefs.kt \
  src/main/java/autoconnect/app/ReportStore.kt \
  src/main/java/autoconnect/app/ReportQueue.kt \
  src/main/java/autoconnect/app/BundleClient.kt \
  src/main/java/autoconnect/app/OperatorKeys.kt \
  tests/kotlin/autoconnect/app/*.kt \
  -cp "$ANDROID_JAR" \
  -include-runtime -d "$OUT/app-tests.jar"

java -jar "$OUT/app-tests.jar"

# FA/EN string-key parity: every user-visible string must exist in both
# locales with identical key sets (a missing key silently falls back to
# the default locale on the other language's devices).
python3 - <<'PYEOF'
import re, sys
def keys(path):
    with open(path, encoding="utf-8") as f:
        return set(re.findall(r'<string name="([^"]+)"', f.read()))
fa = keys("src/main/res/values/strings.xml")
en = keys("src/main/res/values-en/strings.xml")
if fa != en:
    print("STRING PARITY FAILED: FA-only=%s EN-only=%s" % (sorted(fa - en), sorted(en - fa)))
    sys.exit(1)
print("STRING PARITY PASSED: %d keys identical in FA and EN" % len(fa))
PYEOF
