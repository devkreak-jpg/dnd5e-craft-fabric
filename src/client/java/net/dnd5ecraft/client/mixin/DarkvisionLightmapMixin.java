package net.dnd5ecraft.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.dnd5ecraft.client.ClientDndState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public abstract class DarkvisionLightmapMixin {
    @Shadow private boolean updateLightTexture;
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private DynamicTexture lightTexture;
    @Unique private boolean dnd5e$updated;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    @Unique
    private void dnd5e$rememberUpdate(float delta, CallbackInfo callback) {
        dnd5e$updated = updateLightTexture;
    }

    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    @Unique
    private void dnd5e$applyDarkvision(float delta, CallbackInfo callback) {
        var client = Minecraft.getInstance();
        var viewer = client.player;
        int distance = ClientDndState.state().darkvision();
        if (!dnd5e$updated || viewer == null || client.level == null || distance <= 0
                || viewer.hasEffect(MobEffects.BLINDNESS) || viewer.hasEffect(MobEffects.DARKNESS)
                || viewer.hasEffect(MobEffects.NIGHT_VISION)) return;
        var camera = client.getCameraEntity();
        if (camera == null || camera.pick(distance / 5.0, delta, false).getType() == HitResult.Type.MISS) return;
        for (int sky = 0; sky < 8; sky++) {
            for (int block = 0; block < 8; block++) {
                int pixel = lightPixels.getPixelRGBA(block, sky);
                int red = brighten(pixel & 255, block, sky);
                int green = brighten((pixel >>> 8) & 255, block, sky);
                int blue = brighten((pixel >>> 16) & 255, block, sky);
                lightPixels.setPixelRGBA(block, sky, 0xFF000000 | (blue << 16) | (green << 8) | red);
            }
        }
        lightTexture.upload();
    }

    @Unique
    private static int brighten(int value, int block, int sky) {
        return Math.min(255, value + (7 - Math.max(block, sky)) * Math.max(0, 160 - value) / 14);
    }
}
