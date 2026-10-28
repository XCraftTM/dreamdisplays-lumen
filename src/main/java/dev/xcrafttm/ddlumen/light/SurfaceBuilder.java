package dev.xcrafttm.ddlumen.light;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the block faces a display lights and works out how much light each of their corners gets from each zone.
 * {@link #snapshot} copies the blocks on the client thread; {@link #build} then runs on a worker thread.
 */
final class SurfaceBuilder {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Bigger regions are not lit (a display can be huge). */
    private static final int MAX_VOLUME = 400_000;
    /** How far the lit faces float above the blocks, so they win the depth test without a depth bias. */
    private static final double LIFT = 0.003;
    /** Corners that get less than this from all zones together are dark. */
    private static final float MIN_WEIGHT = 2e-4f;

    private SurfaceBuilder() {
    }

    /** The blocks of the region plus one block around it (for face culling). */
    record Snapshot(int ox, int oy, int oz, int sx, int sy, int sz, BlockState[] states, BlockPos min, BlockPos max) {
        BlockState get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return Blocks.AIR.defaultBlockState();
            return states[(ly * sz + lz) * sx + lx];
        }
    }

    static Snapshot snapshot(ClientLevel level, BlockPos min, BlockPos max) {
        int ox = min.getX() - 1, oy = min.getY() - 1, oz = min.getZ() - 1;
        int sx = max.getX() - min.getX() + 3, sy = max.getY() - min.getY() + 3, sz = max.getZ() - min.getZ() + 3;
        if ((long) sx * sy * sz > MAX_VOLUME) return null;
        BlockState[] states = new BlockState[sx * sy * sz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    states[(y * sz + z) * sx + x] = level.getBlockState(pos.set(ox + x, oy + y, oz + z));
                }
            }
        }
        return new Snapshot(ox, oy, oz, sx, sy, sz, states, min, max);
    }

    static LitSurface build(Snapshot s, ScreenGeometry g, int zonesX, int zonesY, int range, boolean shadows,
                            int maxFaces, BlockStateModelSet models) {
        int zones = zonesX * zonesY;
        double[] centers = g.zoneCenters(zonesX, zonesY);
        double zoneArea = g.area() / zones;

        FloatArrayList positions = new FloatArrayList();
        FloatArrayList uvs = new FloatArrayList();
        FloatArrayList weights = new FloatArrayList();
        FloatArrayList importance = new FloatArrayList();
        IntArrayList tintIndex = new IntArrayList();
        LongArrayList tintPos = new LongArrayList();
        List<BlockState> tintState = new ArrayList<>();

        RandomSource random = RandomSource.create();
        List<BlockStateModelPart> parts = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        float[] w = new float[4 * zones];
        double[] corner = new double[12];

        for (int y = s.min().getY(); y <= s.max().getY(); y++) {
            for (int z = s.min().getZ(); z <= s.max().getZ(); z++) {
                for (int x = s.min().getX(); x <= s.max().getX(); x++) {
                    BlockState state = s.get(x, y, z);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                    if (!g.inFront(x + 0.5, y + 0.5, z + 0.5)) continue;
                    pos.set(x, y, z);
                    BlockStateModel model = models.get(state);
                    random.setSeed(state.getSeed(pos));
                    parts.clear();
                    model.collectParts(random, parts);
                    Vec3 offset = state.getOffset(pos);

                    for (BlockStateModelPart part : parts) {
                        for (int d = 0; d <= DIRECTIONS.length; d++) {
                            Direction cull = d < DIRECTIONS.length ? DIRECTIONS[d] : null;
                            List<BakedQuad> quads = part.getQuads(cull);
                            if (quads.isEmpty()) continue;
                            if (cull != null) {
                                BlockState neighbor = s.get(x + cull.getStepX(), y + cull.getStepY(), z + cull.getStepZ());
                                if (!Block.shouldRenderFace(state, neighbor, cull)) continue;
                            }
                            for (BakedQuad quad : quads) {
                                if (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) continue;
                                for (int v = 0; v < 4; v++) {
                                    Vector3fc p = quad.position(v);
                                    corner[v * 3] = x + offset.x + p.x();
                                    corner[v * 3 + 1] = y + offset.y + p.y();
                                    corner[v * 3 + 2] = z + offset.z + p.z();
                                }
                                double[] n = normal(corner, quad.direction());
                                float sum = 0;
                                for (int v = 0; v < 4; v++) {
                                    sum += cornerWeights(s, g, centers, zones, zoneArea, range, shadows, corner, v, n, w, v * zones);
                                }
                                if (sum < MIN_WEIGHT) continue;

                                for (int v = 0; v < 4; v++) {
                                    positions.add((float) (corner[v * 3] + n[0] * LIFT - s.ox()));
                                    positions.add((float) (corner[v * 3 + 1] + n[1] * LIFT - s.oy()));
                                    positions.add((float) (corner[v * 3 + 2] + n[2] * LIFT - s.oz()));
                                    long uv = quad.packedUV(v);
                                    uvs.add(UVPair.unpackU(uv));
                                    uvs.add(UVPair.unpackV(uv));
                                }
                                weights.addElements(weights.size(), w, 0, 4 * zones);
                                importance.add(sum);
                                int tint = quad.materialInfo().tintIndex();
                                tintIndex.add(tint);
                                tintPos.add(tint >= 0 ? pos.asLong() : 0L);
                                tintState.add(tint >= 0 ? state : null);
                            }
                        }
                    }
                }
            }
        }

        int count = importance.size();
        int[] keep = null;
        if (count > maxFaces) {
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            float[] imp = importance.elements();
            Arrays.sort(order, (a, b) -> Float.compare(imp[b], imp[a]));
            keep = new int[maxFaces];
            for (int i = 0; i < maxFaces; i++) keep[i] = order[i];
            Arrays.sort(keep);
            count = maxFaces;
        }
        return pack(s, zones, count, keep, positions, uvs, weights, tintIndex, tintPos, tintState);
    }

    private static LitSurface pack(Snapshot s, int zones, int count, int[] keep, FloatArrayList positions,
                                   FloatArrayList uvs, FloatArrayList weights, IntArrayList tintIndex,
                                   LongArrayList tintPos, List<BlockState> tintState) {
        if (keep == null) {
            return new LitSurface(s.ox(), s.oy(), s.oz(), zones, count, positions.toFloatArray(), uvs.toFloatArray(),
                    weights.toFloatArray(), tintIndex.toIntArray(), tintPos.toLongArray(), tintState.toArray(new BlockState[0]));
        }
        float[] p = new float[count * 12], uv = new float[count * 8], wt = new float[count * 4 * zones];
        int[] ti = new int[count];
        long[] tp = new long[count];
        BlockState[] ts = new BlockState[count];
        float[] srcP = positions.elements(), srcUv = uvs.elements(), srcW = weights.elements();
        for (int i = 0; i < count; i++) {
            int q = keep[i];
            System.arraycopy(srcP, q * 12, p, i * 12, 12);
            System.arraycopy(srcUv, q * 8, uv, i * 8, 8);
            System.arraycopy(srcW, q * 4 * zones, wt, i * 4 * zones, 4 * zones);
            ti[i] = tintIndex.getInt(q);
            tp[i] = tintPos.getLong(q);
            ts[i] = tintState.get(q);
        }
        return new LitSurface(s.ox(), s.oy(), s.oz(), zones, count, p, uv, wt, ti, tp, ts);
    }

    /** The face's outward normal: from its corners, turned to agree with the face direction. */
    private static double[] normal(double[] c, Direction facing) {
        double e1x = c[6] - c[0], e1y = c[7] - c[1], e1z = c[8] - c[2];
        double e2x = c[9] - c[3], e2y = c[10] - c[4], e2z = c[11] - c[5];
        double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9) return new double[]{facing.getStepX(), facing.getStepY(), facing.getStepZ()};
        nx /= len;
        ny /= len;
        nz /= len;
        if (nx * facing.getStepX() + ny * facing.getStepY() + nz * facing.getStepZ() < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return new double[]{nx, ny, nz};
    }

    /**
     * Light corner [v] gets from each zone, written to [out] from [at]: each zone is a small Lambertian emitter,
     * {@code area * cos(emitter) * cos(receiver) / (pi * distance^2)}, so a white picture filling the whole view of a
     * surface gives it 1 (its full color). Returns the sum.
     */
    private static float cornerWeights(Snapshot s, ScreenGeometry g, double[] centers, int zones, double zoneArea,
                                       int range, boolean shadows, double[] c, int v, double[] n, float[] out, int at) {
        // nudge the corner onto the face, off its edges, so rays do not start inside the neighbors
        double qx = (c[0] + c[3] + c[6] + c[9]) * 0.25, qy = (c[1] + c[4] + c[7] + c[10]) * 0.25, qz = (c[2] + c[5] + c[8] + c[11]) * 0.25;
        double px = c[v * 3] + (qx - c[v * 3]) * 0.02 + n[0] * 0.02;
        double py = c[v * 3 + 1] + (qy - c[v * 3 + 1]) * 0.02 + n[1] * 0.02;
        double pz = c[v * 3 + 2] + (qz - c[v * 3 + 2]) * 0.02 + n[2] * 0.02;
        // fade out toward the end of the range, so the light has no visible edge there
        double fade = smoothFade(g.distanceToPicture(px, py, pz), range);
        float sum = 0;
        for (int z = 0; z < zones; z++) {
            if (fade <= 0) {
                out[at + z] = 0;
                continue;
            }
            double tx = centers[z * 3], ty = centers[z * 3 + 1], tz = centers[z * 3 + 2];
            double dx = px - tx, dy = py - ty, dz = pz - tz;
            double cosEmit = dx * g.nx() + dy * g.ny() + dz * g.nz();
            double cosRecv = -(dx * n[0] + dy * n[1] + dz * n[2]);
            if (cosEmit <= 0 || cosRecv <= 0) {
                out[at + z] = 0;
                continue;
            }
            double dist2 = dx * dx + dy * dy + dz * dz;
            // the zone's own size keeps the light finite right in front of it
            double weight = fade * zoneArea * (cosEmit / Math.sqrt(dist2)) * (cosRecv / Math.sqrt(dist2))
                    / (Math.PI * (dist2 + zoneArea * 0.25));
            if (weight > 1e-6 && shadows && !visible(s, px, py, pz, tx, ty, tz)) weight = 0;
            out[at + z] = (float) weight;
            sum += (float) weight;
        }
        return sum;
    }

    /** 1 up to 60 % of the range, then smoothly down to 0 at the range. */
    private static double smoothFade(double distance, int range) {
        double t = Math.clamp((range - distance) / (range * 0.4), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** True when no full block lies between the two points (voxel walk). */
    private static boolean visible(Snapshot s, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? (x + 1 - x0) : (x0 - x)) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? (y + 1 - y0) : (y0 - y)) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? (z + 1 - z0) : (z0 - z)) * tDeltaZ;
        for (int i = 0; i < 256; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1) return true;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1) return true;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1) return true;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (s.get(x, y, z).isSolidRender()) return false;
        }
        return true;
    }
}
