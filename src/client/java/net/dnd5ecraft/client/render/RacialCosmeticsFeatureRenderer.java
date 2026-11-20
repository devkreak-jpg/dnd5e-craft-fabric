package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.EnumMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

public final class RacialCosmeticsFeatureRenderer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final net.minecraft.resources.ResourceLocation ATLAS_TEXTURE = Dnd5eCraftMod.id("textures/entity/racial_cosmetics_atlas.png");
    private record Detail(ModelPart root) {}
    private static final EnumMap<RaceType, Detail> DETAILS = new EnumMap<>(RaceType.class);
    static {
        for (var race : new RaceType[]{RaceType.LOXODON, RaceType.MINOTAUR, RaceType.TRITON, RaceType.ELF,
                RaceType.HALF_ELF, RaceType.DWARF, RaceType.TIEFLING, RaceType.DRAGONBORN}) DETAILS.put(race, create(race));
    }

    public RacialCosmeticsFeatureRenderer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    private static Detail create(RaceType race) {
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        var head = CubeListBuilder.create();
        var body = CubeListBuilder.create();
        switch (race) {
            case LOXODON -> {
                head.texOffs(16, 0).addBox(-8, -7.2f, -1, 4, 7, 2).addBox(4, -7.2f, -1, 4, 7, 2);
                root.addOrReplaceChild("trunk", CubeListBuilder.create().texOffs(16, 0)
                        .addBox(-1.8f, -4, -5.3f, 3.6f, 6, 2.5f).addBox(-1.35f, 1.8f, -6.3f, 2.7f, 3, 2), PartPose.ZERO);
            }
            case MINOTAUR -> {
                for (int s : new int[]{-1, 1}) {
                    head.texOffs(0, 11).addBox(s * 4 - 1, -8, -1, 2, 2, 2)
                            .texOffs(0, 6).addBox(s * 5.5f - 1, -9.5f, -.8f, 2, 1.6f, 1.6f)
                            .texOffs(0, 0).addBox(s * 6.3f - .55f, -11, -.5f, 1.1f, 1.7f, 1.2f);
                }
            }
            case TRITON -> {
                head.texOffs(48, 0).addBox(-6.3f, -6, -.75f, 2.3f, 3.2f, 1.5f).addBox(4, -6, -.75f, 2.3f, 3.2f, 1.5f);
                body.texOffs(48, 0).addBox(-8, 4, 1.5f, .9f, 6, 2.5f).addBox(7, 4, 1.5f, .9f, 6, 2.5f);
            }
            case TIEFLING, DRAGONBORN -> {
                head.texOffs(race == RaceType.TIEFLING ? 48 : 0, race == RaceType.TIEFLING ? 23 : 9)
                        .addBox(-4, -10, -2, 1.6f, 3, 2).addBox(2.5f, -10, -2, 1.6f, 3, 2);
                head.texOffs(0, 0).addBox(-4, -11.5f, -1.2f, 1, 2, 1.5f).addBox(3, -11.5f, -1.2f, 1, 2, 1.5f);
                body.texOffs(race == RaceType.TIEFLING ? 48 : 32, 16).addBox(-1.3f, 9, 1.8f, 2.6f, 3, 5).addBox(-1.0f, 10.4f, 6, 2, 2.4f, 3.7f);
            }
            case DWARF -> {
                head.texOffs(0, 16).addBox(-4, -1.5f, -4.55f, 4, 3.5f, 1.6f).addBox(0, -1.5f, -4.55f, 4, 3.5f, 1.6f).addBox(-3, 1.5f, -4.3f, 6, 4, 1.5f);
            }
            case ELF, HALF_ELF -> {
                head.texOffs(16, 16).addBox(-6, -5, -1, 2.3f, 2, 2).addBox(3.8f, -5, -1, 2.3f, 2, 2);
                head.addBox(-6.5f, -6.2f, -.6f, 1, 1.5f, 1.4f).addBox(5.5f, -6.2f, -.6f, 1, 1.5f, 1.4f);
            }
        }
        root.addOrReplaceChild("head", head, PartPose.ZERO);
        root.addOrReplaceChild("body", body, PartPose.ZERO);
        if (race == RaceType.LOXODON) root.addOrReplaceChild("tusks", CubeListBuilder.create().texOffs(32, 0)
                .addBox(-3.6f, -1.8f, -6, 1.2f, 1, 3).addBox(2.4f, -1.8f, -6, 1.2f, 1, 3)
                .addBox(-3.55f, -2.45f, -6, 1, .8f, 1).addBox(2.45f, -2.45f, -6, 1, .8f, 1), PartPose.ZERO);
        return new Detail(LayerDefinition.create(mesh, 64, 64).bakeRoot());
    }

    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                float limbSwing, float limbAmount, float partialTick, float age, float yaw, float pitch) {
        var client = Minecraft.getInstance();
        if (!(player instanceof PlayerCharacter character) || client.player == null || player.isInvisibleTo(client.player)
                || player == client.getCameraEntity() && client.options.getCameraType().isFirstPerson()
                || !character.dnd5e$showsCosmetics() || character.dnd5e$disguise() != 0) return;
        var race = character.dnd5e$race();
        var detail = DETAILS.get(race);
        if (detail == null) return;
        var helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        boolean wearingHelmet = !helmet.isEmpty();
        var root = detail.root();
        var vertex = buffers.getBuffer(RenderType.entityCutoutNoCull(ATLAS_TEXTURE));
        int overlay = net.minecraft.client.renderer.entity.LivingEntityRenderer.getOverlayCoords(player, 0);
        pose.pushPose();
        getParentModel().head.translateAndRotate(pose);
        if (wearingHelmet && race == RaceType.DWARF) pose.translate(0, 0, -.45f / 16);
        if (!wearingHelmet || race != RaceType.ELF && race != RaceType.HALF_ELF && race != RaceType.TRITON)
            root.getChild("head").render(pose, vertex, light, overlay, 1f, 1f, 1f, 1f);
        if (race == RaceType.LOXODON) {
            root.getChild("tusks").render(pose, vertex, light, overlay);
            if (wearingHelmet) pose.translate(0, 0, -.45f / 16);
            root.getChild("trunk").render(pose, vertex, light, overlay);
        }
        pose.popPose();
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        root.getChild("body").render(pose, vertex, light, overlay);
        pose.popPose();
    }
}
