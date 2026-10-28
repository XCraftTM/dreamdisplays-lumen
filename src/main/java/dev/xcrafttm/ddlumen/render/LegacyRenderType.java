package dev.xcrafttm.ddlumen.render;

//#if MC < 1.21.11
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * The light's render type before render pipelines: the game's own position-texture-color shader (texture times
 * vertex color), added to the frame. Extends RenderType only to reach its protected building blocks (older
 * versions do not open them up); it is never created.
 */
abstract class LegacyRenderType extends RenderType {
    private LegacyRenderType() {
        super("ddlumen_unused", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 0, false, false, () -> {
        }, () -> {
        });
    }

    static RenderType screenLight() {
        return RenderType.create("ddlumen_screen_light",
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1 << 18, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new ShaderStateShard(GameRenderer::getPositionTexColorShader))
                        .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, true))
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(CULL)
                        .createCompositeState(false));
    }
}
//#endif
