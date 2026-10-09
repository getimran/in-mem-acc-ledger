#!/usr/bin/env bash
# Builds the ledger and replays the brief's event stream, printing one report per day.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]] && ! java -version >/dev/null 2>&1; then
    for candidate in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@21; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

mvn -q compile
"$JAVA" -cp target/classes ledger.Replay
