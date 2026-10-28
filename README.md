# Dream Displays Lumen

An addon for [Dream Displays](https://modrinth.com/mod/dreamdisplays) (Minecraft 26.3, Fabric): a display lights up
the room in the colors of its video, like a real screen or a cinema canvas. It works with and without shaders.

- **Colored light per area of the picture.** Each frame is reduced to a grid of zone colors (4 × 3 by default).
  The left part of the picture lights the left part of the room, and so on.
- **Light like a real canvas.** Each zone is an area light: light falls off with distance and angle, and blocks
  between the display and a surface cast shadows. A white picture right in front of a wall lights it fully.
- **Without shaders:** the lit block faces are drawn a second time with their own texture times the incoming light,
  added on top. Surfaces keep their texture and take on the video's colors.
- **With shaders (Iris):** with [LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) installed, the
  display raises the block light in front of it. Every shader pack reads that light, and entities get it too. The
  colored light is also drawn while a pack is active, through an Iris program you can choose (`irisProgram`).

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | yes |
| Dream Displays | 1.10.6 or newer (client) |
| LambDynamicLights | optional, 4.13.0 or newer: block light (recommended with shaders) |
| Sodium + Iris | optional |

The mod is client-only: servers only need Dream Displays itself (plugin or server mod).

## Settings

`config/ddlumen.json` (created on first start; reload with `/ddlumen reload`):

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | master switch (`/ddlumen toggle`) |
| `coloredLight` | `true` | the colored light on surfaces |
| `intensity` | `1.0` | strength of the colored light (`/ddlumen intensity <0–16>`) |
| `range` | `16` | how far in front of a display the light reaches, in blocks |
| `zonesX`, `zonesY` | `4`, `3` | color grid taken from the picture |
| `smoothingMs` | `90` | how fast the light follows the video (higher is calmer) |
| `shadows` | `true` | blocks cast shadows |
| `maxDistance` | `96` | displays farther from the camera give no light |
| `maxFaces` | `40000` | most lit block faces per display (the brightest are kept) |
| `blockLight` | `true` | block light through LambDynamicLights |
| `blockLightStrength` | `5.0` | how bright the block light gets |
| `blockLightMax` | `13` | highest block light level |
| `coloredLightWithShaders` | `true` | colored light while a shader pack is active |
| `irisProgram` | `BEACON_BEAM` | Iris program for the colored light under shaders |

`/ddlumen status` shows the displays nearby and their lit block faces.

## How it works

1. `GpuFrameUploader` (Dream Displays) receives each decoded frame as a CPU buffer right before the upload. A mixin
   samples up to 8 × 8 pixels per zone there (I420 is converted with BT.709 like Dream Displays' own shader) and
   averages them in linear light. `DisplayFrameUploader.upload` tells which display the frame belongs to.
2. Position, size and facing come from the Dream Displays API (`DreamDisplays.getDisplays()`).
3. When a display appears or blocks near it change, a worker thread collects the block faces in front of it from
   the block models and stores, for each corner and zone, how much light arrives:
   `area · cos(emitter) · cos(receiver) / (π · distance²)`, zero when a full block is in the way.
4. Each frame the corner colors are the weighted sum of the smoothed zone colors. The faces are drawn with an
   additive pipeline over the block atlas, slightly in front of the terrain (like the block breaking overlay), and
   with Minecraft's improved transparency (OIT) as an additive OIT surface.

## Building

```bash
./gradlew build                 # build/libs/dreamdisplays-lumen-<version>.jar
./gradlew runClient             # dev client with Dream Displays and LambDynamicLights
./gradlew runClient -Pshaders=true   # ... plus Sodium and Iris
```

`scripts/autotest.sh [shaders]` runs a test scene headless (Xvfb, Mesa): a dark room with an 8 × 4 display playing
a test video (red | blue, green, white, black) and saves screenshots with and without the light to
`run/autotest/screenshots`. It needs a flat 26.3 world and the video (see the variables at the top of the script).
