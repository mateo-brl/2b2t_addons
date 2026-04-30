package com.basefinder.domain.zone;

import java.util.List;

/**
 * Représentation d'une zone de recherche dessinée par l'utilisateur sur
 * le dashboard.
 *
 * Deux types supportés en MVP :
 * - {@link Type#POLYGON} : outer ring uniquement, coords en world blocks
 *   (x, z). On ignore les holes ({@code rings.subList(1, n)}) pour ce
 *   MVP — l'UI ne les expose pas non plus.
 * - {@link Type#CIRCLE}  : (centerX, centerZ, radius) en blocs.
 *
 * {@link #contains(int, int)} teste l'inclusion d'un point world (x, z) :
 * - Polygon : ray casting (O(n) en sommets).
 * - Circle  : distance² ≤ radius².
 */
public final class SearchZone {

    public enum Type { POLYGON, CIRCLE }

    public static final class Polygon {
        private final double[] xs;
        private final double[] zs;

        public Polygon(List<double[]> ring) {
            int n = ring.size();
            xs = new double[n];
            zs = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = ring.get(i);
                xs[i] = p[0];
                zs[i] = p[1];
            }
        }

        public boolean contains(double x, double z) {
            int n = xs.length;
            if (n < 3) return false;
            boolean inside = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = xs[i], zi = zs[i];
                double xj = xs[j], zj = zs[j];
                boolean cross = ((zi > z) != (zj > z))
                        && (x < (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi);
                if (cross) inside = !inside;
            }
            return inside;
        }
    }

    public static final class Circle {
        public final double centerX;
        public final double centerZ;
        public final double radius;

        public Circle(double centerX, double centerZ, double radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
        }

        public boolean contains(double x, double z) {
            double dx = x - centerX;
            double dz = z - centerZ;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    public final long id;
    public final String name;
    public final String dimension;
    public final boolean active;
    public final Type type;
    public final Polygon polygon; // null if type != POLYGON
    public final Circle circle;   // null if type != CIRCLE

    private SearchZone(long id, String name, String dim, boolean active,
                       Type type, Polygon polygon, Circle circle) {
        this.id = id;
        this.name = name;
        this.dimension = dim;
        this.active = active;
        this.type = type;
        this.polygon = polygon;
        this.circle = circle;
    }

    public static SearchZone polygon(long id, String name, String dim, boolean active, Polygon polygon) {
        return new SearchZone(id, name, dim, active, Type.POLYGON, polygon, null);
    }

    public static SearchZone circle(long id, String name, String dim, boolean active, Circle circle) {
        return new SearchZone(id, name, dim, active, Type.CIRCLE, null, circle);
    }

    public boolean contains(int worldX, int worldZ) {
        return switch (type) {
            case POLYGON -> polygon.contains(worldX, worldZ);
            case CIRCLE -> circle.contains(worldX, worldZ);
        };
    }

    /**
     * Bounding box axis-aligned (en world blocks). Sert au calcul de la
     * bbox d'union pour piloter les waypoints de NavigationHelper.
     */
    public int[] boundingBox() {
        if (type == Type.CIRCLE) {
            return new int[] {
                    (int) Math.floor(circle.centerX - circle.radius),
                    (int) Math.ceil(circle.centerX + circle.radius),
                    (int) Math.floor(circle.centerZ - circle.radius),
                    (int) Math.ceil(circle.centerZ + circle.radius),
            };
        }
        double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < polygon.xs.length; i++) {
            xmin = Math.min(xmin, polygon.xs[i]);
            xmax = Math.max(xmax, polygon.xs[i]);
            zmin = Math.min(zmin, polygon.zs[i]);
            zmax = Math.max(zmax, polygon.zs[i]);
        }
        return new int[] {
                (int) Math.floor(xmin), (int) Math.ceil(xmax),
                (int) Math.floor(zmin), (int) Math.ceil(zmax),
        };
    }
}
