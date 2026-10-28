package dev.xcrafttm.ddlumen.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
//#if MC >= 26.1
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
//#else
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
//#endif
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

import java.util.ArrayList;
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
    static void collect(Object models, BlockState state, BlockPos pos, RandomSource random, Predicate<Direction> culled,
                        Sink sink) {
        float[] corners = new float[12], uvs = new float[8];
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
