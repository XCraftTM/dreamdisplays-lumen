package dev.xcrafttm.ddlumen.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
//#if MC >= 26.3
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#else
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
//#endif
import dev.xcrafttm.ddlumen.DdLumen;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.LitSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The colored light while a shader pack is active. Packs render deferred: what is drawn into their buffers is surface
 * color that their lighting darkens again. So the light is added onto the finished picture right after the pack's
 * final pass (see IrisFinalPassMixin), depth-tested against the scene, the same way as without shaders.
 */
public final class PostShaderLight {
    private static final List<Draw> PENDING = new ArrayList<>();
    private static Matrix4f modelView;
    private static GpuBufferSlice projection;
    private static ProjectionType projectionType;
    private static ByteBufferBuilder bytes;
    private static boolean failed;

    private static RenderPipeline reversedZ, forwardZ;

    private record Draw(LitSurface surface, float[] radiance, PoseStack.Pose pose) {
    }

    private PostShaderLight() {
    }

    /** Collect phase: remembers what to draw and the camera of this frame. */
    static void queue(LitSurface surface, float[] radiance, PoseStack.Pose pose) {
        if (PENDING.isEmpty()) {
            modelView = RenderSystem.getModelViewMatrixCopy();
            projection = RenderSystem.getProjectionMatrixBuffer();
            projectionType = RenderSystem.getProjectionType();
        }
        PENDING.add(new Draw(surface, radiance, pose));
    }

    /** Right after the shader pack's final pass. */
    public static void draw() {
        if (PENDING.isEmpty()) return;
        try {
            if (!failed && projection != null) drawPending();
        } catch (RuntimeException e) {
            failed = true;
            DdLumen.LOGGER.error("Drawing the light after the shader pack failed; it stays off under shaders", e);
        } finally {
            PENDING.clear();
        }
    }

    private static void drawPending() {
        Minecraft mc = Minecraft.getInstance();
        var target = mc.gameRenderer.mainRenderTarget();
        if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;

        if (bytes == null) bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Draw draw : PENDING) LightRenderer.emit(draw.surface(), draw.radiance(), false, draw.pose(), builder);
        try (MeshData mesh = builder.build()) {
            if (mesh == null) return;
            int indexCount = mesh.drawState().indexCount();
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projection, projectionType);
            try (GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "ddlumen light", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                 RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "ddlumen light after shaders",
                         target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                //#if MC >= 26.3
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline()));
                //#else
                pass.setPipeline(pipeline());
                //#endif
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView, new Matrix4f()));
                //#if MC >= 26.3
                pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
                //#else
                pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
                //#endif
                pass.setVertexBuffer(0, vertices.slice());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }
    }

    /** Iris may turn reversed Z off while a pack renders; the depth test has to match. */
    private static RenderPipeline pipeline() {
        boolean reversed = IrisCompat.isReverseZ();
        if (reversed && reversedZ == null) reversedZ = build(CompareOp.GREATER_THAN_OR_EQUAL, "reversed");
        if (!reversed && forwardZ == null) forwardZ = build(CompareOp.LESS_THAN_OR_EQUAL, "forward");
        return reversed ? reversedZ : forwardZ;
    }

    private static RenderPipeline build(CompareOp depthTest, String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("ddlumen", "pipeline/screen_light_after_shaders_" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("ddlumen", "core/screen_light"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("ddlumen", "core/screen_light"))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(new DepthStencilState(depthTest, false))
                .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)))
                .build();
    }
}
