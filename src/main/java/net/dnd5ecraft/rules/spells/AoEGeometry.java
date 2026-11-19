package net.dnd5ecraft.rules.spells;

import java.util.Objects;

public enum AoEGeometry {
    CONE, SPHERE, CUBE, LINE, CYLINDER, EMANATION, SINGLE;

    private static final double EPS = 1e-9;

    public record Vec3(double x, double y, double z) {
        public Vec3 {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Coordinates must be finite");
            }
        }

        public Vec3 plus(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
        public Vec3 minus(Vec3 b) { return new Vec3(x - b.x, y - b.y, z - b.z); }
        public Vec3 scale(double n) { return new Vec3(x * n, y * n, z * n); }
        public double dot(Vec3 b) { return x * b.x + y * b.y + z * b.z; }
        public Vec3 cross(Vec3 b) { return new Vec3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
        public double length() { return Math.hypot(Math.hypot(x, y), z); }

        public Vec3 unit() {
            double length = length();
            if (length == 0) {
                throw new IllegalArgumentException("Direction cannot be zero");
            }
            return scale(1 / length);
        }
    }

    public record Box(Vec3 min, Vec3 max) {
        public Box {
            if (min.x > max.x || min.y > max.y || min.z > max.z) {
                throw new IllegalArgumentException("Reversed box");
            }
        }

        public static Box point(Vec3 point) { return new Box(point, point); }
        public Vec3 center() { return min.plus(max).scale(.5); }

        public Vec3 closest(Vec3 point) {
            return new Vec3(clamp(point.x, min.x, max.x), clamp(point.y, min.y, max.y), clamp(point.z, min.z, max.z));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** Sizes are in feet. The second dimension is line width or cylinder height. */
    public record Template(AoEGeometry shape, double size, double secondary) {
        public Template {
            Objects.requireNonNull(shape);
            boolean twoDimensions = shape == LINE || shape == CYLINDER;
            if (!Double.isFinite(size) || !Double.isFinite(secondary) || size <= 0
                    || (twoDimensions ? secondary <= 0 : secondary != 0)) {
                throw new IllegalArgumentException("Invalid area dimensions");
            }
        }

        public Template(AoEGeometry shape, double size) {
            this(shape, size, 0);
        }

        public Area place(Vec3 origin, Vec3 facing, Box source, boolean includeOrigin) {
            return new Area(this, origin, facing, source, includeOrigin);
        }
    }

    /** Positions are in blocks. For a cube the origin is the center of its starting face. */
    public record Area(Template template, Vec3 origin, Vec3 facing, Box source, boolean includeOrigin) {
        public Area {
            Objects.requireNonNull(template);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(source);
            if (template.shape == CONE || template.shape == CUBE || template.shape == LINE) {
                facing = facing.unit();
            }
        }

        public Vec3 rayOrigin(Vec3 target) {
            return template.shape == EMANATION ? source.closest(target) : origin;
        }

        public boolean contains(Vec3 point) {
            Vec3 offset = point.minus(origin);
            double size = template.size / 5;
            double secondary = template.secondary / 5;
            if (!includeOrigin && point.equals(rayOrigin(point))) {
                return false;
            }
            return switch (template.shape) {
                case SINGLE -> point.equals(origin);
                case SPHERE -> offset.length() <= size + EPS;
                case EMANATION -> point.minus(source.closest(point)).length() <= size + EPS;
                case CYLINDER -> offset.y >= -EPS && offset.y <= secondary + EPS
                        && Math.hypot(offset.x, offset.z) <= size + EPS;
                case CONE -> {
                    double forward = offset.dot(facing);
                    yield forward >= -EPS && forward <= size + EPS
                            && offset.minus(facing.scale(forward)).length() <= forward / 2 + EPS;
                }
                case CUBE, LINE -> {
                    double forward = offset.dot(facing);
                    double halfWidth = (template.shape == CUBE ? size : secondary) / 2;
                    Vec3 up = Math.abs(facing.y) > .99 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
                    Vec3 right = up.cross(facing).unit();
                    yield forward >= -EPS && forward <= size + EPS
                            && Math.abs(offset.dot(right)) <= halfWidth + EPS
                            && Math.abs(offset.dot(facing.cross(right))) <= halfWidth + EPS;
                }
            };
        }
    }
}
