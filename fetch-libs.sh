#!/usr/bin/env bash
# Downloads all third-party jars used by AssetManager into ./lib
set -u
M2="https://repo1.maven.org/maven2"
LIB="$(cd "$(dirname "$0")" && pwd)/lib"
mkdir -p "$LIB"

# groupId:artifactId:version
JARS=(
  "org.xerial:sqlite-jdbc:3.53.4.0"
  "com.google.code.gson:gson:2.13.2"
  "org.apache.commons:commons-imaging:1.0.0-alpha6"
  "commons-io:commons-io:2.21.0"
  "org.apache.commons:commons-lang3:3.17.0"
  "com.twelvemonkeys.imageio:imageio-core:3.15.2"
  "com.twelvemonkeys.imageio:imageio-metadata:3.15.2"
  "com.twelvemonkeys.common:common-lang:3.15.2"
  "com.twelvemonkeys.common:common-io:3.15.2"
  "com.twelvemonkeys.common:common-image:3.15.2"
  "com.twelvemonkeys.imageio:imageio-webp:3.15.2"
  "com.twelvemonkeys.imageio:imageio-psd:3.15.2"
  "com.twelvemonkeys.imageio:imageio-tiff:3.15.2"
  "com.twelvemonkeys.imageio:imageio-jpeg:3.15.2"
  "com.twelvemonkeys.imageio:imageio-hdr:3.15.2"
  "com.googlecode.soundlibs:jlayer:1.0.1.4"
  "com.googlecode.soundlibs:tritonus-share:0.3.7.4"
  "com.googlecode.soundlibs:mp3spi:1.9.5.4"
  "com.googlecode.soundlibs:jorbis:0.0.17.4"
  "com.googlecode.soundlibs:vorbisspi:1.0.3.3"
)

fail=0
for spec in "${JARS[@]}"; do
  IFS=':' read -r g a v <<<"$spec"
  path="${g//.//}/$a/$v/$a-$v.jar"
  out="$LIB/$a-$v.jar"
  if [ -s "$out" ]; then
    echo "  ok (cached)  $a-$v.jar"
    continue
  fi
  if curl -fsSL --retry 3 --retry-delay 2 --max-time 180 -o "$out" "$M2/$path"; then
    echo "  downloaded   $a-$v.jar ($(du -h "$out" | cut -f1))"
  else
    echo "  !! FAILED    $a-$v.jar  ($M2/$path)"
    rm -f "$out"
    fail=$((fail+1))
  fi
done

echo
if [ "$fail" -gt 0 ]; then
  echo "$fail jar(s) failed to download."
  exit 1
fi
echo "All jars present in $LIB"
ls -1 "$LIB" | wc -l | xargs echo "jar count:"
