package net.dnd5ecraft.mixin;

import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityScaleMixin {
    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
    @Unique
    private void dnd5e$npcDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> callback) {
        if ((Object) this instanceof DndNpcEntity npc)
            callback.setReturnValue(callback.getReturnValue().scale(npc.physicalScale()));
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("TAIL"))
    @Unique
    private void dnd5e$refreshPlayerScale(EntityDataAccessor<?> key, CallbackInfo callback) {
        if (this instanceof PlayerCharacter carrier && carrier.dnd5e$isScaleKey(key)) {
            ((Entity) (Object) this).refreshDimensions();
        }
    }
}
