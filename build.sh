#!/usr/bin/env bash
# Portable Java 17 desktop build for Linux, macOS, and Windows environments with Bash.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes dist
javac --release 17 -encoding UTF-8 -d build/classes \
  core/src/main/java/dev/rectifydata/holdframe/core/*.java \
  desktop/src/main/java/dev/rectifydata/holdframe/windows/*.java
jar --create --file dist/HoldFrame.jar \
    --main-class dev.rectifydata.holdframe.windows.HoldFrame -C build/classes .
echo 'Created dist/HoldFrame.jar (Windows and Linux, Java 17+)'
