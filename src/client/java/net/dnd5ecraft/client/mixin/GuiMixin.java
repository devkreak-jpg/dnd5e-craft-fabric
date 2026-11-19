package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.client.ClientDndState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Unique
    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void dnd5e$replacePlayerHealth(GuiGraphics graphics, CallbackInfo callback) {
        var client = Minecraft.getInstance();
        if (ClientDndState.sheet() != null && client.getCameraEntity() == client.player && !client.player.isSpectator()) {
            callback.cancel();
        }
    }
}
