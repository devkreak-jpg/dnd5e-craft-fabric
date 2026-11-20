package net.dnd5ecraft.mixin;

import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$skipVanillaFoodTick(Player player, CallbackInfo callback) {
        if (((PlayerCharacter) player).dnd5e$sheet().isPresent()) callback.cancel();
    }
}
