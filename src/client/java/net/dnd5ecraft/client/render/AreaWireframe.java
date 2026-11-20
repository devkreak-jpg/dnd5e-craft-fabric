package net.dnd5ecraft.client.render;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.rules.spells.AoEGeometry.Area;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

final class AreaWireframe {
    record Edge(Vec3 from, Vec3 to) {}

    private static final Vec3 EAST = new Vec3(1, 0, 0);
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3 SOUTH = new Vec3(0, 0, 1);
    private static final int SEGMENTS = 32;

    private AreaWireframe() {}

    static List<Edge> edges(Area area) {
        double size = area.template().size() / 5;
        double other = area.template().secondary() / 5;
        Vec3 origin = area.origin();
        List<Edge> result = new ArrayList<>();
        switch (area.template().shape()) {
            case SINGLE -> {
                result.add(new Edge(origin.minus(EAST.scale(.25)), origin.plus(EAST.scale(.25))));
                result.add(new Edge(origin.minus(SOUTH.scale(.25)), origin.plus(SOUTH.scale(.25))));
            }
            case SPHERE -> {
                addRing(result, origin, EAST, SOUTH, size);
                addRing(result, origin, EAST, UP, size);
                addRing(result, origin, UP, SOUTH, size);
                for (int i : new int[]{-1, 1}) addRing(result, origin.plus(UP.scale(size * i / 2)),
                        EAST, SOUTH, size * Math.sqrt(.75));
            }
            case CYLINDER -> {
                Vec3 top = origin.plus(UP.scale(other));
                addRing(result, origin, EAST, SOUTH, size);
                addRing(result, top, EAST, SOUTH, size);
                for (int i = 0; i < SEGMENTS; i += 4) {
                    result.add(new Edge(onRing(origin, EAST, SOUTH, size, i), onRing(top, EAST, SOUTH, size, i)));
                }
            }
            case CONE -> {
                Vec3 forward = area.facing();
                Vec3 across = side(forward);
                Vec3 vertical = forward.cross(across);
                Vec3 end = origin.plus(forward.scale(size));
                addRing(result, end, across, vertical, size / 2);
                addRing(result, origin.plus(forward.scale(size / 2)), across, vertical, size / 4);
                for (int i = 0; i < SEGMENTS; i += 4) {
                    result.add(new Edge(origin, onRing(end, across, vertical, size / 2, i)));
                }
            }
            case CUBE, LINE -> {
                double half = (area.template().shape() == net.dnd5ecraft.rules.spells.AoEGeometry.CUBE ? size : other) / 2;
                Vec3 forward = area.facing();
                Vec3 across = side(forward);
                Vec3 vertical = forward.cross(across);
                Vec3[] corners = new Vec3[8];
                for (int i = 0; i < corners.length; i++) {
                    corners[i] = origin.plus(forward.scale((i & 4) != 0 ? size : 0))
                            .plus(across.scale((i & 2) != 0 ? half : -half))
                            .plus(vertical.scale((i & 1) != 0 ? half : -half));
                }
                for (int i = 0; i < corners.length; i++) {
                    for (int bit : new int[]{1, 2, 4}) if ((i & bit) == 0) result.add(new Edge(corners[i], corners[i | bit]));
                }
            }
            case EMANATION -> {
                roundedRing(result, area, EAST, SOUTH, size);
                roundedRing(result, area, EAST, UP, size);
                roundedRing(result, area, UP, SOUTH, size);
            }
        }
        return List.copyOf(result);
    }

    private static Vec3 side(Vec3 forward) { return (Math.abs(forward.y()) > .99 ? SOUTH : UP).cross(forward).unit(); }

    private static void addRing(List<Edge> edges, Vec3 origin, Vec3 u, Vec3 v, double radius) {
        for (int i = 0; i < SEGMENTS; i++) {
            edges.add(new Edge(onRing(origin, u, v, radius, i), onRing(origin, u, v, radius, i + 1)));
        }
    }

    private static Vec3 onRing(Vec3 origin, Vec3 u, Vec3 v, double radius, int i) {
        double angle = i * (2 * Math.PI) / SEGMENTS;
        return origin.plus(u.scale(Math.cos(angle) * radius)).plus(v.scale(Math.sin(angle) * radius));
    }

    private static void roundedRing(List<Edge> result, Area area, Vec3 u, Vec3 v, double radius) {
        Vec3 center = area.source().center();
        Vec3 previous = roundedPoint(area, center, u, v, radius, 0);
        for (int i = 1; i <= SEGMENTS; i++) {
            Vec3 next = roundedPoint(area, center, u, v, radius, i);
            result.add(new Edge(previous, next));
            previous = next;
        }
    }

    private static Vec3 roundedPoint(Area area, Vec3 center, Vec3 u, Vec3 v, double radius, int i) {
        Vec3 normal = onRing(new Vec3(0, 0, 0), u, v, 1, i);
        double size = area.source().max().minus(area.source().min()).length() + 1;
        return area.source().closest(center.plus(normal.scale(size))).plus(normal.scale(radius));
    }
}
