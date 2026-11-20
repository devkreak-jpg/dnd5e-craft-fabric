package net.dnd5ecraft.rules.spells;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import net.dnd5ecraft.rules.spells.AoEGeometry.Area;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

public final class AoETargetResolver {
    private AoETargetResolver() {}

    /** The ray predicate returns true if an effect has a clear path from its origin to the target. */
    public static <T> List<T> resolve(Area area, List<T> candidates, Function<T, Vec3> position,
            BiPredicate<Vec3, Vec3> clearPath) {
        return candidates.stream().filter(target -> {
            Vec3 point = position.apply(target);
            return area.contains(point) && clearPath.test(area.rayOrigin(point), point);
        }).toList();
    }

    private record Voxel(int x, int y, int z) {
        static Voxel containing(Vec3 point) {
            return new Voxel((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
        }

        Vec3 center() { return new Vec3(x + .5, y + .5, z + .5); }

        List<Voxel> neighbors() {
            return List.of(new Voxel(x - 1, y, z), new Voxel(x + 1, y, z), new Voxel(x, y - 1, z),
                    new Voxel(x, y + 1, z), new Voxel(x, y, z - 1), new Voxel(x, y, z + 1));
        }
    }

    /** An effect that spreads around corners can reach open voxels connected inside the area. */
    public static <T> List<T> resolveSpread(Area area, List<T> candidates, Function<T, Vec3> position,
            Predicate<Vec3> blockedVoxel) {
        if (area.template().shape() != AoEGeometry.SPHERE || area.template().size() > 100) {
            throw new IllegalArgumentException("Corner spread requires a bounded sphere");
        }
        Voxel origin = Voxel.containing(area.origin());
        if (blockedVoxel.test(origin.center())) return List.of();
        Set<Voxel> reached = new HashSet<>();
        ArrayDeque<Voxel> queue = new ArrayDeque<>();
        reached.add(origin);
        queue.add(origin);
        while (!queue.isEmpty()) {
            for (Voxel next : queue.remove().neighbors()) {
                Vec3 center = next.center();
                if (area.contains(center) && !blockedVoxel.test(center) && reached.add(next)) queue.add(next);
            }
        }
        return candidates.stream().filter(target -> {
            Vec3 point = position.apply(target);
            return area.contains(point) && reached.contains(Voxel.containing(point));
        }).toList();
    }
}
