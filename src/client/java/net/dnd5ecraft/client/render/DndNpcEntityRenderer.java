package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class DndNpcEntityRenderer extends HumanoidMobRenderer<DndNpcEntity, HumanoidModel<DndNpcEntity>> {
    private static final ResourceLocation ADVENTURER = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation UNDEAD = new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png");
    private static final ResourceLocation SKELETON = new ResourceLocation("minecraft", "textures/entity/skeleton/skeleton.png");

    public DndNpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), .5f);
    }

    @Override public ResourceLocation getTextureLocation(DndNpcEntity npc) {
        var override = npc.skin().filter(id -> net.minecraft.client.Minecraft.getInstance()
                .getResourceManager().getResource(id).isPresent());
        if (override.isPresent()) return override.orElseThrow();
        return switch (npc.preset()) {
            case SKELETON -> SKELETON;
            case ZOMBIE, VAMPIRE, VAMPIRE_SPAWN, GHOUL -> UNDEAD;
            default -> ADVENTURER;
        };
    }

    @Override protected void scale(DndNpcEntity npc, PoseStack pose, float partialTick) {
        float scale = npc.physicalScale();
        pose.scale(scale, scale, scale);
    }
}
