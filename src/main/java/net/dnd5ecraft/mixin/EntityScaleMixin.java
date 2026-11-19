package net.dnd5ecraft.mixin;

import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityScaleMixin {
    @Shadow private float eyeHeight;

    @Redirect(method = "refreshDimensions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"))
    @Unique
    private EntityDimensions dnd5e$scaleDimensions(Entity entity, Pose pose) {
        EntityDimensions dimensions = entity.getDimensions(pose);
        float scale = entity instanceof DndNpcEntity npc ? npc.physicalScale()
                : entity instanceof PlayerCharacter carrier ? carrier.dnd5e$physicalScale() : 1f;
        return scale == 1f ? dimensions : dimensions.scale(scale);
    }

    @Inject(method = "refreshDimensions", at = @At("TAIL"))
    @Unique
    private void dnd5e$scalePlayerEyeHeight(CallbackInfo callback) {
        if (this instanceof PlayerCharacter carrier) eyeHeight *= carrier.dnd5e$physicalScale();
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("TAIL"))
    @Unique
    private void dnd5e$refreshPlayerScale(EntityDataAccessor<?> key, CallbackInfo callback) {
        if (this instanceof PlayerCharacter carrier && carrier.dnd5e$isScaleKey(key)) {
            ((Entity) (Object) this).refreshDimensions();
        }
    }
}
