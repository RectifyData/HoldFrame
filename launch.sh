#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo 'Java 17 or later is required. Please install OpenJDK.' >&2
  exit 1
fi
if [ ! -f dist/HoldFrame.jar ]; then
  echo 'App JAR is missing. Run ./build.sh first.' >&2
  exit 1
fi
exec java -jar dist/HoldFrame.jar
