package net.dnd5ecraft.mixin;

import net.dnd5ecraft.server.TacticalCombat;
import net.dnd5ecraft.server.CinematicFreeze;
import net.dnd5ecraft.server.PlayerSheets;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerMovementLockMixin {
    @Shadow public ServerPlayer player;
    @Unique private long dnd5e$lastCorrection = Long.MIN_VALUE / 2;

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$keepVehiclePosition(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet, CallbackInfo callback) {
        if (player == null || player.connection == null || !player.serverLevel().getServer().isSameThread()) return;
        if (!TacticalCombat.isLocked(player) && !CinematicFreeze.frozen(player) && !PlayerSheets.unconscious(player)) return;
        var vehicle = player.getRootVehicle();
        if (vehicle != player && vehicle.getControllingPassenger() == player) {
            long tick = player.serverLevel().getGameTime();
            if (tick - dnd5e$lastVehicleCorrection >= 3) {
                dnd5e$lastVehicleCorrection = tick;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket(vehicle));
            }
        }
        callback.cancel();
    }

    @Unique private long dnd5e$lastVehicleCorrection = Long.MIN_VALUE / 2;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$keepTacticalPosition(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        if (player == null || player.connection == null || !player.serverLevel().getServer().isSameThread() || !packet.hasPosition()) return;
        if (!TacticalCombat.isLocked(player) && !CinematicFreeze.frozen(player) && !PlayerSheets.unconscious(player)) return;
        double dx = packet.getX(player.getX()) - player.getX();
        double dy = packet.getY(player.getY()) - player.getY();
        double dz = packet.getZ(player.getZ()) - player.getZ();
        if (Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz < 1e-8) return;

        float yaw = packet.getYRot(player.getYRot());
        float pitch = packet.getXRot(player.getXRot());
        if (Float.isFinite(yaw)) player.setYRot(Mth.wrapDegrees(yaw));
        if (Float.isFinite(pitch)) player.setXRot(Mth.clamp(Mth.wrapDegrees(pitch), -90, 90));
        long tick = player.serverLevel().getGameTime();
        if (tick - dnd5e$lastCorrection >= 3) {
            dnd5e$lastCorrection = tick;
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
        callback.cancel();
    }
}
