package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public final class EldritchCannonRenderer extends EntityRenderer<EldritchCannonEntity> {
    private static final ResourceLocation BRASS = Dnd5eCraftMod.id("textures/entity/eldritch_cannon.png");
    private static final ResourceLocation RUNE = Dnd5eCraftMod.id("textures/entity/cosmetic_linen.png");
    private final ModelPart chassis;
    private final ModelPart barrel;
    private final ModelPart core;
    private final ModelPart[] legs = new ModelPart[8];

    public EldritchCannonRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = .26f;
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        root.addOrReplaceChild("chassis", CubeListBuilder.create().texOffs(0, 0).addBox(-5, -3, -5, 10, 6, 10),
                PartPose.ZERO);
        root.addOrReplaceChild("barrel", CubeListBuilder.create().texOffs(0, 16).addBox(-2, -2, -9, 4, 4, 9),
                PartPose.offset(0, -4, 0));
        root.addOrReplaceChild("core", CubeListBuilder.create().texOffs(0, 0).addBox(-2, -2.4f, -2, 4, 4, 4),
                PartPose.offset(0, -5.2f, 1.6f));
        for (int i = 0; i < legs.length; i++) {
            float side = i % 2 == 0 ? 1 : -1;
            float fore = (i / 2 - 1.5f) * 2.6f;
            root.addOrReplaceChild("leg" + i, CubeListBuilder.create().texOffs(22, 0)
                    .addBox(side > 0 ? 0 : -5, 0, -.65f, 5, 1, 1.3f)
                    .addBox(side * 4.4f - .6f, .8f, -.65f, 1.2f, 4, 1.3f),
                    PartPose.offset(side * 4, 1.2f, fore));
        }
        var baked = LayerDefinition.create(mesh, 48, 32).bakeRoot();
        chassis = baked.getChild("chassis");
        barrel = baked.getChild("barrel");
        core = baked.getChild("core");
        for (int i = 0; i < legs.length; i++) legs[i] = baked.getChild("leg" + i);
    }

    @Override public ResourceLocation getTextureLocation(EldritchCannonEntity entity) { return BRASS; }

    @Override public void render(EldritchCannonEntity entity, float yaw, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0, .27, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        pose.scale(.78f, -.78f, .78f);
        var solid = buffers.getBuffer(RenderType.entityCutout(BRASS));
        chassis.render(pose, solid, light, OverlayTexture.NO_OVERLAY);
        barrel.z = Math.max(0, entity.recoil() - partialTick) * .46f;
        barrel.render(pose, solid, light, OverlayTexture.NO_OVERLAY);
        float movement = entity.perched() ? .16f : Math.min(1, entity.walkAnimation.speed(partialTick) * 4);
        float phase = entity.tickCount + partialTick;
        for (int i = 0; i < legs.length; i++) {
            legs[i].yRot = (float) Math.sin(phase * .58 + i * 1.3) * movement * .29f;
            legs[i].zRot = entity.perched() ? (i % 2 == 0 ? -.4f : .4f) : 0;
            legs[i].render(pose, solid, light, OverlayTexture.NO_OVERLAY);
        }
        float[] tone = switch (entity.mode()) {
            case FLAMETHROWER -> new float[]{1f, .37f, .09f};
            case FORCE_BALLISTA -> new float[]{.18f, .9f, 1f};
            case PROTECTOR -> new float[]{1f, .8f, .25f};
        };
        core.yRot = phase * .08f;
        core.render(pose, buffers.getBuffer(RenderType.eyes(RUNE)), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                tone[0], tone[1], tone[2], 1);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }
}
