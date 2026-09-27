#!/usr/bin/env bash
# Writes a synthetic asset tree, so the app can be tried out without a real
# game to point it at. Then serves it and prints the URL.
#
#   ./devtools/seed-demo-assets.sh              # into ./demo-assets, and serve
#   ./devtools/seed-demo-assets.sh /some/where  # elsewhere
#   ./devtools/seed-demo-assets.sh --no-serve   # just write the files
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-11-openjdk}"
JAVA="$JAVA_HOME/bin/java"

SERVE=1
DEST="$ROOT/demo-assets"
for arg in "$@"; do
  case "$arg" in
    --no-serve) SERVE=0 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) DEST="$arg" ;;
  esac
done

if [ ! -f "$ROOT/bin/com/assetmanager/devtools/DemoAssets.class" ] \
   || [ -n "$(find "$ROOT/src" -name '*.java' -newer \
        "$ROOT/bin/com/assetmanager/devtools/DemoAssets.class" -print -quit 2>/dev/null)" ]; then
  "$ROOT/build.sh"
fi

CP="$ROOT/bin"
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$ROOT/lib" -name '*.jar')

mkdir -p "$DEST"
"$JAVA" -cp "$CP" com.assetmanager.devtools.DemoAssets "$DEST"

[ "$SERVE" -eq 1 ] || exit 0

# A throwaway home, so seeding the demo tree does not touch a real library.
WORK="$(mktemp -d /tmp/assetmanager-demo.XXXXXX)"
echo
echo "Serving on a throwaway library ($WORK). Ctrl+C to stop."
setsid "$JAVA" -Duser.home="$WORK" -cp "$CP" com.assetmanager.AssetManagerApp \
  --no-open "$DEST" > "$WORK/server.log" 2>&1 < /dev/null &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT INT TERM

for _ in $(seq 1 60); do
  URL=$(grep -o 'http://127.0.0.1:[0-9]*' "$WORK/server.log" 2>/dev/null | head -1 || true)
  [ -n "$URL" ] && break
  sleep 1
done
if [ -z "$URL" ]; then
  echo "the server did not start:" >&2
  tail -20 "$WORK/server.log" >&2
  exit 1
fi
wait "$SERVER_PID"
