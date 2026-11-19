package net.dnd5ecraft.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public final class CampaignPoses {
    private static final String SEAT = "dnd5e-craft:roleplay-seat";

    private CampaignPoses() {}

    public static void toggle(ServerPlayer player, boolean kneel) {
        PlayerRules.requireDowntime(player);
        if (player.getVehicle() != null && player.getVehicle().getTags().contains(SEAT)) {
            var seat = player.getVehicle();
            player.stopRiding();
            seat.discard();
            return;
        }
        if (!player.onGround() || player.isPassenger()) throw new IllegalStateException("Stand on solid ground first");
        var seat = EntityType.ARMOR_STAND.create(player.serverLevel());
        if (seat == null) throw new IllegalStateException("Pose unavailable");
        var data = new CompoundTag();
        data.putBoolean("Invisible", true);
        data.putBoolean("Marker", true);
        data.putBoolean("NoGravity", true);
        data.putBoolean("Invulnerable", true);
        seat.load(data);
        Vec3 pos = player.position();
        seat.moveTo(pos.x, pos.y + (kneel ? .12 : .05), pos.z, player.getYRot(), 0);
        seat.addTag(SEAT);
        if (player.serverLevel().addFreshEntity(seat) && !player.startRiding(seat, true)) seat.discard();
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (level.getGameTime() % 20 != 0) return;
            for (var entity : level.getAllEntities()) if (entity.getTags().contains(SEAT) && !entity.isVehicle()) entity.discard();
        });
    }
}
