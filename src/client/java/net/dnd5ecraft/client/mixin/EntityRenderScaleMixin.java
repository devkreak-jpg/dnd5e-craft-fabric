package net.dnd5ecraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class EntityRenderScaleMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
    @Unique
    private void dnd5e$applyRaceScale(LivingEntity entity, float yaw, float partialTick, PoseStack stack,
            MultiBufferSource buffers, int light, CallbackInfo callback) {
        if (entity instanceof PlayerCharacter carrier) {
            float scale = carrier.dnd5e$physicalScale();
            if (scale != 1f) stack.scale(scale, scale, scale);
        }
    }
}
