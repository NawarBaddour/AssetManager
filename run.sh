#!/usr/bin/env bash
# Runs AssetManager. Pass asset paths/folders to import on startup, e.g.
#   ./run.sh ~/games/mygame/assets
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-11-openjdk}"
JAVA="$JAVA_HOME/bin/java"

# Rebuild when the build is missing OR any source is newer than the last build.
# Without this you can silently run a stale bin/ after editing sources.
# Resources count too: the browser UI is served from src/main/resources.
STAMP="$ROOT/bin/com/assetmanager/AssetManagerApp.class"
needs_build=0
if [ ! -d "$ROOT/bin/com/assetmanager" ] || [ ! -f "$STAMP" ]; then
  needs_build=1
elif [ -n "$(find "$ROOT/src" \( -name '*.java' -o -path '*/resources/*' \) -newer "$STAMP" -print -quit 2>/dev/null)" ]; then
  needs_build=1
  echo "Sources changed since the last build; rebuilding."
fi

if [ "$needs_build" -eq 1 ]; then
  "$ROOT/build.sh"
fi

CP="$ROOT/bin"
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$ROOT/lib" -name '*.jar')

exec "$JAVA" -Xmx2g \
  -cp "$CP" com.assetmanager.AssetManagerApp "$@"
