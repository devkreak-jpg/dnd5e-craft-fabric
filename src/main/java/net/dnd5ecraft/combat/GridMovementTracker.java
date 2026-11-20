package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

public final class GridMovementTracker {
    public static final int FEET_PER_BLOCK = 5;

    public record Cell(int x, int y, int z) {
        public Vec3 center() { return new Vec3(x + .5, y + .5, z + .5); }

        public int distanceFeet(Cell to) {
            long dx = Math.abs((long) x - to.x);
            long dy = Math.abs((long) y - to.y);
            long dz = Math.abs((long) z - to.z);
            return (int) Math.min(Integer.MAX_VALUE, FEET_PER_BLOCK * Math.max(dx, Math.max(dy, dz)));
        }
    }

    public interface Terrain {
        boolean canOccupy(Cell cell);
        boolean difficult(Cell cell);

        default List<Cell> neighbors(Cell from) {
            List<Cell> cells = new ArrayList<>(8);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) cells.add(new Cell(from.x + dx, from.y, from.z + dz));
                }
            }
            return cells;
        }

        default boolean canStep(Cell from, Cell to) {
            if (!canOccupy(to)) return false;
            return from.x == to.x || from.z == to.z
                    || (canOccupy(new Cell(to.x, to.y, from.z)) && canOccupy(new Cell(from.x, to.y, to.z)));
        }
    }

    public record Path(List<Cell> cells, int costFeet) {
        public Path { cells = List.copyOf(cells); }
    }

    private record Node(Cell cell, int cost, long estimate, long order) {}
    private record Search(Map<Cell, Integer> costs, Map<Cell, Cell> parent) {}

    private GridMovementTracker() {}

    public static int stepCost(Cell from, Cell to, Terrain terrain, boolean crawling) {
        if (from.distanceFeet(to) != FEET_PER_BLOCK || !terrain.neighbors(from).contains(to)
                || !terrain.canStep(from, to)) {
            throw new IllegalArgumentException("Cells do not form a legal step");
        }
        return terrainCost(to, terrain, crawling);
    }

    private static int terrainCost(Cell destination, Terrain terrain, boolean crawling) {
        // Crawling and difficult terrain each add one foot of cost per foot traveled.
        return FEET_PER_BLOCK * (1 + (terrain.difficult(destination) ? 1 : 0) + (crawling ? 1 : 0));
    }

    public static Map<Cell, Integer> reachable(Cell from, int budgetFeet, Terrain terrain, boolean crawling) {
        return Collections.unmodifiableMap(search(from, null, budgetFeet, terrain, crawling).costs);
    }

    public static Optional<Path> path(Cell from, Cell to, int budgetFeet, Terrain terrain, boolean crawling) {
        Search result = search(from, to, budgetFeet, terrain, crawling);
        Integer cost = result.costs.get(to);
        if (cost == null) return Optional.empty();
        List<Cell> cells = new ArrayList<>();
        for (Cell cell = to; cell != null; cell = result.parent.get(cell)) cells.add(cell);
        Collections.reverse(cells);
        return Optional.of(new Path(cells, cost));
    }

    private static Search search(Cell from, Cell goal, int budget, Terrain terrain, boolean crawling) {
        if (budget < 0) throw new IllegalArgumentException("Movement budget cannot be negative");
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingLong(Node::estimate)
                .thenComparingLong(Node::order));
        Map<Cell, Integer> costs = new LinkedHashMap<>();
        Map<Cell, Cell> parent = new HashMap<>();
        costs.put(from, 0);
        queue.add(new Node(from, 0, 0, 0));
        long sequence = 0;
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            if (node.cost != costs.get(node.cell)) continue;
            if (node.cell.equals(goal)) break;
            for (Cell to : terrain.neighbors(node.cell)) {
                if (node.cell.distanceFeet(to) != FEET_PER_BLOCK || !terrain.canStep(node.cell, to)) continue;
                long cost = (long) node.cost + terrainCost(to, terrain, crawling);
                if (cost > budget || cost >= costs.getOrDefault(to, Integer.MAX_VALUE)) continue;
                costs.put(to, (int) cost);
                parent.put(to, node.cell);
                long heuristic = goal == null ? 0 : to.distanceFeet(goal);
                queue.add(new Node(to, (int) cost, cost + heuristic, ++sequence));
            }
        }
        return new Search(costs, parent);
    }
}
