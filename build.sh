#!/usr/bin/env bash

# Compiles AssetManager into ./bin
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

# Find a JDK 11+ compiler. The project targets Java 11 language level.
JAVAC=""
for home in "${JAVA_HOME:-}" /usr/lib/jvm/java-11-openjdk /usr/lib/jvm/java-17-openjdk \
            /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-26-openjdk; do
  [ -n "$home" ] && [ -x "$home/bin/javac" ] && { JAVAC="$home/bin/javac"; JDK="$home"; break; }
done
if [ -z "$JAVAC" ]; then
  JAVAC="$(command -v javac || true)"
  [ -n "$JAVAC" ] || { echo "No javac found. Install a JDK 11 or newer." >&2; exit 1; }
  JDK="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
fi
echo "Compiler: $JAVAC"
"$JAVAC" -version 2>&1 | sed 's/^/  /'

CP="$(find "$ROOT/lib" -name '*.jar' -printf '%p:' 2>/dev/null || true)"
if [ -z "$CP" ]; then
  echo "Build failed: no jars in ./lib" >&2
  if [ -d "$ROOT/lib" ]; then
    echo "  ./lib exists but is empty." >&2
  else
    echo "  ./lib does not exist." >&2
  fi
  echo "  Run ./fetch-libs.sh to download them (~16 MB, once)." >&2
  exit 1
fi

rm -rf "$ROOT/bin"
mkdir -p "$ROOT/bin"

SRC_LIST="$(mktemp)"
trap 'rm -f "$SRC_LIST"' EXIT
find "$ROOT/src/main/java" -name '*.java' > "$SRC_LIST"

echo "Compiling $(wc -l < "$SRC_LIST") source files..."
"$JAVAC" -encoding UTF-8 -Xlint:-options --release 11 \
         -cp "$CP" -d "$ROOT/bin" "@$SRC_LIST"

if [ -d "$ROOT/src/main/resources" ]; then
  cp -r "$ROOT/src/main/resources/." "$ROOT/bin/"
fi

rm -f "$ROOT/assetmanager.jar"
if "$JDK/bin/jar" --create --file "$ROOT/assetmanager.jar" \
     --main-class com.assetmanager.AssetManagerApp -C "$ROOT/bin" . 2>/dev/null; then
  echo "Build OK -> $ROOT/bin  (jar: $ROOT/assetmanager.jar)"
else
  echo "Build OK -> $ROOT/bin  (warning: could not package the jar; run with -cp instead)" >&2
fi
