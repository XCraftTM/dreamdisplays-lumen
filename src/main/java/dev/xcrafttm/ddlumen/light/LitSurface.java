package dev.xcrafttm.ddlumen.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block faces in front of one display that its light reaches, with the light each corner gets from each zone
 * of the picture (distance, angles and shadows already applied). Only the zone colors change per frame.
 * <p>
 * Positions are relative to {@link #originX} / {@link #originY} / {@link #originZ}, so floats keep their precision.
 */
public final class LitSurface {
    public final int originX, originY, originZ;
    public final int zones;
    public final int quads;
    /** x, y, z per corner. */
    public final float[] positions;
    /** u, v per corner (block atlas). */
    public final float[] uvs;
    /** Light per corner and zone. */
    public final float[] weights;
    /** Tint per face (0xRRGGBB), white unless the block is tinted (grass, leaves, water-logged ...). */
    public final int[] tints;
    /** Tint layer per face, -1 when untinted, and where to look it up. */
    final int[] tintIndex;
    final long[] tintPos;
    final BlockState[] tintState;

    LitSurface(int originX, int originY, int originZ, int zones, int quads, float[] positions, float[] uvs,
               float[] weights, int[] tintIndex, long[] tintPos, BlockState[] tintState) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.zones = zones;
        this.quads = quads;
        this.positions = positions;
        this.uvs = uvs;
        this.weights = weights;
        this.tintIndex = tintIndex;
        this.tintPos = tintPos;
        this.tintState = tintState;
        this.tints = new int[quads];
        java.util.Arrays.fill(this.tints, 0xFFFFFF);
    }

    static final LitSurface EMPTY = new LitSurface(0, 0, 0, 1, 0, new float[0], new float[0], new float[0],
            new int[0], new long[0], new BlockState[0]);

    boolean needsTints() {
        for (int t : tintIndex) if (t >= 0) return true;
        return false;
    }

    BlockPos tintPos(int quad) {
        return BlockPos.of(tintPos[quad]);
    }
}
