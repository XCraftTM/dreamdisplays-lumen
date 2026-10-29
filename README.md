# Dream Displays Lumen

An addon for [Dream Displays](https://modrinth.com/mod/dreamdisplays) (Minecraft 26.3, Fabric): a display lights up
the room in the colors of its video, like a real screen or a cinema canvas. It works with and without shaders.

- **Colored light per area of the picture.** Each frame is reduced to a grid of zone colors (4 × 3 by default).
  The left part of the picture lights the left part of the room, and so on.
- **Light like a real canvas.** Each zone is an area light: light falls off with distance and angle, and blocks
  between the display and a surface cast shadows. A white picture right in front of a wall lights it fully.
- **Without shaders:** the lit block faces are drawn a second time with their own texture times the incoming light,
  added on top. Surfaces keep their texture and take on the video's colors.
- **With shaders (Iris):** shader packs render deferred, so whatever is drawn into their buffers counts as surface
  color and their lighting darkens it again. The colored light is therefore added onto the finished picture right
  after the pack's final pass, depth-tested against the scene. With
  [LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) installed, the display also raises the block light
  in front of it while a pack is active: the pack's own lighting brightens, and entities get it too.

## Requirements

| | |
|---|---|
| Minecraft | 1.18.2, 1.19.4, 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, 26.3 (one jar each) |
| Loader | Fabric (or Quilt), Fabric Loader 0.19.5 or newer |
| Fabric API | yes |
| Dream Displays | 1.10.6 or newer (client) |
| LambDynamicLights | optional, from 1.20.1 on: block light under shaders |
| Sodium + Iris | optional |

The mod is client-only: servers only need Dream Displays itself (plugin or server mod).

What differs between versions:

| | 26.3 | 26.1.2 – 26.2 | 1.21.11 | 1.18.2 – 1.21.1 |
|---|---|---|---|---|
| Colored light without shaders | yes | yes | yes | yes (no brightness above the texture's own color) |
| Colored light with Iris | after the pack's final pass | same | same | off for now |
| Block light (LambDynamicLights) | yes | yes | yes | 1.20.1 and 1.21.1; none before 1.20 |
| Room light row in the display menu | yes | yes | yes | yes |

Tested with the test scene (color video) on every version without shaders, and on 26.3 with Iris and
Complementary Reimagined.

## Settings

**In the display menu:** Dream Displays' menu of each display gets a **Room light** row below *Brightness*:
how strongly this display lights the room, 0–200 % (0 % turns its light off), with a reset button like the other
rows. It is stored per display and only for you, like volume and brightness (`displayLight` in the file below).

**Everything else** is in `config/ddlumen.json` (created on first start; reload with `/ddlumen reload`):

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | master switch (`/ddlumen toggle`) |
| `coloredLight` | `true` | the colored light on surfaces |
| `intensity` | `4.0` | strength of the colored light (`/ddlumen intensity <0–16>` sets the one of the mode in use) |
| `range` | `20` | how far in front of a display the light reaches, in blocks |
| `zonesX`, `zonesY` | `4`, `3` | color grid taken from the picture |
| `smoothingMs` | `90` | how fast the light follows the video (higher is calmer) |
| `shadows` | `true` | blocks cast shadows |
| `maxDistance` | `96` | displays farther from the camera give no light |
| `maxFaces` | `40000` | most lit block faces per display (the brightest are kept) |
| `blockLight` | `shaders` | block light through LambDynamicLights: `shaders` (only while a shader pack is active), `always` or `off`. Vanilla block light is warm-tinted and pulls the colors toward orange, so it is off without shaders by default |
| `blockLightStrength` | `5.0` | how bright the block light gets |
| `blockLightMax` | `13` | highest block light level |
| `coloredLightWithShaders` | `true` | colored light while a shader pack is active |
| `intensityWithShaders` | `1.5` | strength of the colored light while a shader pack is active |
| `displayLight` | `{}` | light strength per display id, set in the display menu |
| `irisProgram` | `NONE` | `NONE` draws the light after the pack's final pass (recommended). An Iris program name (`TEXTURED`, ...) draws it inside the pack instead, where the pack shades it like a surface |

`/ddlumen status` shows the displays nearby and their lit block faces.

## How it works

1. `GpuFrameUploader` (Dream Displays) receives each decoded frame as a CPU buffer right before the upload. A mixin
   samples up to 8 × 8 pixels per zone there (I420 is converted with BT.709 like Dream Displays' own shader) and
   averages them in linear light. `DisplayFrameUploader.upload` tells which display the frame belongs to.
2. Position, size and facing come from the Dream Displays API (`DreamDisplays.getDisplays()`).
3. When a display appears or blocks near it change, a worker thread collects the block faces in front of it from
   the block models and stores, for each corner and zone, how much light arrives:
   `area · cos(emitter) · cos(receiver) / (π · distance²)`, zero when a full block is in the way.
4. Each frame the corner colors are the weighted sum of the smoothed zone colors, shown as about `sqrt(E)` (the
   frame holds gamma-encoded colors). The faces are drawn with an additive pipeline over the block atlas, lifted
   0.003 blocks off the terrain (a depth bias hid them on the Vulkan backend), and with Minecraft's improved
   transparency (OIT) as an additive OIT surface. Toward the end of the range the light fades out smoothly.
5. The **Room light** row is added to `DisplayMenu.settingsRows()` with Dream Displays' own slider and reset button;
   the menu layout gets one row more height (`MenuLayout.compute`).
6. With a shader pack the same faces are drawn in a render pass of their own on the finished picture, right after
   Iris' final pass (`IrisRenderingPipeline.finalizeLevelRendering`).

## Building

One source tree for every Minecraft version, through a comment preprocessor (`//#if MC >= 1.21.11` … `//#endif`,
see `preprocess.gradle`). `versions/<mc>.properties` pins each version's dependencies.

```bash
./gradlew build                                # 26.3: build/libs/dreamdisplays-lumen-<version>+26.3.jar
./gradlew build -Pminecraft_version=1.21.1     # another version
scripts/build-all.sh                           # every version into dist/
./gradlew runClient             # dev client with Dream Displays and LambDynamicLights
./gradlew runClient -Pshaders=true   # ... plus Sodium and Iris
```

`MC=<version> scripts/autotest.sh [shaders]` runs a test scene headless (Xvfb, Mesa): a dark room with an 8 × 4 display playing
a test video (red | blue, green, white, black) and saves screenshots with and without the light to
`run/autotest/screenshots`. It needs a flat world of that version (`scripts/makeworld.sh <version>`), the video and (for `shaders`) a shader pack zip; see the
variables at the top of the script. `VIDEO_URL=... SEEK=<ms>` plays any video instead and takes six pairs of shots.
Without a GPU Minecraft falls back to Vulkan (lavapipe); with `shaders` SDL is told to use EGL, which gives OpenGL.
