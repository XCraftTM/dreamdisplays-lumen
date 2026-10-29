#!/usr/bin/env bash
# Runs the test scene in a headless client (Xvfb + Mesa software rendering) and leaves screenshots in
# run/autotest/screenshots. Needs: a flat 26.3 world in $WORLD and the test video in $VIDEO_DIR.
#
#   MC=1.21.1 scripts/autotest.sh       # another Minecraft version (default 26.3)
#   scripts/autotest.sh [shaders]      # "shaders" adds Sodium + Iris (and the pack in run/autotest/shaderpacks)
#   VIDEO_URL=https://youtu.be/... SEEK=45000 scripts/autotest.sh [shaders]   # any video: shot pairs every few seconds
#   MODE=flicker scripts/autotest.sh shaders   # pauses on white, bursts of shots per setup (all / nocolor / noblock)
#   MODE=menu scripts/autotest.sh             # a shot of Dream Displays' menu with the Lumen rows
set -euo pipefail
cd "$(dirname "$0")/.."
# the Minecraft version to test (see versions/); its flat world comes from scripts/makeworld.sh
MC=${MC:-26.3}
WORLD=${WORLD:-/home/CodeProjects/_ref/lumen-test/worlds/$MC/lumen-test}
VIDEO_DIR=${VIDEO_DIR:-/home/CodeProjects/_ref/lumen-test/video}
JAVA_HOME=${JAVA_HOME:-$(ls -d ~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2)}
export JAVA_HOME
SHADERS=false
PREFIX=lumen
if [ "${1:-}" = shaders ]; then SHADERS=true; PREFIX=lumen-shaders; fi
EXTRA=()
if [ -n "${MODE:-}" ]; then EXTRA=(-PtestMode=$MODE); PREFIX=$PREFIX-$MODE; fi
if [ -n "${VIDEO_URL:-}" ]; then
  EXTRA=(-PtestMode=video "-PtestVideo=$VIDEO_URL" "-PtestSeek=${SEEK:-0}")
  PREFIX=$PREFIX-video
fi

RUN=run/autotest/$MC
rm -rf "$RUN/saves/lumen-test" "$RUN/config/dreamdisplays" "$RUN/dreamdisplays"
mkdir -p "$RUN/saves"
cp -r "$WORLD" "$RUN/saves/lumen-test"
rm -f "$RUN/saves/lumen-test/session.lock"
# no first-start screens, small view distance (software rendering is slow)
cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
renderDistance:4
simulationDistance:5
maxFps:60
narrator:0
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
lang:${GAME_LANG:-en_us}
OPT

if [ "$SHADERS" = true ]; then
  # a shader pack from $SHADERPACK_DIR (not part of the repository), switched on in Iris' settings
  mkdir -p "$RUN/shaderpacks" "$RUN/config"
  PACK=$(ls "${SHADERPACK_DIR:-/home/CodeProjects/_ref/lumen-test/shaderpacks}" | head -1)
  cp "${SHADERPACK_DIR:-/home/CodeProjects/_ref/lumen-test/shaderpacks}/$PACK" "$RUN/shaderpacks/"
  printf 'shaderPack=%s\nenableShaders=true\n' "$PACK" > "$RUN/config/iris.properties"
fi

python3 -m http.server --bind 127.0.0.1 8899 --directory "$VIDEO_DIR" >/dev/null 2>&1 &
HTTP=$!
trap 'kill $HTTP 2>/dev/null || true' EXIT

export LIBGL_ALWAYS_SOFTWARE=1
# SDL3 finds no fitting GLX visual on Xvfb; EGL works (OpenGL is what Iris needs)
export SDL_VIDEO_FORCE_EGL=1 SDL_VIDEO_X11_FORCE_EGL=1
timeout 900 xvfb-run -a -s "-screen 0 1280x720x${XVFB_DEPTH:-24} +extension GLX" ./gradlew --console=plain -q runAutotest \
  -Pminecraft_version=$MC -Pshaders=$SHADERS -PtestPrefix=$PREFIX ${GRADLE_EXTRA:-} "${EXTRA[@]}" 2>&1 | grep -E "ddlumen|Dream Displays|dreamdisplays|ERROR|Exception|FATAL" | grep -v "^\s*at " | head -200 || true
ls -la "$RUN/screenshots" 2>/dev/null || echo "no screenshots"
