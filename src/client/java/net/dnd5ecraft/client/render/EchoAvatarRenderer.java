package net.dnd5ecraft.client.render;

import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class EchoAvatarRenderer extends HumanoidMobRenderer<EchoAvatarEntity, HumanoidModel<EchoAvatarEntity>> {
    private static final ResourceLocation TEXTURE = Dnd5eCraftMod.id("textures/entity/echo_avatar.png");

    public EchoAvatarRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), .27f);
    }

    @Override public ResourceLocation getTextureLocation(EchoAvatarEntity entity) { return TEXTURE; }

    @Override protected RenderType getRenderType(EchoAvatarEntity entity, boolean visible, boolean translucent, boolean glowing) {
        return visible || translucent ? RenderType.entityTranslucent(TEXTURE) : glowing ? RenderType.outline(TEXTURE) : null;
    }

    @Override protected void scale(EchoAvatarEntity entity, com.mojang.blaze3d.vertex.PoseStack pose, float partialTick) {
        var owner = entity.ownerId().map(entity.level()::getPlayerByUUID).orElse(null);
        if (owner instanceof net.dnd5ecraft.server.PlayerCharacter character) {
            float size = character.dnd5e$physicalScale();
            pose.scale(size, size, size);
        }
    }
}
