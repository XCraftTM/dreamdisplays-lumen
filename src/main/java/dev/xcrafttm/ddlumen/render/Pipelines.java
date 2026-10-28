package dev.xcrafttm.ddlumen.render;

//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
//#elif MC >= 26.2
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.BindGroupLayouts;
//#elif MC >= 26.1
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.VertexFormat;
//#else
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.VertexFormat;
//#endif
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The render pipelines of the light, for each rendering API generation. */
final class Pipelines {
    /** Whether this version's depth buffer is reversed (near = 1). */
    //#if MC >= 26.2
    static final boolean REVERSED_Z = true;
    //#else
    static final boolean REVERSED_Z = false;
    //#endif

    private Pipelines() {
    }

    /**
     * The light pass: block atlas times vertex color, added to the frame, depth-tested against the scene without
     * writing depth. [nearerWins] picks the depth test of a reversed depth buffer.
     */
    static RenderPipeline additive(String location, boolean nearerWins) {
        //#if MC >= 26.2
        return RenderPipeline.builder(snippet(depth(nearerWins)))
                .withLocation(id(location))
                .withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)))
                .build();
        //#elif MC >= 26.1
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(id(location))
                .withVertexShader(id("core/screen_light"))
                .withFragmentShader(id("core/screen_light"))
                .withSampler("Sampler0")
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withDepthStencilState(depth(nearerWins))
                .withColorTargetState(new ColorTargetState(new BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE)))
                .build();
        //#else
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(id(location))
                .withVertexShader(id("core/screen_light"))
                .withFragmentShader(id("core/screen_light"))
                .withSampler("Sampler0")
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withDepthTestFunction(nearerWins ? DepthTestFunction.GREATER_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withDepthWrite(false)
                .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE))
                .build();
        //#endif
    }

    /**
     * No depth bias: on the Vulkan backend any bias hid the faces entirely. The faces are lifted off the blocks a
     * little instead (see SurfaceBuilder.LIFT).
     */
    //#if MC >= 26.1
    private static DepthStencilState depth(boolean nearerWins) {
        return new DepthStencilState(nearerWins ? CompareOp.GREATER_THAN_OR_EQUAL : CompareOp.LESS_THAN_OR_EQUAL, false);
    }
    //#endif

    //#if MC >= 26.2
    private static RenderPipeline.Snippet snippet(DepthStencilState depth) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withVertexShader(id("core/screen_light"))
                .withFragmentShader(id("core/screen_light"))
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(depth)
                .buildSnippet();
    }
    //#endif

    //#if MC >= 26.3
    /** The same pass for Minecraft's improved transparency (an additive surface there). */
    static OitPipelineSet oit(String suffix) {
        DepthStencilState depth = depth(true);
        return OitPipelineSet.builder(suffix, RenderPipeline.builder(snippet(depth)).withShaderDefine("OIT_ADDITIVE"))
                .withDepthBoundsModifier(b -> b.withDepthStencilState(depth))
                .withTransmittanceModifier(b -> b.withDepthStencilState(depth))
                .withAccumulateModifier(b -> b.withDepthStencilState(depth))
                .build();
    }
    //#endif

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ddlumen", path);
    }
}
