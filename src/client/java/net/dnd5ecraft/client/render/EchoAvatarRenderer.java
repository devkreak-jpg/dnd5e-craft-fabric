package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;

public final class EchoAvatarRenderer extends HumanoidMobRenderer<EchoAvatarEntity, HumanoidModel<EchoAvatarEntity>> {
    private static final ResourceLocation TEXTURE = Dnd5eCraftMod.id("textures/entity/echo_avatar.png");
    private static final ResourceLocation RIM = Dnd5eCraftMod.id("textures/entity/echo_rim.png");

    private static final class EchoModel extends HumanoidModel<EchoAvatarEntity> {
        EchoModel(ModelPart root) { super(root); hat.visible = false; }

        @Override public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                float red, float green, float blue, float alpha) {
            super.renderToBuffer(pose, buffer, light, overlay, .22f, .25f, .34f, .62f * alpha);
        }

        void rim(PoseStack pose, MultiBufferSource buffers, float pulse) {
            super.renderToBuffer(pose, buffers.getBuffer(RenderType.eyes(RIM)),
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    .4f + .2f * pulse, .65f - .16f * pulse, .94f, .45f);
        }
    }

    public EchoAvatarRenderer(EntityRendererProvider.Context context) {
        super(context, new EchoModel(context.bakeLayer(ModelLayers.PLAYER)), .27f);
        layers.removeIf(layer -> layer instanceof ItemInHandLayer<?, ?>);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()) {
            @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, EchoAvatarEntity echo,
                    float swing, float amount, float partialTick, float age, float yaw, float pitch) {
                if (echo.isInvisible()) return;
                ((EchoModel)getParentModel()).rim(pose, buffers, (float) Math.sin((echo.tickCount + partialTick) * .12));
                var owner = echo.ownerId().map(echo.level()::getPlayerByUUID).orElse(null);
                if (owner == null || owner.getMainHandItem().isEmpty()) return;
                var hand = owner.getMainArm();
                renderArmWithItem(echo, owner.getMainHandItem(), hand == HumanoidArm.RIGHT
                        ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                        hand, pose, buffers, light);
            }
        });
    }

    @Override public ResourceLocation getTextureLocation(EchoAvatarEntity entity) { return TEXTURE; }

    @Override protected RenderType getRenderType(EchoAvatarEntity entity, boolean visible, boolean translucent, boolean glowing) {
        return visible || translucent ? RenderType.entityTranslucent(TEXTURE) : glowing ? RenderType.outline(TEXTURE) : null;
    }

    @Override protected void scale(EchoAvatarEntity entity, PoseStack pose, float partialTick) {
        var owner = entity.ownerId().map(entity.level()::getPlayerByUUID).orElse(null);
        if (owner instanceof PlayerCharacter character) {
            float size = character.dnd5e$physicalScale();
            pose.scale(size, size, size);
        }
    }
}
