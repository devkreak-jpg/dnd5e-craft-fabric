package net.dnd5ecraft.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.combat.GridMovementTracker;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.GridMovementTracker.Path;
import net.dnd5ecraft.combat.GridMovementTracker.Terrain;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class TacticalGridWorldRenderer {
    private TacticalGridWorldRenderer() {}

    public static void register() { WorldRenderEvents.AFTER_ENTITIES.register(TacticalGridWorldRenderer::render); }

    private static void render(WorldRenderContext context) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui
                || context.matrixStack() == null || context.consumers() == null) return;
        var combat = ClientDndState.combat();
        var area = ClientActions.areaPreview();
        if ((combat == null || !combat.ownsTurn()) && area == null) return;
        var matrices = context.matrixStack();
        var camera = context.camera().getPosition();
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        var lines = context.consumers().getBuffer(RenderType.lines());
        if (area != null) AreaWireframe.edges(area).forEach(edge -> WorldDrawing.segment(matrices, lines, edge.from(), edge.to(), ARCANE));
        if (combat != null && combat.ownsTurn()) {
            Cell goal = ClientActions.hoveredCell();
            int alpha = ClientActions.aiming() ? 0x22 : 0x70;
            for (Cell cell : combat.reachable().keySet()) {
                int color = cell.equals(goal) ? GOLD : combat.threats().containsKey(cell) ? CRIMSON : (alpha << 24) | (ARCANE & 0xFFFFFF);
                outline(matrices, lines, cell, .035, color);
            }
            var actor = combat.creature(combat.controlledActor());
            var path = ClientActions.aiming() || goal == null || actor == null || !combat.reachable().containsKey(goal)
                    ? Optional.<Path>empty() : preview(combat, actor.cell(), goal, actor.conditions().contains(ConditionType.PRONE));
            path.ifPresent(value -> {
                for (int i = 1; i < value.cells().size(); i++) {
                    Cell from = value.cells().get(i - 1);
                    Cell to = value.cells().get(i);
                    int color = combat.threats().containsKey(to) || hasThreat(combat, from, to) ? CRIMSON : GOLD;
                    WorldDrawing.segment(matrices, lines, floorCenter(from).plus(new Vec3(0, .1, 0)),
                            floorCenter(to).plus(new Vec3(0, .1, 0)), color);
                }
            });
            if (area != null) combat.initiative().stream().filter(actorInArea -> area.contains(actorInArea.cell().center()))
                    .forEach(actorInArea -> outline(matrices, lines, actorInArea.cell(), .09, CRIMSON));
            path.ifPresent(value -> {
                if (value.cells().size() > 1) {
                    var pos = floorCenter(goal).plus(new Vec3(0, .45, 0));
                    boolean warning = risk(combat, value);
                    label(context, pos, text(warning ? "grid.risk" : "grid.path", value.costFeet()), warning ? CRIMSON : GOLD);
                }
            });
        }
        matrices.popPose();
    }

    private static void label(WorldRenderContext context, Vec3 position, Component text, int color) {
        var pose = context.matrixStack();
        pose.pushPose();
        pose.translate(position.x(), position.y(), position.z());
        pose.mulPose(context.camera().rotation());
        pose.scale(-.023f, -.023f, .023f);
        WorldDrawing.label(pose, context.consumers(), text, 0, color);
        pose.popPose();
    }

    private static void outline(com.mojang.blaze3d.vertex.PoseStack stack, com.mojang.blaze3d.vertex.VertexConsumer lines,
            Cell cell, double height, int color) {
        double x = cell.x() + .04, y = cell.y() + height, z = cell.z() + .04;
        var a = new Vec3(x, y, z);
        var b = new Vec3(x + .92, y, z);
        var c = new Vec3(x + .92, y, z + .92);
        var d = new Vec3(x, y, z + .92);
        WorldDrawing.segment(stack, lines, a, b, color);
        WorldDrawing.segment(stack, lines, b, c, color);
        WorldDrawing.segment(stack, lines, c, d, color);
        WorldDrawing.segment(stack, lines, d, a, color);
    }

    private static Vec3 floorCenter(Cell cell) { return new Vec3(cell.x() + .5, cell.y() + .035, cell.z() + .5); }

    private static boolean risk(Combat combat, Path path) {
        for (int i = 1; i < path.cells().size(); i++) {
            if (combat.threats().containsKey(path.cells().get(i)) || hasThreat(combat, path.cells().get(i - 1), path.cells().get(i))) return true;
        }
        return false;
    }

    private static boolean hasThreat(Combat combat, Cell from, Cell to) {
        var actor = combat.creature(combat.controlledActor());
        return combat.initiative().stream().anyMatch(enemy -> enemy.team() != actor.team() && enemy.hp() > 0
                && !enemy.conditions().contains(ConditionType.INCAPACITATED) && !enemy.conditions().contains(ConditionType.PARALYZED)
                && from.distanceFeet(enemy.cell()) <= enemy.reach() && to.distanceFeet(enemy.cell()) > enemy.reach());
    }

    private static Optional<Path> preview(Combat combat, Cell start, Cell goal, boolean crawl) {
        var level = Minecraft.getInstance().level;
        return GridMovementTracker.path(start, goal, combat.movement(), new Terrain() {
            @Override public boolean canOccupy(Cell cell) { return combat.reachable().containsKey(cell); }
            @Override public boolean difficult(Cell cell) {
                BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
                var state = level.getBlockState(pos);
                return !state.getFluidState().isEmpty() || state.is(BlockTags.SNOW) || level.getBlockState(pos.below()).is(BlockTags.SAND);
            }
            @Override public List<Cell> neighbors(Cell from) {
                var next = new ArrayList<Cell>(24);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) next.add(new Cell(from.x() + dx, from.y() + dy, from.z() + dz));
                }
                return next;
            }
        }, crawl);
    }
}
