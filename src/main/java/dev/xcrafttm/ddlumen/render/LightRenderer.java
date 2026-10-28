package dev.xcrafttm.ddlumen.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#else
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.LitSurface;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
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

    /** Faces drawn and displays submitted in the last frame (for /ddlumen status and tests). */
    public static volatile int lastFaces, lastSubmits;

    private LightRenderer() {
    }

    /** Collect-submits phase of the level render: queues the light of every lit display. */
    public static void submit(LevelRenderContext context) {
        ScreenLights.frame();
        LumenConfig cfg = LumenConfig.get();
        if (!cfg.enabled || !cfg.coloredLight) return;
        boolean shaders = IrisCompat.isShaderPackInUse();
        if (shaders && (!cfg.coloredLightWithShaders || IrisCompat.isRenderingShadowPass())) return;
        float intensity = shaders ? cfg.intensityWithShaders : cfg.intensity;

        lastSubmits = 0;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        for (ScreenLight light : ScreenLights.all()) {
            LitSurface surface = light.surface();
            if (!light.isLit() || surface.quads == 0) continue;
            float[] zones = light.radiance();
            float[] radiance = new float[zones.length];
            for (int i = 0; i < zones.length; i++) radiance[i] = zones[i] * intensity;

            lastSubmits++;
            poseStack.pushPose();
            poseStack.translate(surface.originX - camera.x, surface.originY - camera.y, surface.originZ - camera.z);
            if (shaders && !IrisCompat.programAssigned()) {
                PostShaderLight.queue(surface, radiance, poseStack.last().copy());
            } else {
                context.submitNodeCollector().submitCustomGeometry(poseStack, RENDER_TYPE,
                        (pose, buffer) -> emit(surface, radiance, shaders, pose, buffer));
            }
            poseStack.popPose();
        }
    }

    /**
     * [plainColor]: a shader pack draws the faces with its own program, which reads alpha as transparency (and dithers
     * it), so the light goes into the color alone, without the alpha headroom.
     */
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
                buffer.addVertex(pose, p[pi], p[pi + 1], p[pi + 2])
                        .setUv(uv[ui], uv[ui + 1])
                        .setColor(Math.min(1f, r * scale), Math.min(1f, g * scale), Math.min(1f, b * scale), a);
            }
        }
        lastFaces = drawn;
    }

    private static float shown(float light) {
        return Math.max(0f, (float) Math.sqrt(light) - MIN_SHOWN);
    }

}
