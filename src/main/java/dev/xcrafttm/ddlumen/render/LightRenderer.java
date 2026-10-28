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
    private static final float HEADROOM = 2f;
    /** Light below this is not drawn; it is subtracted from all light, so nothing jumps at the cut. */
    private static final float MIN_LIGHT = 1f / 16384f;
    private static final float MIN_SHOWN = (float) Math.sqrt(MIN_LIGHT);

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
                        (pose, buffer) -> emit(surface, radiance, shaders, pose, buffer));
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
            emit(surface, radiance, true, poseStack.last(), consumers.getBuffer(RENDER_TYPE));
            poseStack.popPose();
        });
        if (consumers instanceof MultiBufferSource.BufferSource source) source.endBatch(RENDER_TYPE);
    }
    //#endif

    static void emit(LitSurface s, float[] radiance, boolean plainColor, PoseStack.Pose pose, VertexConsumer buffer) {
        int zones = s.zones;
        if (radiance.length != zones * 3) return;
        float[] p = s.positions, uv = s.uvs, w = s.weights;
        float[] rgb = new float[12];
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

            int tint = s.tints[q];
            float tr = ((tint >> 16) & 0xFF) / 255f, tg = ((tint >> 8) & 0xFF) / 255f, tb = (tint & 0xFF) / 255f;
            for (int v = 0; v < 4; v++) {
                // the frame holds gamma-encoded colors: in the dark, adding E of linear light shows as about sqrt(E)
                float r = shown(rgb[v * 3]) * tr, g = shown(rgb[v * 3 + 1]) * tg, b = shown(rgb[v * 3 + 2]) * tb;
                float a = 1f, scale = 1f;
                if (!plainColor) {
                    float m = Math.max(r, Math.max(g, b));
                    a = Math.min(1f, m / HEADROOM);
                    scale = m > 0 ? 1f / (a * HEADROOM) : 0f;
                }
                int pi = (q * 4 + v) * 3, ui = (q * 4 + v) * 2;
                vertex(buffer, pose, p[pi], p[pi + 1], p[pi + 2], uv[ui], uv[ui + 1],
                        Math.min(1f, r * scale), Math.min(1f, g * scale), Math.min(1f, b * scale), a);
            }
        }
        lastFaces = drawn;
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               float r, float g, float b, float a) {
        //#if MC >= 1.21
        buffer.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, a);
        //#else
        buffer.vertex(pose.pose(), x, y, z).uv(u, v).color(r, g, b, a).endVertex();
        //#endif
    }

    private static float shown(float light) {
        return Math.max(0f, (float) Math.sqrt(light) - MIN_SHOWN);
    }
}
