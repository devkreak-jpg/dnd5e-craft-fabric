package net.dnd5ecraft.client.render;

import java.util.List;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

final class Icosahedron {
    private static final double GOLDEN = (1 + Math.sqrt(5)) / 2;
    static final List<Vec3> POINTS = List.of(
            p(-1, GOLDEN, 0), p(1, GOLDEN, 0), p(-1, -GOLDEN, 0), p(1, -GOLDEN, 0),
            p(0, -1, GOLDEN), p(0, 1, GOLDEN), p(0, -1, -GOLDEN), p(0, 1, -GOLDEN),
            p(GOLDEN, 0, -1), p(GOLDEN, 0, 1), p(-GOLDEN, 0, -1), p(-GOLDEN, 0, 1)
    );
    record Face(int a, int b, int c) {
        Vec3 normal() { return POINTS.get(a).plus(POINTS.get(b)).plus(POINTS.get(c)).unit(); }
    }
    static final List<Face> FACES = List.of(
            new Face(0,11,5), new Face(0,5,1), new Face(0,1,7), new Face(0,7,10), new Face(0,10,11),
            new Face(1,5,9), new Face(5,11,4), new Face(11,10,2), new Face(10,7,6), new Face(7,1,8),
            new Face(3,9,4), new Face(3,4,2), new Face(3,2,6), new Face(3,6,8), new Face(3,8,9),
            new Face(4,9,5), new Face(2,4,11), new Face(6,2,10), new Face(8,6,7), new Face(9,8,1)
    );

    private Icosahedron() {}

    private static Vec3 p(double x, double y, double z) { return new Vec3(x, y, z).unit(); }
}
