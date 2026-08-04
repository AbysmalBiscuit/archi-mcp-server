#!/usr/bin/env bash
#
# M0-1 — Headless build + test harness for arch-mcp-server.
#
# ONE command that, on a machine with a local Archi 5.7 install:
#   1. compiles BOTH projects FROM SOURCE with javac against the assembled Archi/Eclipse/lib
#      classpath (proven cleaner than the Eclipse PDE build, which error-stubs EMF refs),
#   2. auto-discovers the test class list by scanning the tests source tree (no hard-coded list —
#      kills the stale 49-class AllPluginTestsRunner array, repo-audit T2),
#   3. runs the headless-safe classes via a JUnit4 runner that asserts testsRun > 0 per class
#      (the silent-stale guard AllPluginTestsRunner lacked) and emits surefire JUnit XML,
#   4. exits non-zero if any (non-quarantined) test fails or any class contributes zero tests.
#
# Generalizes the proven run-pins.sh classpath assembly. Designed so m0-2 (GitHub Actions) can call
# this identical script on Linux — all locations are env vars, no macOS-only assumptions in the
# default code path (no -XstartOnFirstThread unless --swt is requested).
#
# Usage:
#   tools/run-tests.sh                 # compile + run all headless-safe classes (scan ∖ manifest)
#   tools/run-tests.sh ClassA ClassB   # compile + run ONLY these fully-qualified classes
#   tools/run-tests.sh --swt [Class…]  # add -XstartOnFirstThread (macOS SWT classes); run subset
#
# Env vars (with macOS dev-box defaults):
#   ARCHI_HOME    Archi's Eclipse dir   (default /Applications/Archi.app/Contents/Eclipse)
#   ECLIPSE_HOME  Eclipse IDE dir       (default /Applications/Eclipse.app/Contents/Eclipse)  [JUnit/Hamcrest]
#   M2_REPO       Maven local repo      (default ~/.m2/repository)                            [Mockito stack]
#   BUILD_DIR     compile output dir    (default <repo>/build/test-harness)  — NOT the Eclipse bin/
#   RESULTS_DIR   JUnit XML output dir  (default <repo>/build/test-results)  — surefire convention
#
set -uo pipefail

# ---- locate repo (the script lives in <repo>/tools/) ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
PROD="$REPO/net.vheerden.archi.mcp"
TESTS="$REPO/net.vheerden.archi.mcp.tests"

ARCHI_HOME="${ARCHI_HOME:-/Applications/Archi.app/Contents/Eclipse}"
ECLIPSE_HOME="${ECLIPSE_HOME:-/Applications/Eclipse.app/Contents/Eclipse}"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
BUILD_DIR="${BUILD_DIR:-$REPO/build/test-harness}"
RESULTS_DIR="${RESULTS_DIR:-$REPO/build/test-results}"

# Under MSYS/Git Bash these are typically handed in Windows form (C:/Program Files/Archi). Fold
# them to POSIX so `find`/`ls`/`[ -d ]` work and the assembled classpath is uniform; native_cp
# converts back at the javac/java boundary.
if command -v cygpath >/dev/null; then
  ARCHI_HOME="$(cygpath -u "$ARCHI_HOME")"
  ECLIPSE_HOME="$(cygpath -u "$ECLIPSE_HOME")"
  M2_REPO="$(cygpath -u "$M2_REPO")"
fi

EXCLUDED_MANIFEST="$SCRIPT_DIR/osgi-excluded-tests.txt"
KNOWN_FAILING="$SCRIPT_DIR/known-failing-tests.txt"
RUNNER_CLASS="net.vheerden.archi.mcp.harness.HeadlessTestRunner"

die() { echo "ERROR: $*" >&2; exit 2; }

# ---- parse args: --swt flag + optional explicit class names ----
SWT=0
EXPLICIT_CLASSES=()
for arg in "$@"; do
  case "$arg" in
    --swt) SWT=1 ;;
    -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    -*) die "unknown flag: $arg (supported: --swt)" ;;
    *) EXPLICIT_CLASSES+=("$arg") ;;
  esac
done

# ---- validate required paths (name WHICH path is missing — QW-6 quality bar) ----
ARCHI_PLUGINS="$ARCHI_HOME/plugins"
ECLIPSE_PLUGINS="$ECLIPSE_HOME/plugins"
[ -d "$ARCHI_PLUGINS" ]   || die "ARCHI_HOME/plugins not found: $ARCHI_PLUGINS (set ARCHI_HOME to your Archi 5.7 Eclipse dir)"
[ -d "$ECLIPSE_PLUGINS" ] || die "ECLIPSE_HOME/plugins not found: $ECLIPSE_PLUGINS (needed for JUnit + Hamcrest; set ECLIPSE_HOME)"
[ -d "$M2_REPO" ]         || die "M2_REPO not found: $M2_REPO (needed for the Mockito stack; set M2_REPO)"
[ -d "$PROD/src" ]        || die "production source not found: $PROD/src"
[ -d "$TESTS/src" ]       || die "test source not found: $TESTS/src"
command -v javac >/dev/null || die "javac not on PATH (need a JDK 21+)"
command -v java  >/dev/null || die "java not on PATH (need a JDK 21+)"
# The exclusion manifest is a required checked-in artifact in scan mode (the single exclusion SoT).
# Validate it here so a missing/unreadable manifest fails with a named-path message rather than
# surfacing later as confusing per-class testsRun>0 violations on the OSGi classes.
if [ "${#EXPLICIT_CLASSES[@]}" -eq 0 ]; then
  [ -f "$EXCLUDED_MANIFEST" ] || die "excluded-class manifest not found: $EXCLUDED_MANIFEST (the exclusion source of truth — it should be checked in)"
fi

# JUnit + Hamcrest from the Eclipse install (version-robust glob).
JUNIT="$(ls "$ECLIPSE_PLUGINS"/org.junit_4*.jar 2>/dev/null | head -1)"
HAM="$(ls "$ECLIPSE_PLUGINS"/org.hamcrest_*.jar 2>/dev/null | head -1)"
[ -n "$JUNIT" ] || die "JUnit 4 jar not found under $ECLIPSE_PLUGINS (org.junit_4*.jar)"
[ -n "$HAM" ]   || die "Hamcrest jar not found under $ECLIPSE_PLUGINS (org.hamcrest_*.jar)"

# Vendored libs (both projects) + all Archi plugin jars + Mockito stack.
LIBS="$(find "$PROD/lib" "$TESTS/lib" -name '*.jar' 2>/dev/null | tr '\n' ':')"
ARCHIJARS="$(find "$ARCHI_PLUGINS" -name '*.jar' 2>/dev/null | tr '\n' ':')"
MOCKITO_CORE="$M2_REPO/org/mockito/mockito-core/5.5.0/mockito-core-5.5.0.jar"
MOCK="$MOCKITO_CORE:$M2_REPO/net/bytebuddy/byte-buddy/1.14.6/byte-buddy-1.14.6.jar:$M2_REPO/net/bytebuddy/byte-buddy-agent/1.14.6/byte-buddy-agent-1.14.6.jar:$M2_REPO/org/objenesis/objenesis/3.3/objenesis-3.3.jar"
# Validate the Mockito stack up front (it is version-pinned, not globbed) — otherwise a missing/
# mismatched version silently drops Mockito from the classpath and every @Mock test fails late
# with ClassNotFoundException.
[ -f "$MOCKITO_CORE" ] || die "Mockito not found: $MOCKITO_CORE (populate ~/.m2 via a Maven build, or set M2_REPO; tests using @Mock need it)"

OUT_PROD="$BUILD_DIR/prod"
OUT_TESTS="$BUILD_DIR/tests"
COMPILE_CP="$JUNIT:$HAM:$LIBS:$MOCK:$ARCHIJARS"
JAVAC_FLAGS=(--release 21 -encoding UTF-8 -nowarn)

# javac/java are native Windows binaries under MSYS/Git Bash: they cannot resolve the POSIX
# paths `find` emits into an @argfile, and MSYS leaves a `:`-joined classpath untouched once it
# contains a drive letter. Both helpers below are no-ops wherever cygpath is absent (Linux CI,
# macOS). `-m` yields drive-letter paths with FORWARD slashes; `-w` would emit backslashes, which
# javac treats as escapes inside an @argfile.
native_paths() {
  if command -v cygpath >/dev/null; then
    cygpath -m -f - < "$1" > "$1.native" && mv "$1.native" "$1"
  fi
}

native_cp() {
  if command -v cygpath >/dev/null; then cygpath -p -m "$1"; else printf '%s' "$1"; fi
}

native_path() {
  if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

echo "== M0-1 headless build + test harness =="
echo "   repo:        $REPO"
echo "   ARCHI_HOME:  $ARCHI_HOME"
echo "   build dir:   $BUILD_DIR"
echo "   results dir: $RESULTS_DIR"

# ---- compile production source FROM SOURCE (never touches the Eclipse bin/) ----
echo "-- compiling production source ($(find "$PROD/src" -name '*.java' | wc -l | tr -d ' ') files) ..."
rm -rf "$OUT_PROD"; mkdir -p "$OUT_PROD"
find "$PROD/src" -name '*.java' > "$BUILD_DIR/prod-srcs.txt"
native_paths "$BUILD_DIR/prod-srcs.txt"
javac "${JAVAC_FLAGS[@]}" -cp "$(native_cp "$COMPILE_CP")" -d "$OUT_PROD" "@$BUILD_DIR/prod-srcs.txt" \
  || die "production compile failed (see javac output above)"
# Mirror Eclipse's bin.includes so classpath resource loads resolve (resources/, img/).
[ -d "$PROD/resources" ] && cp -R "$PROD/resources" "$OUT_PROD/"
[ -d "$PROD/img" ]       && cp -R "$PROD/img" "$OUT_PROD/"
# Package prod output (classes + resources) as a jar for the RUN classpath, so resource lookups
# behave exactly like the shipped OSGi bundle — e.g. getResourceAsStream on a DIRECTORY returns
# null, not an exploded-dir file listing (ResourceHandler.loadResourceFile("") must be null). The
# COMPILE classpaths can stay exploded; only the runtime needs jar semantics.
PROD_JAR="$BUILD_DIR/prod.jar"
( cd "$OUT_PROD" && jar cf "$PROD_JAR" . ) || die "failed to package prod.jar"
# Strip directory entries so getResourceAsStream on a directory path returns null (as in a real
# deployed bundle), not a 0-byte/dir-listing stream. `jar` always writes dir entries; zip -d drops
# them. File entries (the resources themselves) are unaffected.
if command -v zip >/dev/null; then
  zip -d "$PROD_JAR" '*/' >/dev/null 2>&1 || true
elif PY="$(command -v python3 || command -v python)" && [ -n "$PY" ]; then
  "$PY" - "$(native_path "$PROD_JAR")" <<'STRIP_DIR_ENTRIES' || die "failed to strip jar directory entries"
import sys, zipfile
src = sys.argv[1]
with zipfile.ZipFile(src) as z:
    entries = [(i, z.read(i.filename)) for i in z.infolist() if not i.filename.endswith('/')]
with zipfile.ZipFile(src, 'w', zipfile.ZIP_DEFLATED) as z:
    for info, data in entries:
        z.writestr(info, data)
STRIP_DIR_ENTRIES
else
  # Not silent: without zip the jar keeps directory entries, so getResourceAsStream on a directory
  # path returns a 0-byte stream instead of null — a class like ResourceHandlerTest will then go
  # RED (loud), not silently wrong. m0-2 CI images must install zip (apt-get install zip).
  echo "WARN: 'zip' not found — cannot strip jar directory entries; directory-path resource lookups" >&2
  echo "      may diverge from a deployed bundle. Install zip (m0-2: apt-get install zip)." >&2
fi

# ---- compile test source FROM SOURCE (against fresh prod output) ----
echo "-- compiling test source ($(find "$TESTS/src" -name '*.java' | wc -l | tr -d ' ') files) ..."
rm -rf "$OUT_TESTS"; mkdir -p "$OUT_TESTS"
find "$TESTS/src" -name '*.java' > "$BUILD_DIR/test-srcs.txt"
native_paths "$BUILD_DIR/test-srcs.txt"
javac "${JAVAC_FLAGS[@]}" -cp "$(native_cp "$OUT_PROD:$COMPILE_CP")" -d "$OUT_TESTS" "@$BUILD_DIR/test-srcs.txt" \
  || die "test compile failed (see javac output above)"
[ -d "$TESTS/testdata" ] && cp -R "$TESTS/testdata" "$OUT_TESTS/" 2>/dev/null || true

RUN_CP="$OUT_TESTS:$PROD_JAR:$COMPILE_CP"

# ---- build the class list: explicit args win; else scan ∖ excluded manifest ----
if [ "${#EXPLICIT_CLASSES[@]}" -gt 0 ]; then
  CLASSES=("${EXPLICIT_CLASSES[@]}")
  echo "-- running ${#CLASSES[@]} explicitly-requested class(es)"
else
  # Scan every *Test.java -> FQCN (the 6 non-test helpers don't match *Test.java, so they're
  # excluded automatically). Then subtract the excluded manifest (data, not code — AC-3/AC-4).
  EXCLUDE_PATTERN="$(sed -e 's/#.*//' -e 's/[[:space:]]//g' "$EXCLUDED_MANIFEST" 2>/dev/null | grep . || true)"
  CLASSES=()  # while-read append, not mapfile — portable to bash 3.2 (macOS default)
  while IFS= read -r line; do
    [ -n "$line" ] && CLASSES+=("$line")
  done < <(
    find "$TESTS/src" -name '*Test.java' \
      | sed -e "s#^$TESTS/src/##" -e 's#/#.#g' -e 's#\.java$##' \
      | { if [ -n "$EXCLUDE_PATTERN" ]; then grep -vxF "$EXCLUDE_PATTERN"; else cat; fi; } \
      | sort
  )
  scanned="$(find "$TESTS/src" -name '*Test.java' | wc -l | tr -d ' ')"
  echo "-- scanned $scanned *Test.java; excluded $((scanned - ${#CLASSES[@]})) per manifest; running ${#CLASSES[@]}"
fi
[ "${#CLASSES[@]}" -gt 0 ] || die "no test classes to run"

# ---- SWT escape hatch (AC-9): -XstartOnFirstThread on macOS only ----
JVM_FLAGS=()
if [ "$SWT" -eq 1 ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    JVM_FLAGS+=(-XstartOnFirstThread)
    echo "-- --swt: added -XstartOnFirstThread (macOS)"
  else
    echo "-- --swt: no-op on $(uname -s) (use xvfb for a headless display)"
  fi
fi

mkdir -p "$RESULTS_DIR"
echo "-- running tests (JUnit XML -> $RESULTS_DIR) ..."
LOG="$BUILD_DIR/run.log"
# The classpath plus 200+ class names exceeds the Windows 32 KiB command-line limit, so the
# classpath, runner and class list go through a JDK @argfile instead of the argv.
JAVA_ARGS="$BUILD_DIR/java-args.txt"
{
  printf -- '-cp\n"%s"\n' "$(native_cp "$RUN_CP")"
  printf '%s\n' "$RUNNER_CLASS"
  printf '%s\n' "${CLASSES[@]}"
} > "$JAVA_ARGS"
# ${arr[@]+"${arr[@]}"} = expand to nothing when empty (bash 3.2 + `set -u` would otherwise abort).
java ${JVM_FLAGS[@]+"${JVM_FLAGS[@]}"} \
  -Dharness.xmlDir="$RESULTS_DIR" \
  -Dharness.knownFailingFile="$KNOWN_FAILING" \
  "@$JAVA_ARGS" > "$LOG" 2>&1
rc=$?

# Preserve the run-pins.sh SLF4J stdout-hygiene; show the per-class results + summary block.
grep -vE 'SLF4J' "$LOG"

exit "$rc"
