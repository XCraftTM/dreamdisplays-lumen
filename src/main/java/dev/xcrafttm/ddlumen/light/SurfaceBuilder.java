package dev.xcrafttm.ddlumen.light;

import dev.xcrafttm.ddlumen.Clamp;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//#if MC >= 1.19
import net.minecraft.util.RandomSource;
//#else
import java.util.Random;
//#endif
//#if MC >= 1.21.2
import net.minecraft.world.level.block.Block;
//#else
import net.minecraft.world.level.EmptyBlockGetter;
//#endif
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the block faces a display lights and works out how much light each of their corners gets from each zone.
 * {@link #snapshot} copies the blocks on the client thread; {@link #build} then runs on a worker thread.
 */
final class SurfaceBuilder {
    /** Bigger regions are not lit (a display can be huge). */
    private static final int MAX_VOLUME = 400_000;
    /** How far the lit faces float above the blocks, so they win the depth test without a depth bias. */
    private static final double LIFT = 0.003;
    /** Corners that get less than this from all zones together are dark. */
    private static final float MIN_WEIGHT = 2e-4f;
    /** Pieces per zone and axis close to the picture, where the zone colors blend. */
    private static final int SUB = 4;
    /** In zone diagonals: closer, the zones blend; further, each lights as a whole; mixed in between. */
    private static final double BLEND_NEAR = 1.5, BLEND_FAR = 3;

    private SurfaceBuilder() {
    }

    /** Where shadow rays look the blocks up: a snapshot (worker thread) or the level itself (entity light). */
    interface BlockAt {
        BlockState get(int x, int y, int z);
    }

    /** The blocks of the region plus one block around it (for face culling). */
    record Snapshot(int ox, int oy, int oz, int sx, int sy, int sz, BlockState[] states, BlockPos min, BlockPos max)
            implements BlockAt {
        @Override
        public BlockState get(int x, int y, int z) {
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
                            int maxFaces, Object models) {
        int zones = zonesX * zonesY;
        double[] centers = g.zoneCenters(zonesX, zonesY);
        double[] rects = g.zoneCorners(zonesX, zonesY);
        Scratch scratch = new Scratch(zones);

        FloatArrayList positions = new FloatArrayList();
        FloatArrayList uvs = new FloatArrayList();
        FloatArrayList weights = new FloatArrayList();
        FloatArrayList importance = new FloatArrayList();
        IntArrayList tintIndex = new IntArrayList();
        LongArrayList tintPos = new LongArrayList();
        List<BlockState> tintState = new ArrayList<>();

        //#if MC >= 1.19
        RandomSource random = RandomSource.create();
        //#else
        Random random = new Random();
        //#endif
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
                    Vec3 offset = offset(state, pos);
                    int bx = x, by = y, bz = z;
                    long packedPos = pos.asLong();
                    ModelQuads.collect(models, state, pos, random,
                            cull -> faceVisible(state, s.get(bx + cull.getStepX(), by + cull.getStepY(), bz + cull.getStepZ()), cull),
                            (quadCorners, quadUvs, tint, facing) -> {
                                for (int i = 0; i < 12; i += 3) {
                                    corner[i] = bx + offset.x + quadCorners[i];
                                    corner[i + 1] = by + offset.y + quadCorners[i + 1];
                                    corner[i + 2] = bz + offset.z + quadCorners[i + 2];
                                }
                                double[] n = normal(corner, facing);
                                float sum = 0;
                                for (int v = 0; v < 4; v++) {
                                    sum += cornerWeights(s, g, centers, rects, zonesX, zonesY, range, shadows, corner, v, n, w, v * zones, scratch);
                                }
                                if (sum < MIN_WEIGHT) return;

                                for (int v = 0; v < 4; v++) {
                                    positions.add((float) (corner[v * 3] + n[0] * LIFT - s.ox()));
                                    positions.add((float) (corner[v * 3 + 1] + n[1] * LIFT - s.oy()));
                                    positions.add((float) (corner[v * 3 + 2] + n[2] * LIFT - s.oz()));
                                    uvs.add(quadUvs[v * 2]);
                                    uvs.add(quadUvs[v * 2 + 1]);
                                }
                                weights.addElements(weights.size(), w, 0, 4 * zones);
                                importance.add(sum);
                                tintIndex.add(tint);
                                tintPos.add(tint >= 0 ? packedPos : 0L);
                                tintState.add(tint >= 0 ? state : null);
                            });
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

    /** Whether a face of [state] towards [neighbor] is drawn by the game. */
    private static boolean faceVisible(BlockState state, BlockState neighbor, Direction direction) {
        //#if MC >= 1.21.2
        return Block.shouldRenderFace(state, neighbor, direction);
        //#else
        // the game asks the world here; without one, a full opaque neighbor hides the face
        return !solid(neighbor);
        //#endif
    }

    /** A full opaque block: light does not pass it. */
    private static boolean solid(BlockState state) {
        //#if MC >= 1.21.2
        return state.isSolidRender();
        //#else
        return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        //#endif
    }

    /** The random model offset of flowers, grass and the like. */
    private static Vec3 offset(BlockState state, BlockPos pos) {
        //#if MC >= 1.21.2
        return state.getOffset(pos);
        //#else
        return state.getOffset(EmptyBlockGetter.INSTANCE, pos);
        //#endif
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

    /** Per-point working memory of {@link #pointLight}, reused. */
    static final class Scratch {
        final double[] quad = new double[12];
        final double[] light;
        /** Shadow test per zone for the current corner: 0 not done, 1 lit, 2 in shadow. */
        final byte[] vis;

        Scratch(int zones) {
            light = new double[zones];
            vis = new byte[zones];
        }
    }

    /**
     * Light corner [v] gets from each zone, written to [out] from [at]: the picture is a Lambertian surface whose color
     * blends linearly between the zones' middles (not tiles of one color each), and the corner gets the form factor
     * of each part (the share of the corner's view it covers, cosine-weighted), so a white picture filling the whole
     * view of a surface gives it 1 (its full color). Close to the picture each zone is cut into pieces, each shared
     * between the zones around it, so the light right in front of the picture blends like the picture does; further
     * away a zone lights as a whole. Shadows are tested toward the zone's middle. Returns the sum.
     */
    private static float cornerWeights(BlockAt s, ScreenGeometry g, double[] centers, double[] rects, int zonesX,
                                       int zonesY, int range, boolean shadows, double[] c, int v, double[] n,
                                       float[] out, int at, Scratch scratch) {
        int zones = zonesX * zonesY;
        // nudge the corner onto the face, off its edges, so rays do not start inside the neighbors
        double qx = (c[0] + c[3] + c[6] + c[9]) * 0.25, qy = (c[1] + c[4] + c[7] + c[10]) * 0.25, qz = (c[2] + c[5] + c[8] + c[11]) * 0.25;
        double px = c[v * 3] + (qx - c[v * 3]) * 0.02 + n[0] * 0.02;
        double py = c[v * 3 + 1] + (qy - c[v * 3 + 1]) * 0.02 + n[1] * 0.02;
        double pz = c[v * 3 + 2] + (qz - c[v * 3 + 2]) * 0.02 + n[2] * 0.02;
        pointLight(s, g, centers, rects, zonesX, zonesY, range, shadows, px, py, pz, n, scratch);
        double[] light = scratch.light;
        float sum = 0;
        for (int z = 0; z < zones; z++) {
            float weight = (float) light[z];
            out[at + z] = weight;
            sum += weight;
        }
        return sum;
    }

    /**
     * Light a point with normal [n] gets from each zone (fade toward the end of the range included), into
     * {@code scratch.light}. See {@link #cornerWeights}.
     */
    static void pointLight(BlockAt s, ScreenGeometry g, double[] centers, double[] rects, int zonesX, int zonesY,
                           int range, boolean shadows, double px, double py, double pz, double[] n, Scratch scratch) {
        // fade out toward the end of the range, so the light has no visible edge there
        double fade = smoothFade(g.distanceToPicture(px, py, pz), range);
        // the picture only shines forward
        boolean ahead = (px - g.cx()) * g.nx() + (py - g.cy()) * g.ny() + (pz - g.cz()) * g.nz() > 0;
        double[] light = scratch.light;
        Arrays.fill(light, 0);
        Arrays.fill(scratch.vis, (byte) 0);
        if (fade <= 0 || !ahead) return;
        double zoneW = (double) g.width() / zonesX, zoneH = (double) g.height() / zonesY;
        double diag = Math.sqrt(zoneW * zoneW + zoneH * zoneH);
        for (int j = 0; j < zonesY; j++) {
            for (int i = 0; i < zonesX; i++) {
                int z = j * zonesX + i;
                double dx = px - centers[z * 3], dy = py - centers[z * 3 + 1], dz = pz - centers[z * 3 + 2];
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz) / diag;
                double t = Clamp.of((r - BLEND_NEAR) / (BLEND_FAR - BLEND_NEAR), 0, 1);
                double whole = t * t * (3 - 2 * t);
                if (whole > 0) {
                    double f = formFactor(rects, z * 12, px, py, pz, n);
                    if (f > 1e-7 && lit(s, scratch, z, shadows, px, py, pz, centers)) light[z] += whole * f;
                }
                if (whole < 1) blended(s, g, scratch, centers, zonesX, zonesY, i, j, shadows, px, py, pz, n, 1 - whole);
            }
        }
        for (int z = 0; z < light.length; z++) light[z] *= fade;
    }

    /** Zone (i, j) in SUB x SUB pieces, each piece's light shared between the zones whose middles surround it. */
    private static void blended(BlockAt s, ScreenGeometry g, Scratch scratch, double[] centers, int zonesX, int zonesY,
                                int i, int j, boolean shadows, double px, double py, double pz, double[] n, double share) {
        double w = g.width(), h = g.height();
        double s0 = ((double) i / zonesX - 0.5) * w, ds = w / zonesX / SUB;
        double t0 = (0.5 - (double) j / zonesY) * h, dt = -h / zonesY / SUB;
        double[] q = scratch.quad;
        double[] light = scratch.light;
        int own = j * zonesX + i;
        for (int b = 0; b < SUB; b++) {
            for (int a = 0; a < SUB; a++) {
                double sa = s0 + a * ds, sb = sa + ds, ta = t0 + b * dt, tb = ta + dt;
                corner(g, q, 0, sa, ta);
                corner(g, q, 1, sb, ta);
                corner(g, q, 2, sb, tb);
                corner(g, q, 3, sa, tb);
                double f = formFactor(q, 0, px, py, pz, n) * share;
                if (f <= 1e-8 || !lit(s, scratch, own, shadows, px, py, pz, centers)) continue;
                // the piece's middle among the zone middles: bilinear shares (the outer zones reach the picture's edge)
                double fx = Clamp.of(((sa + sb) * 0.5 / w + 0.5) * zonesX - 0.5, 0, zonesX - 1);
                double fy = Clamp.of((0.5 - (ta + tb) * 0.5 / h) * zonesY - 0.5, 0, zonesY - 1);
                int x0 = Math.min((int) fx, Math.max(0, zonesX - 2)), y0 = Math.min((int) fy, Math.max(0, zonesY - 2));
                int x1 = Math.min(x0 + 1, zonesX - 1), y1 = Math.min(y0 + 1, zonesY - 1);
                double ax = fx - x0, ay = fy - y0;
                light[y0 * zonesX + x0] += f * (1 - ax) * (1 - ay);
                light[y0 * zonesX + x1] += f * ax * (1 - ay);
                light[y1 * zonesX + x0] += f * (1 - ax) * ay;
                light[y1 * zonesX + x1] += f * ax * ay;
            }
        }
    }

    private static void corner(ScreenGeometry g, double[] q, int k, double s, double t) {
        q[k * 3] = g.cx() + g.ux() * s + g.vx() * t;
        q[k * 3 + 1] = g.cy() + g.uy() * s + g.vy() * t;
        q[k * 3 + 2] = g.cz() + g.uz() * s + g.vz() * t;
    }

    /** Whether zone [z]'s middle is in view of the point (tested once per corner and zone). */
    private static boolean lit(BlockAt s, Scratch scratch, int z, boolean shadows, double px, double py, double pz,
                               double[] centers) {
        if (!shadows) return true;
        if (scratch.vis[z] == 0) {
            boolean seen = visible(s, px, py, pz, centers[z * 3], centers[z * 3 + 1], centers[z * 3 + 2]);
            scratch.vis[z] = seen ? (byte) 1 : (byte) 2;
        }
        return scratch.vis[z] == 1;
    }

    /**
     * Form factor from a point with normal [n] to the quad at [rects] from [k]: Lambert's formula over the edges of
     * the part of the quad above the point's horizon, {@code |sum(angle_i * n . unit(r_i x r_i+1))| / 2 pi}.
     */
    static double formFactor(double[] rects, int k, double px, double py, double pz, double[] n) {
        // the quad relative to the point, cut at the point's horizon (a quad partly behind a wall lights it partly)
        double[] poly = new double[8 * 3];
        int count = 0;
        for (int i = 0; i < 4; i++) {
            int a = k + i * 3, b = k + ((i + 1) & 3) * 3;
            double ax = rects[a] - px, ay = rects[a + 1] - py, az = rects[a + 2] - pz;
            double bx = rects[b] - px, by = rects[b + 1] - py, bz = rects[b + 2] - pz;
            double ha = ax * n[0] + ay * n[1] + az * n[2], hb = bx * n[0] + by * n[1] + bz * n[2];
            if (ha >= 0) {
                poly[count * 3] = ax;
                poly[count * 3 + 1] = ay;
                poly[count * 3 + 2] = az;
                count++;
            }
            if ((ha >= 0) != (hb >= 0)) {
                double t = ha / (ha - hb);
                poly[count * 3] = ax + (bx - ax) * t;
                poly[count * 3 + 1] = ay + (by - ay) * t;
                poly[count * 3 + 2] = az + (bz - az) * t;
                count++;
            }
        }
        if (count < 3) return 0;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int a = i * 3, b = ((i + 1) % count) * 3;
            double la = Math.sqrt(poly[a] * poly[a] + poly[a + 1] * poly[a + 1] + poly[a + 2] * poly[a + 2]);
            double lb = Math.sqrt(poly[b] * poly[b] + poly[b + 1] * poly[b + 1] + poly[b + 2] * poly[b + 2]);
            if (la < 1e-9 || lb < 1e-9) continue;
            double ax = poly[a] / la, ay = poly[a + 1] / la, az = poly[a + 2] / la;
            double bx = poly[b] / lb, by = poly[b + 1] / lb, bz = poly[b + 2] / lb;
            double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1e-12) continue;
            double angle = Math.acos(Clamp.of(ax * bx + ay * by + az * bz, -1, 1));
            sum += angle * (cx * n[0] + cy * n[1] + cz * n[2]) / cl;
        }
        return Math.min(1, Math.abs(sum) / (2 * Math.PI));
    }

    /** 1 up to 70 % of the range, then smoothly down to 0 at the range. */
    private static double smoothFade(double distance, int range) {
        double t = Clamp.of((range - distance) / (range * 0.3), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** True when no full block lies between the two points (voxel walk). */
    private static boolean visible(BlockAt s, double x0, double y0, double z0, double x1, double y1, double z1) {
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
            if (solid(s.get(x, y, z))) return false;
        }
        return true;
    }
}
