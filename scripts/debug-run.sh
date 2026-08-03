#!/usr/bin/env bash
# Run Insula so that a native crash actually leaves a report.
#
# libjlibtorrent replaces the JVM's SIGSEGV and SIGBUS handlers with its own (confirmed with
# -Xcheck:jni: "Handler was modified! Expected: javaSignalHandler in libjvm.so"). That is why a
# BitTorrent crash exits 139 with no hs_err_pid file to read. libjsig is the JVM's own signal
# chaining library: preloading it keeps HotSpot's handler in the chain, so the next crash writes a
# real report instead of vanishing.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
JSIG="$JAVA_HOME/lib/libjsig.so"
[ -f "$JSIG" ] || { echo "no libjsig at $JSIG" >&2; exit 1; }
echo "preloading $JSIG; crash reports will land in $PWD/hs_err_pid*.log"
LD_PRELOAD="$JSIG" ./mvnw javafx:run \
  -Djavafx.options="-XX:ErrorFile=$PWD/hs_err_pid%p.log" "$@"
