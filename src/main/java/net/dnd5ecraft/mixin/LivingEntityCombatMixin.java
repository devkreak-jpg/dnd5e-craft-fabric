package net.dnd5ecraft.mixin;

import net.dnd5ecraft.server.TacticalCombat;
import net.dnd5ecraft.server.CinematicFreeze;
import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.server.PlayerCharacter;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.character.RaceType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityCombatMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$routeDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        TacticalCombat.interceptDamage((LivingEntity) (Object) this, source, amount).ifPresent(callback::setReturnValue);
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$routeHealing(float amount, CallbackInfo callback) {
        if (TacticalCombat.interceptVanillaHealing((LivingEntity) (Object) this, amount)) callback.cancel();
    }

    @ModifyArg(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V", ordinal = 0), index = 0)
    @Unique
    private float dnd5e$tritonSwim(float speed) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof Player player && ((PlayerCharacter) player).dnd5e$race() == RaceType.TRITON) {
            return speed * CampaignRules.TRITON_SWIM_MULTIPLIER;
        }
        return speed;
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
