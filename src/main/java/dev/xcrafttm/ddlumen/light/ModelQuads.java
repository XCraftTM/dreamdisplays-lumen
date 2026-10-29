package dev.xcrafttm.ddlumen.light;

import net.minecraft.client.Minecraft;
//#if MC >= 1.21.5
import net.minecraft.client.model.geom.builders.UVPair;
//#endif
import net.minecraft.client.multiplayer.ClientLevel;
//#if MC >= 26.1
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
//#elif MC >= 1.21.5
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
//#else
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
//#endif
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//#if MC >= 1.19
import net.minecraft.util.RandomSource;
//#else
import java.util.Random;
//#endif
import net.minecraft.world.level.block.state.BlockState;
//#if MC >= 1.21.5
import org.joml.Vector3fc;
//#endif

//#if MC >= 1.21.5
import java.util.ArrayList;
//#endif
import java.util.List;
import java.util.function.Predicate;

/**
 * The block model faces of a block, in a form that is the same for every Minecraft version: corner positions
 * (model space), atlas UVs, tint layer and facing. Translucent faces (glass, ice) are left out.
 */
final class ModelQuads {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Receives one face: corners x, y, z (12 values), u, v (8 values), tint layer (-1 = none), facing. */
    interface Sink {
        void quad(float[] corners, float[] uvs, int tintIndex, Direction facing);
    }

    private ModelQuads() {
    }

    /** The game's block models; read on the client thread, used from the worker. */
    static Object models() {
        //#if MC >= 26.1
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        //#else
        return Minecraft.getInstance().getModelManager().getBlockModelShaper();
        //#endif
    }

    /**
     * Calls [sink] for each face of [state]'s model at [pos]. Faces that face a direction are only kept when
     * [culled] allows that direction (the neighbor does not hide it).
     */
    //#if MC >= 1.19
    static void collect(Object models, BlockState state, BlockPos pos, RandomSource random, Predicate<Direction> culled,
                        Sink sink) {
    //#else
    static void collect(Object models, BlockState state, BlockPos pos, Random random, Predicate<Direction> culled,
                        Sink sink) {
    //#endif
        float[] corners = new float[12], uvs = new float[8];
        //#if MC >= 1.21.5
        random.setSeed(state.getSeed(pos));
        //#if MC >= 26.1
        BlockStateModel model = ((BlockStateModelSet) models).get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
        //#else
        if (ItemBlockRenderTypes.getChunkRenderType(state) == ChunkSectionLayer.TRANSLUCENT) return;
        BlockStateModel model = ((BlockModelShaper) models).getBlockModel(state);
        List<BlockModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        for (BlockModelPart part : parts) {
        //#endif
            for (int d = 0; d <= DIRECTIONS.length; d++) {
                Direction cull = d < DIRECTIONS.length ? DIRECTIONS[d] : null;
                List<BakedQuad> quads = part.getQuads(cull);
                if (quads.isEmpty() || (cull != null && !culled.test(cull))) continue;
                for (BakedQuad quad : quads) {
                    //#if MC >= 26.1
                    if (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) continue;
                    int tint = quad.materialInfo().tintIndex();
                    //#else
                    int tint = quad.tintIndex();
                    //#endif
                    for (int v = 0; v < 4; v++) {
                        Vector3fc p = quad.position(v);
                        corners[v * 3] = p.x();
                        corners[v * 3 + 1] = p.y();
                        corners[v * 3 + 2] = p.z();
                        long uv = quad.packedUV(v);
                        uvs[v * 2] = UVPair.unpackU(uv);
                        uvs[v * 2 + 1] = UVPair.unpackV(uv);
                    }
                    sink.quad(corners, uvs, tint, quad.direction());
                }
            }
        }
        //#else
        if (ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.translucent()) return;
        BakedModel model = ((BlockModelShaper) models).getBlockModel(state);
        for (int d = 0; d <= DIRECTIONS.length; d++) {
            Direction cull = d < DIRECTIONS.length ? DIRECTIONS[d] : null;
            random.setSeed(state.getSeed(pos));
            List<BakedQuad> quads = model.getQuads(state, cull, random);
            if (quads.isEmpty() || (cull != null && !culled.test(cull))) continue;
            for (BakedQuad quad : quads) {
                // the block vertex format: x, y, z (floats), color, u, v (floats), light, normal: 8 ints a corner
                int[] data = quad.getVertices();
                int stride = data.length / 4;
                for (int v = 0; v < 4; v++) {
                    int o = v * stride;
                    corners[v * 3] = Float.intBitsToFloat(data[o]);
                    corners[v * 3 + 1] = Float.intBitsToFloat(data[o + 1]);
                    corners[v * 3 + 2] = Float.intBitsToFloat(data[o + 2]);
                    uvs[v * 2] = Float.intBitsToFloat(data[o + 4]);
                    uvs[v * 2 + 1] = Float.intBitsToFloat(data[o + 5]);
                }
                sink.quad(corners, uvs, quad.getTintIndex(), quad.getDirection());
            }
        }
        //#endif
    }

    /** The color a tinted face (grass, leaves, water) has at [pos], 0xRRGGBB. */
    static int tint(BlockState state, ClientLevel level, BlockPos pos, int tintIndex) {
        //#if MC >= 26.1
        BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
        return source == null ? 0xFFFFFF : source.colorInWorld(state, level, pos) & 0xFFFFFF;
        //#else
        int color = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, tintIndex);
        return color == -1 ? 0xFFFFFF : color & 0xFFFFFF;
        //#endif
    }
}
