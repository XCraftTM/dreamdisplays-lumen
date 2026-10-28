#!/usr/bin/env bash
# Creates the flat test world for one Minecraft version with its vanilla server (from Loom's cache: build that
# version once first) in $OUT/<version>/lumen-test.
#
#   scripts/makeworld.sh 1.21.1
set -euo pipefail
MC=${1:?usage: makeworld.sh <minecraft version>}
OUT=${OUT:-/home/CodeProjects/_ref/lumen-test/worlds}
JAVA=${JAVA:-$(ls -d ~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2)/bin/java}
JAR=$(ls ~/.gradle/caches/fabric-loom/"$MC"/minecraft-server.jar)
DIR="$OUT/$MC"
[ -d "$DIR/lumen-test" ] && { echo "$DIR/lumen-test exists"; exit 0; }
mkdir -p "$DIR"
cd "$DIR"
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
level-name=lumen-test
level-type=minecraft\:flat
generate-structures=false
server-ip=127.0.0.1
server-port=25691
online-mode=false
view-distance=4
simulation-distance=4
PROPS
(until grep -q "Done (" logs/latest.log 2>/dev/null; do sleep 2; done; echo stop) \
  | timeout 300 "$JAVA" -Xmx1G -jar "$JAR" --nogui > gen.out 2>&1 || true
rm -f lumen-test/session.lock
ls "$DIR/lumen-test" >/dev/null && echo "world: $DIR/lumen-test"
