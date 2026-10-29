#!/usr/bin/env bash
# Builds the mod for every Minecraft version in versions/ and collects the jars in dist/.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME=${JAVA_HOME:-$(ls -d ~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2)}
export JAVA_HOME
mkdir -p dist
rm -f dist/*.jar
for f in versions/*.properties; do
  mc=$(basename "$f" .properties)
  echo "== $mc"
  ./gradlew --console=plain -q build -Pminecraft_version="$mc"
  cp "build/libs/dreamdisplays-lumen-$(grep '^mod_version=' gradle.properties | cut -d= -f2)+$mc.jar" dist/
done
ls -la dist
