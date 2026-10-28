package dev.xcrafttm.ddlumen.light;

import com.dreamdisplays.api.display.model.property.DisplayFacing;
import net.minecraft.core.BlockPos;

/**
 * The emitting picture of a display: the front face of its block box, split into a grid of zones.
 * <p>
 * {@code u} points to the picture's right and {@code v} to its top, as a viewer in front of it sees them (the same
 * axes Dream Displays uses in {@code DisplayGeometry.pose}); zone (0, 0) is the top left one, like the frame rows.
 */
public record ScreenGeometry(
        int minX, int minY, int minZ, int width, int height, int depth, DisplayFacing facing,
        double cx, double cy, double cz,
        int nx, int ny, int nz,
        int ux, int uy, int uz,
        int vx, int vy, int vz
) {
    public static ScreenGeometry of(int minX, int minY, int minZ, int width, int height, int depth, DisplayFacing facing) {
        // the box, as DisplayGeometry builds it
        int maxX = minX, maxY = minY + height - 1, maxZ = minZ;
        switch (facing) {
            case NORTH, SOUTH -> { maxX += width - 1; maxZ += depth - 1; }
            case EAST, WEST -> { maxZ += width - 1; maxX += depth - 1; }
            case UP, DOWN -> { maxX += width - 1; maxZ += height - 1; maxY = minY + depth - 1; }
        }
        double cx = (minX + maxX + 1) / 2.0, cy = (minY + maxY + 1) / 2.0, cz = (minZ + maxZ + 1) / 2.0;
        int nx = 0, ny = 0, nz = 0, ux = 0, uy = 0, uz = 0, vx = 0, vy = 1, vz = 0;
        switch (facing) {
            case NORTH -> { nz = -1; ux = -1; cz = minZ; }
            case SOUTH -> { nz = 1; ux = 1; cz = maxZ + 1; }
            case EAST -> { nx = 1; uz = -1; cx = maxX + 1; }
            case WEST -> { nx = -1; uz = 1; cx = minX; }
            case UP -> { ny = 1; ux = 1; vy = 0; vz = 1; cy = maxY + 1; }
            case DOWN -> { ny = -1; ux = 1; vy = 0; vz = 1; cy = minY; }
        }
        return new ScreenGeometry(minX, minY, minZ, width, height, depth, facing, cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz);
    }

    /** Area of the picture in square blocks. */
    public double area() {
        return (double) width * height;
    }

    /** Zone centers (x, y, z per zone, row-major from the top left), slightly in front of the picture. */
    public double[] zoneCenters(int zonesX, int zonesY) {
        double[] out = new double[zonesX * zonesY * 3];
        for (int j = 0; j < zonesY; j++) {
            double t = (0.5 - (j + 0.5) / zonesY) * height;
            for (int i = 0; i < zonesX; i++) {
                double s = ((i + 0.5) / zonesX - 0.5) * width;
                int k = (j * zonesX + i) * 3;
                out[k] = cx + ux * s + vx * t + nx * 0.01;
                out[k + 1] = cy + uy * s + vy * t + ny * 0.01;
                out[k + 2] = cz + uz * s + vz * t + nz * 0.01;
            }
        }
        return out;
    }

    /** Blocks that can receive light: everything up to [range] blocks from the picture, in front of it. */
    public BlockPos[] region(int range) {
        double halfU = width / 2.0 + range, halfV = height / 2.0 + range;
        double[] xs = new double[8], ys = new double[8], zs = new double[8];
        int n = 0;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                for (int c = 0; c <= 1; c++) {
                    double s = a * halfU, t = b * halfV, d = c * range;
                    xs[n] = cx + ux * s + vx * t + nx * d;
                    ys[n] = cy + uy * s + vy * t + ny * d;
                    zs[n] = cz + uz * s + vz * t + nz * d;
                    n++;
                }
            }
        }
        BlockPos min = new BlockPos(floorMin(xs), floorMin(ys), floorMin(zs));
        BlockPos max = new BlockPos(floorMax(xs), floorMax(ys), floorMax(zs));
        return new BlockPos[]{min, max};
    }

    /** Distance from the point to the nearest point of the picture. */
    public double distanceToPicture(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double s = Math.clamp(dx * ux + dy * uy + dz * uz, -width / 2.0, width / 2.0);
        double t = Math.clamp(dx * vx + dy * vy + dz * vz, -height / 2.0, height / 2.0);
        double ex = dx - ux * s - vx * t, ey = dy - uy * s - vy * t, ez = dz - uz * s - vz * t;
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /** True when the point is in front of the picture's plane. */
    public boolean inFront(double x, double y, double z) {
        return (x - cx) * nx + (y - cy) * ny + (z - cz) * nz > 0;
    }

    private static int floorMin(double[] v) {
        double m = Double.MAX_VALUE;
        for (double d : v) m = Math.min(m, d);
        return (int) Math.floor(m);
    }

    private static int floorMax(double[] v) {
        double m = -Double.MAX_VALUE;
        for (double d : v) m = Math.max(m, d);
        return (int) Math.ceil(m) - 1;
    }
}
