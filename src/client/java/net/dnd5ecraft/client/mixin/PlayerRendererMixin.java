package net.dnd5ecraft.client.mixin;

import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.client.render.RacialCosmeticsFeatureRenderer;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    protected PlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadow) {
        super(context, model, shadow);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    @Unique
    private void dnd5e$addRacialFeatures(EntityRendererProvider.Context context, boolean slim, CallbackInfo callback) {
        addLayer(new RacialCosmeticsFeatureRenderer(this));
    }

    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$renderDisguise(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> callback) {
        int index = ((PlayerCharacter) player).dnd5e$disguise();
        if (index > 0 && index <= 3) callback.setReturnValue(Dnd5eCraftMod.id("textures/entity/crowd/townsfolk_" + (index - 1) + ".png"));
    }

    @Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    @Unique
    private void dnd5e$disguiseName(AbstractClientPlayer player, net.minecraft.network.chat.Component name,
                                    com.mojang.blaze3d.vertex.PoseStack pose,
                                    net.minecraft.client.renderer.MultiBufferSource buffers, int light, CallbackInfo callback) {
        if (((PlayerCharacter) player).dnd5e$disguise() > 0) {
            super.renderNameTag(player, net.minecraft.network.chat.Component.translatable("dnd-5e-craft.magic.disguise"), pose, buffers, light);
            callback.cancel();
        }
    }
}
