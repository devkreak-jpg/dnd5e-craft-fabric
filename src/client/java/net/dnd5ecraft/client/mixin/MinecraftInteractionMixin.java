package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientDndState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftInteractionMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$attackOrSelect(CallbackInfoReturnable<Boolean> callback) {
        if (ClientActions.attack()) callback.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$moveOrConfirm(CallbackInfo callback) {
        if (ClientActions.use()) callback.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$skipTacticalMining(boolean attacking, CallbackInfo callback) {
        if (attacking && ClientDndState.combat() != null) callback.cancel();
    }
}
