package dev.xcrafttm.ddlumen.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#elif MC >= 1.21.11
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.LitSurface;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
//#if MC >= 1.21.11
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
//#else
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//#endif
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the light of the displays: the lit block faces again, with their own texture times the light that reaches
 * them, added on top of the frame. That is how a surface looks when one more light shines on it.
 */
public final class LightRenderer {
    /** Vertex colors carry light up to this much (alpha scales it), see screen_light.fsh. */
    private static final float HEADROOM = 4f;
    /** Light below this is not drawn; it is subtracted from all light, so nothing jumps at the cut. */
    private static final float MIN_LIGHT = 1f / 16384f;
    private static final float MIN_SHOWN = (float) Math.sqrt(MIN_LIGHT);
    /** Most a face is moved toward the camera for the depth test after shader packs (blocks). */
    private static final float MAX_PULL = 1.5f;

    //#if MC >= 1.21.11
    public static final RenderPipeline PIPELINE = Pipelines.additive("pipeline/screen_light", Pipelines.REVERSED_Z);

    private static final RenderType RENDER_TYPE = RenderType.create("ddlumen_screen_light",
            RenderSetup.builder(PIPELINE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    //#if MC >= 26.3
                    .setOitPipelines(Pipelines.oit("ddlumen_screen_light"))
                    //#endif
                    .createRenderSetup());

    static {
        IrisCompat.assignPipeline(PIPELINE);
    }
    //#else
    /**
     * Before render pipelines: the game's own position-texture-color shader (texture times vertex color), added to
     * the frame. It has no headroom above the texture's own color, so the light goes into the color alone.
     */
    private static final RenderType RENDER_TYPE = LegacyRenderType.screenLight();
    //#endif

    /** Faces drawn and displays submitted in the last frame (for /ddlumen status and tests). */
    public static volatile int lastFaces, lastSubmits;

    private LightRenderer() {
    }

    private interface LitDraw {
        void draw(LitSurface surface, float[] radiance, boolean shaders);
    }

    /** Moves the lights along, then calls [draw] for each lit display with its light at the mode's strength. */
    private static void eachLit(LitDraw draw) {
        ScreenLights.frame();
        LumenConfig cfg = LumenConfig.get();
        lastSubmits = 0;
        if (!cfg.enabled || !cfg.coloredLight) return;
        boolean shaders = IrisCompat.isShaderPackInUse();
        if (shaders && (!cfg.coloredLightWithShaders || IrisCompat.isRenderingShadowPass())) return;
        float intensity = shaders ? cfg.intensityWithShaders : cfg.intensity;

        for (ScreenLight light : ScreenLights.all()) {
            LitSurface surface = light.surface();
            if (!light.isLit() || surface.quads == 0) continue;
            float[] zones = light.radiance();
            float[] radiance = new float[zones.length];
            for (int i = 0; i < zones.length; i++) radiance[i] = zones[i] * intensity;
            lastSubmits++;
            draw.draw(surface, radiance, shaders);
        }
    }

    //#if MC >= 1.21.11
    /** Collect-submits phase of the level render: queues the light of every lit display (camera-relative pose). */
    public static void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera) {
        eachLit((surface, radiance, shaders) -> {
            poseStack.pushPose();
            poseStack.translate(surface.originX - camera.x, surface.originY - camera.y, surface.originZ - camera.z);
            if (shaders && !IrisCompat.programAssigned()) {
                PostShaderLight.queue(surface, radiance, poseStack.last().copy());
            } else {
                collector.submitCustomGeometry(poseStack, RENDER_TYPE,
                        (pose, buffer) -> emit(surface, radiance, shaders, pose, buffer, 0f));
            }
            poseStack.popPose();
        });
    }
    //#else
    /**
     * After the translucent terrain: draws the light of every lit display into [consumers], camera-relative. From
     * 1.20.5 the camera's rotation is in the game's model-view matrix; before, it is in the level's [poseStack].
     */
    public static void submit(MultiBufferSource consumers, PoseStack levelPoseStack, Vec3 camera) {
        //#if MC >= 1.20.5
        PoseStack poseStack = new PoseStack();
        //#else
        PoseStack poseStack = levelPoseStack;
        //#endif
        eachLit((surface, radiance, shaders) -> {
            // a shader pack would shade the faces as a surface; there is no pass after the pack here yet
            if (shaders) return;
            poseStack.pushPose();
            poseStack.translate(surface.originX - camera.x, surface.originY - camera.y, surface.originZ - camera.z);
            emit(surface, radiance, true, poseStack.last(), consumers.getBuffer(RENDER_TYPE), 0f);
            poseStack.popPose();
        });
        if (consumers instanceof MultiBufferSource.BufferSource source) source.endBatch(RENDER_TYPE);
    }
    //#endif

    /**
     * Writes the lit faces of [s]. [depthSlack] (radians, 0 for none) moves every corner toward the camera along its
     * view ray, as far as the surface's depth changes over that angle: the faces stay on the same pixels but still
     * pass the depth test when the scene was drawn slightly shifted (shader packs jitter their geometry for TAA).
     * Only for a [pose] without rotation (camera-relative translation).
     */
    static void emit(LitSurface s, float[] radiance, boolean plainColor, PoseStack.Pose pose, VertexConsumer buffer,
                     float depthSlack) {
        int zones = s.zones;
        if (radiance.length != zones * 3) return;
        float[] p = s.positions, uv = s.uvs, w = s.weights;
        float[] rgb = new float[12];
        float[] pos = new float[12];
        // the post-shader pass only exists from 1.21.11 (JOML poses)
        //#if MC >= 1.21.11
        org.joml.Vector3f t = depthSlack > 0 ? pose.pose().getTranslation(new org.joml.Vector3f()) : null;
        float[] origin = t == null ? null : new float[]{t.x, t.y, t.z};
        //#else
        float[] origin = null;
        //#endif
        int drawn = 0;
        for (int q = 0; q < s.quads; q++) {
            float max = 0;
            for (int v = 0; v < 4; v++) {
                int wi = (q * 4 + v) * zones;
                float r = 0, g = 0, b = 0;
                for (int z = 0; z < zones; z++) {
                    float k = w[wi + z];
                    if (k == 0f) continue;
                    r += k * radiance[z * 3];
                    g += k * radiance[z * 3 + 1];
                    b += k * radiance[z * 3 + 2];
                }
                rgb[v * 3] = r;
                rgb[v * 3 + 1] = g;
                rgb[v * 3 + 2] = b;
                max = Math.max(max, Math.max(r, Math.max(g, b)));
            }
            if (max < MIN_LIGHT) continue;
            drawn++;

            System.arraycopy(p, q * 12, pos, 0, 12);
            if (origin != null) pullTowardCamera(pos, origin, depthSlack);

            int tint = s.tints[q];
            float tr = ((tint >> 16) & 0xFF) / 255f, tg = ((tint >> 8) & 0xFF) / 255f, tb = (tint & 0xFF) / 255f;
            for (int v = 0; v < 4; v++) {
                // the frame holds gamma-encoded colors: in the dark, adding E of linear light shows as about sqrt(E).
                // The strongest channel decides how much is shown and the others keep their share, so colors stay
                // as saturated as in the picture (a square root per channel would wash them out).
                float r = rgb[v * 3] * tr, g = rgb[v * 3 + 1] * tg, b = rgb[v * 3 + 2] * tb;
                float peak = Math.max(r, Math.max(g, b));
                float shownScale = peak > 0 ? shown(peak) / peak : 0f;
                r *= shownScale;
                g *= shownScale;
                b *= shownScale;
                float a = 1f, scale = 1f;
                if (!plainColor) {
                    float m = Math.max(r, Math.max(g, b));
                    a = Math.min(1f, m / HEADROOM);
                    scale = m > 0 ? 1f / (a * HEADROOM) : 0f;
                }
                int ui = (q * 4 + v) * 2;
                vertex(buffer, pose, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], uv[ui], uv[ui + 1],
                        Math.min(1f, r * scale), Math.min(1f, g * scale), Math.min(1f, b * scale), a);
            }
        }
        lastFaces = drawn;
    }

    /** Moves the four corners of a quad (local coordinates, [origin] = local origin relative to the camera). */
    private static void pullTowardCamera(float[] pos, float[] origin, float slack) {
        // the face's normal from two edges (sign does not matter)
        float ax = pos[3] - pos[0], ay = pos[4] - pos[1], az = pos[5] - pos[2];
        float bx = pos[9] - pos[0], by = pos[10] - pos[1], bz = pos[11] - pos[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl == 0) return;
        for (int v = 0; v < 4; v++) {
            float cx = pos[v * 3] + origin[0], cy = pos[v * 3 + 1] + origin[1], cz = pos[v * 3 + 2] + origin[2];
            float d = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (d < 1e-3f) continue;
            // along the ray, a shift by [slack] across a surface seen at angle a changes the depth by d * slack * tan(a)
            float cos = Math.max(0.05f, Math.abs(nx * cx + ny * cy + nz * cz) / (nl * d));
            float tan = (float) Math.sqrt(1 - cos * cos) / cos;
            float pull = Math.min(d * slack * tan + d * 0.0005f, Math.min(0.5f * d, MAX_PULL));
            float k = pull / d;
            pos[v * 3] -= cx * k;
            pos[v * 3 + 1] -= cy * k;
            pos[v * 3 + 2] -= cz * k;
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               float r, float g, float b, float a) {
        //#if MC >= 1.21
        buffer.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, a);
        //#else
        buffer.vertex(pose.pose(), x, y, z).uv(u, v).color(r, g, b, a).endVertex();
        //#endif
    }

    /** How bright [light] (linear) looks added to a dark surface. */
    public static float shown(float light) {
        return Math.max(0f, (float) Math.sqrt(light) - MIN_SHOWN);
    }
}
