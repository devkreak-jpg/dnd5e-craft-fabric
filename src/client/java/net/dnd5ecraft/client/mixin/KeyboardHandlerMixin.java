package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$routeSharedKeys(long window, int key, int scancode, int action, int modifiers,
            CallbackInfo callback) {
        var client = Minecraft.getInstance();
        if (window != client.getWindow().getWindow()) return;
        if (ModKeybinds.sharedJump(client, key, scancode, action)
                || ModKeybinds.sharedToolbarKeys(client, key, scancode, action)) callback.cancel();
    }
}
