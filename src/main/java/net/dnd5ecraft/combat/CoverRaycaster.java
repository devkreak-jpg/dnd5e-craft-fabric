package net.dnd5ecraft.combat;

import java.util.function.Predicate;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

public final class CoverRaycaster {
    public enum Cover {
        NONE(0), HALF(2), THREE_QUARTERS(5), TOTAL(0);

        private final int bonus;
        Cover(int bonus) { this.bonus = bonus; }
        public int bonus() { return bonus; }
        public boolean blocksTargeting() { return this == TOTAL; }
    }

    private CoverRaycaster() {}

    public static Cover cover(Vec3 observer, Box target, Predicate<Cell> blocker) {
        int blocked = 0;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = new Vec3(corner(target.min().x(), target.max().x(), (corner & 1) == 0),
                    corner(target.min().y(), target.max().y(), (corner & 2) == 0),
                    corner(target.min().z(), target.max().z(), (corner & 4) == 0));
            if (!clearRay(observer, point, blocker)) blocked++;
        }
        if (blocked == 8) return Cover.TOTAL;
        if (blocked >= 6) return Cover.THREE_QUARTERS;
        return blocked >= 4 ? Cover.HALF : Cover.NONE;
    }

    private static double corner(double min, double max, boolean lower) {
        return lower ? Math.nextAfter(min, max) : Math.nextAfter(max, min);
    }

    /** Traverses crossed voxels. The source and destination volumes do not cover themselves. */
    public static boolean clearRay(Vec3 from, Vec3 to, Predicate<Cell> blocker) {
        double[] origin = {from.x(), from.y(), from.z()};
        double[] destination = {to.x(), to.y(), to.z()};
        int[] cell = new int[3];
        int[] end = new int[3];
        int[] direction = new int[3];
        double[] nextCrossing = new double[3];
        double[] delta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            cell[axis] = (int) Math.floor(origin[axis]);
            end[axis] = (int) Math.floor(destination[axis]);
            double span = destination[axis] - origin[axis];
            direction[axis] = cell[axis] == end[axis] ? 0 : span > 0 ? 1 : -1;
            if (direction[axis] == 0) {
                nextCrossing[axis] = delta[axis] = Double.POSITIVE_INFINITY;
            } else {
                double boundary = cell[axis] + (direction[axis] > 0 ? 1 : 0);
                nextCrossing[axis] = (boundary - origin[axis]) / span;
                delta[axis] = 1 / Math.abs(span);
            }
        }
        while (!java.util.Arrays.equals(cell, end)) {
            double next = Math.min(nextCrossing[0], Math.min(nextCrossing[1], nextCrossing[2]));
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(next - nextCrossing[axis]) <= 1e-12) {
                    cell[axis] += direction[axis];
                    nextCrossing[axis] = cell[axis] == end[axis] ? Double.POSITIVE_INFINITY : nextCrossing[axis] + delta[axis];
                }
            }
            if (!java.util.Arrays.equals(cell, end) && blocker.test(new Cell(cell[0], cell[1], cell[2]))) return false;
        }
        return true;
    }
}
