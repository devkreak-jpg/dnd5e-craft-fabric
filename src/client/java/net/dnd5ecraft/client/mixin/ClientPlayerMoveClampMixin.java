package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.client.ClientDndState;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerMoveClampMixin {
    @Shadow public Input input;

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    @Unique
    private void dnd5e$keepTacticalInput(CallbackInfo callback) {
        if (!ClientDndState.controlsCombatant() || ClientDndState.state().dm()) return;
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.jumping = false;
    }
}
