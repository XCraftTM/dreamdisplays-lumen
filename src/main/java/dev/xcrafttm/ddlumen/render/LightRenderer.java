package dev.xcrafttm.ddlumen.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.xcrafttm.ddlumen.LumenConfig;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.LitSurface;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the light of the displays: the lit block faces again, with their own texture times the light that reaches
 * them, added on top of the frame. That is how a surface looks when one more light shines on it.
 */
public final class LightRenderer {
    /** Vertex colors carry light up to this much (alpha scales it), see screen_light.fsh. */
    private static final float HEADROOM = 4f;
    /** Light below this is not drawn. */
    private static final float MIN_LIGHT = 1f / 512f;
    /** Pulled toward the camera like the block breaking overlay, so it never fights with the faces below. */
    private static final DepthStencilState DEPTH = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0f, 10.0f);
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withVertexShader(id("core/screen_light"))
            .withFragmentShader(id("core/screen_light"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DEPTH)
            .buildSnippet();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder(SNIPPET)
            .withLocation(id("pipeline/screen_light"))
            .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)))
            .build();

    private static final OitPipelineSet OIT_PIPELINES = OitPipelineSet.builder("ddlumen_screen_light",
                    RenderPipeline.builder(SNIPPET).withShaderDefine("OIT_ADDITIVE"))
            .withDepthBoundsModifier(b -> b.withDepthStencilState(DEPTH))
            .withTransmittanceModifier(b -> b.withDepthStencilState(DEPTH))
            .withAccumulateModifier(b -> b.withDepthStencilState(DEPTH))
            .build();

    private static final RenderType RENDER_TYPE = RenderType.create("ddlumen_screen_light",
            RenderSetup.builder(PIPELINE)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    .setOitPipelines(OIT_PIPELINES)
                    .createRenderSetup());

    static {
        IrisCompat.assignPipeline(PIPELINE);
    }

    private LightRenderer() {
    }

    /** Collect-submits phase of the level render: queues the light of every lit display. */
    public static void submit(LevelRenderContext context) {
        ScreenLights.frame();
        LumenConfig cfg = LumenConfig.get();
        if (!cfg.enabled || !cfg.coloredLight) return;
        if (IrisCompat.isShaderPackInUse() && (!cfg.coloredLightWithShaders || IrisCompat.isRenderingShadowPass())) return;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        for (ScreenLight light : ScreenLights.all()) {
            LitSurface surface = light.surface();
            if (!light.isLit() || surface.quads == 0) continue;
            float[] zones = light.radiance();
            float[] radiance = new float[zones.length];
            for (int i = 0; i < zones.length; i++) radiance[i] = zones[i] * cfg.intensity;

            poseStack.pushPose();
            poseStack.translate(surface.originX - camera.x, surface.originY - camera.y, surface.originZ - camera.z);
            context.submitNodeCollector().submitCustomGeometry(poseStack, RENDER_TYPE,
                    (pose, buffer) -> emit(surface, radiance, pose, buffer));
            poseStack.popPose();
        }
    }

    private static void emit(LitSurface s, float[] radiance, PoseStack.Pose pose, VertexConsumer buffer) {
        int zones = s.zones;
        if (radiance.length != zones * 3) return;
        float[] p = s.positions, uv = s.uvs, w = s.weights;
        float[] rgb = new float[12];
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

            int tint = s.tints[q];
            float tr = ((tint >> 16) & 0xFF) / 255f, tg = ((tint >> 8) & 0xFF) / 255f, tb = (tint & 0xFF) / 255f;
            for (int v = 0; v < 4; v++) {
                float r = rgb[v * 3] * tr, g = rgb[v * 3 + 1] * tg, b = rgb[v * 3 + 2] * tb;
                float m = Math.max(r, Math.max(g, b));
                float a = Math.min(1f, m / HEADROOM);
                float scale = m > 0 ? 1f / (a * HEADROOM) : 0f;
                int pi = (q * 4 + v) * 3, ui = (q * 4 + v) * 2;
                buffer.addVertex(pose, p[pi], p[pi + 1], p[pi + 2])
                        .setColor(Math.min(1f, r * scale), Math.min(1f, g * scale), Math.min(1f, b * scale), a)
                        .setUv(uv[ui], uv[ui + 1])
                        .setLight(FULL_BRIGHT);
            }
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ddlumen", path);
    }
}
