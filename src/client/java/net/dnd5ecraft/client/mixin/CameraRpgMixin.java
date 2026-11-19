package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.client.ClientDndState;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraRpgMixin {
    @Shadow public abstract Vec3 getPosition();
    @Shadow public abstract float getYRot();
    @Shadow public abstract float getXRot();
    @Shadow protected abstract void move(double distance, double vertical, double horizontal);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    @Unique
    private void dnd5e$tacticalCamera(BlockGetter level, Entity viewer, boolean detached, boolean reverse,
            float delta, CallbackInfo callback) {
        if (ClientDndState.combat() != null && detached && !reverse) {
            Vec3 base = getPosition();
            move(0, .18, .52);
            var collision = level.clip(new ClipContext(base, getPosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
            if (collision.getType() != HitResult.Type.MISS) setPosition(base.lerp(collision.getLocation(), .7));
        }
        double shake = ClientDndState.shake();
        if (shake > 0) {
            double phase = Util.getMillis() / 13.0;
            setRotation(getYRot() + (float) (Math.sin(phase) * shake * .23),
                    getXRot() + (float) (Math.cos(phase) * shake * .18));
        }
    }
}
