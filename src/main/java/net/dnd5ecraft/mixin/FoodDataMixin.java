package net.dnd5ecraft.mixin;

import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"))
    @Unique
    private void dnd5e$skipVanillaFoodHealing(Player player, float amount) {
        if (((PlayerCharacter) player).dnd5e$sheet().isEmpty()) player.heal(amount);
    }
}
