package dev.xcrafttm.ddlumen.render;

//#if MC >= 1.21.11

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
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#elif MC >= 26.2
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
//#else
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Vector3f;
import org.joml.Vector4f;
//#endif
import dev.xcrafttm.ddlumen.DdLumen;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.LitSurface;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

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
    /** Shift of the scene the depth test tolerates, and room for a wider field of view (sprinting, speed). */
    private static final float JITTER_PIXELS = 1f, FOV_MARGIN = 1.3f;

    private record Draw(LitSurface surface, float[] radiance, PoseStack.Pose pose) {
    }

    private PostShaderLight() {
    }

    /** Collect phase: remembers what to draw and the camera of this frame. */
    static void queue(LitSurface surface, float[] radiance, PoseStack.Pose pose) {
        if (PENDING.isEmpty()) {
            //#if MC >= 26.2
            modelView = RenderSystem.getModelViewMatrixCopy();
            //#else
            modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            //#endif
            projection = RenderSystem.getProjectionMatrixBuffer();
            projectionType = RenderSystem.getProjectionType();
        }
        PENDING.add(new Draw(surface, radiance, pose));
    }

    /** Passes drawn after the shader pack (for /ddlumen status). */
    public static volatile long passes;

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
        //#if MC >= 26.2
        var target = mc.gameRenderer.mainRenderTarget();
        //#else
        var target = mc.getMainRenderTarget();
        //#endif
        if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;

        if (bytes == null) bytes = new ByteBufferBuilder(1 << 20);
        //#if MC >= 26.2
        BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        //#else
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        //#endif
        // packs jitter the scene by up to about a pixel (TAA); the light has to pass the depth test on every frame
        float slack = JITTER_PIXELS * 2f * (float) Math.tan(Math.toRadians(mc.options.fov().get() * FOV_MARGIN) / 2) / target.height;
        for (Draw draw : PENDING) LightRenderer.emit(draw.surface(), draw.radiance(), false, draw.pose(), builder, slack);
        try (MeshData mesh = builder.build()) {
            if (mesh == null) return;
            int indexCount = mesh.drawState().indexCount();
            //#if MC >= 26.2
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            //#else
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            //#endif
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            //#if MC >= 26.2
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Matrix4f());
            //#else
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                    .writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            //#endif
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projection, projectionType);
            try (GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "ddlumen light", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                 //#if MC >= 26.2
                 RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "ddlumen light after shaders",
                         target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                 //#else
                 RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "ddlumen light after shaders",
                         target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                 //#endif
                //#if MC >= 26.3
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline()));
                //#else
                pass.setPipeline(pipeline());
                //#endif
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                //#if MC >= 26.3
                pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
                //#else
                pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
                //#endif
                //#if MC >= 26.2
                pass.setVertexBuffer(0, vertices.slice());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
                passes++;
                //#else
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, indexCount, 1);
                passes++;
                //#endif
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }
    }

    /** Iris may turn reversed Z off while a pack renders; the depth test has to match. */
    private static RenderPipeline pipeline() {
        boolean reversed = IrisCompat.isReverseZ() && Pipelines.REVERSED_Z;
        if (reversed && reversedZ == null) reversedZ = Pipelines.additive("pipeline/screen_light_after_shaders_reversed", true);
        if (!reversed && forwardZ == null) forwardZ = Pipelines.additive("pipeline/screen_light_after_shaders_forward", false);
        return reversed ? reversedZ : forwardZ;
    }
}
//#else
/** Before render pipelines the light under shader packs is not drawn yet. */
public final class PostShaderLight {
    public static volatile long passes;

    private PostShaderLight() {
    }

    public static void draw() {
    }
}
//#endif
