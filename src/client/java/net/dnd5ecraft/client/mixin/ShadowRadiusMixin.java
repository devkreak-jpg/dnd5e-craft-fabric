package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowRadiusMixin {
    // The dispatcher draws the shadow after the model renderer has returned. Scale only this invocation.
    @Unique
    @ModifyArgs(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V"))
    private void dnd5e$shadowRadius(Args args) {
        Entity entity = args.get(2);
        float scale = entity instanceof PlayerCharacter player ? player.dnd5e$physicalScale()
                : entity instanceof DndNpcEntity npc ? npc.physicalScale() : 1f;
        if (scale != 1f) args.set(6, (float) args.get(6) * scale);
    }
}
