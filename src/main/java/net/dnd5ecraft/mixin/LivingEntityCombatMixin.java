package net.dnd5ecraft.mixin;

import net.dnd5ecraft.server.TacticalCombat;
import net.dnd5ecraft.server.CinematicFreeze;
import net.dnd5ecraft.server.PlayerSheets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityCombatMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$routeDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        TacticalCombat.interceptDamage((LivingEntity) (Object) this, source, amount).ifPresent(callback::setReturnValue);
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$stopVanillaTravel(Vec3 motion, CallbackInfo callback) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (TacticalCombat.isLocked(entity) || entity instanceof ServerPlayer player
                && (CinematicFreeze.frozen(player) || PlayerSheets.unconscious(player))) {
            entity.setDeltaMovement(Vec3.ZERO);
            callback.cancel();
        }
    }
}
