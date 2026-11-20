package net.dnd5ecraft.client.render;

import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.network.CombatHudView;
import net.dnd5ecraft.network.Protocol.Creature;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class FloatingCombatBarRenderer {
    private FloatingCombatBarRenderer() {}

    public static void register() { WorldRenderEvents.AFTER_ENTITIES.register(FloatingCombatBarRenderer::render); }

    private static void render(WorldRenderContext context) {
        var client = Minecraft.getInstance();
        var combat = ClientDndState.combat();
        if (client.level == null || client.player == null || client.options.hideGui || combat == null
                || context.matrixStack() == null || context.consumers() == null) return;
        var camera = context.camera().getPosition();
        for (var entity : client.level.entitiesForRendering()) {
            Creature info = combat.creature(entity.getUUID());
            if (info == null || entity == client.getCameraEntity() && client.options.getCameraType().isFirstPerson()
                    || entity.isInvisibleTo(client.player) || entity.distanceToSqr(camera) > 48 * 48) continue;
            var matrices = context.matrixStack();
            matrices.pushPose();
            double delta = context.tickDelta();
            matrices.translate(net.minecraft.util.Mth.lerp(delta, entity.xOld, entity.getX()) - camera.x,
                    net.minecraft.util.Mth.lerp(delta, entity.yOld, entity.getY()) - camera.y + entity.getBbHeight() + .36 + hornClearance(entity),
                    net.minecraft.util.Mth.lerp(delta, entity.zOld, entity.getZ()) - camera.z);
            matrices.mulPose(context.camera().rotation());
            matrices.scale(-.025f, -.025f, .025f);
            int accent = info.id().equals(combat.activeActor()) ? GOLD : CombatHudView.allied(combat, info) ? EMERALD : CRIMSON;
            var buffer = context.consumers().getBuffer(RenderType.entityTranslucent(WorldDrawing.WHITE));
            double fill = 42 * CombatHudView.healthFraction(info.hp(), info.maxHp());
            rectangle(matrices, buffer, -21, -10, 21, -5, 0, 0xD80A0B0E);
            if (fill > 0) rectangle(matrices, buffer, -21, -9, -21 + fill, -6, -.02, accent);
            WorldDrawing.label(matrices, context.consumers(), text("render.health_armor", info.hp(), info.armor()), 1, TEXT, 0xDB0A0B0E);
            matrices.popPose();
        }
    }

    private static float hornClearance(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.dnd5ecraft.server.PlayerCharacter player) || !player.dnd5e$showsCosmetics()
                || player.dnd5e$disguise() != 0) return 0;
        return switch (player.dnd5e$race()) {
            case MINOTAUR, TIEFLING, DRAGONBORN -> .34f * player.dnd5e$physicalScale();
            default -> 0;
        };
    }

    private static void rectangle(com.mojang.blaze3d.vertex.PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer buffer,
            double left, double top, double right, double bottom, double z, int color) {
        WorldDrawing.surface(pose, buffer, new Vec3(0, 0, -1), new Vec3(left, top, z), new Vec3(left, bottom, z),
                new Vec3(right, bottom, z), new Vec3(right, top, z), color);
    }
}
