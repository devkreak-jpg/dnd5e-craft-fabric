package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.EnumMap;
import java.util.Map;
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
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class RacialCosmeticsFeatureRenderer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final net.minecraft.resources.ResourceLocation TEXTURE = Dnd5eCraftMod.id("textures/entity/cosmetic_linen.png");
    private record Detail(ModelPart head, ModelPart body, float r, float g, float b) {}
    private final Map<RaceType, Detail> details = new EnumMap<>(RaceType.class);

    public RacialCosmeticsFeatureRenderer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
        for (var race : new RaceType[]{RaceType.LOXODON, RaceType.MINOTAUR, RaceType.TRITON, RaceType.ELF,
                RaceType.HALF_ELF, RaceType.DWARF, RaceType.TIEFLING, RaceType.DRAGONBORN}) details.put(race, create(race));
    }

    private static Detail create(RaceType race) {
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        var head = CubeListBuilder.create().texOffs(0, 0);
        var body = CubeListBuilder.create().texOffs(0, 0);
        float[] tint = switch (race) {
            case LOXODON -> {
                head.addBox(-8, -7.2f, -1, 4, 7, 2).addBox(4, -7.2f, -1, 4, 7, 2)
                        .addBox(-1.8f, -4, -5.3f, 3.6f, 6, 2.5f).addBox(-1.35f, 1.8f, -6.3f, 2.7f, 3, 2);
                yield new float[]{.62f,.61f,.66f};
            }
            case MINOTAUR -> {
                for (int s : new int[]{-1, 1}) {
                    head.addBox(s * 4 - 1, -8, -1, 2, 2, 2)
                            .addBox(s * 5.5f - 1, -9.5f, -.8f, 2, 1.6f, 1.6f)
                            .addBox(s * 6.3f - .55f, -11, -.5f, 1.1f, 1.7f, 1.2f);
                }
                yield new float[]{.97f,.79f,.54f};
            }
            case TRITON -> {
                head.addBox(-6.3f, -6, -.75f, 2.3f, 3.2f, 1.5f).addBox(4, -6, -.75f, 2.3f, 3.2f, 1.5f);
                body.addBox(-8, 4, 1.5f, .9f, 6, 2.5f).addBox(7, 4, 1.5f, .9f, 6, 2.5f);
                yield new float[]{.18f,.78f,.79f};
            }
            case TIEFLING, DRAGONBORN -> {
                head.addBox(-4, -10, -2, 1.6f, 3, 2).addBox(2.5f, -10, -2, 1.6f, 3, 2);
                head.addBox(-4, -11.5f, -1.2f, 1, 2, 1.5f).addBox(3, -11.5f, -1.2f, 1, 2, 1.5f);
                body.addBox(-1.3f, 9, 1.8f, 2.6f, 3, 5).addBox(-1.0f, 10.4f, 6, 2, 2.4f, 3.7f);
                yield race == RaceType.TIEFLING ? new float[]{.71f,.24f,.3f} : new float[]{.51f,.66f,.32f};
            }
            case DWARF -> {
                head.addBox(-4, -1.5f, -4.55f, 8, 3.5f, 1.6f).addBox(-3, 1.5f, -4.3f, 6, 4, 1.5f);
                yield new float[]{.55f,.32f,.18f};
            }
            default -> {
                head.addBox(-6, -5, -1, 2.3f, 2, 2).addBox(3.8f, -5, -1, 2.3f, 2, 2);
                head.addBox(-6.5f, -6.2f, -.6f, 1, 1.5f, 1.4f).addBox(5.5f, -6.2f, -.6f, 1, 1.5f, 1.4f);
                yield new float[]{.86f,.61f,.48f};
            }
        };
        root.addOrReplaceChild("head", head, PartPose.ZERO);
        root.addOrReplaceChild("body", body, PartPose.ZERO);
        if (race == RaceType.LOXODON) root.addOrReplaceChild("tusks", CubeListBuilder.create().texOffs(0,0)
                .addBox(-3.6f, -1.8f, -6, 1.2f, 1, 3).addBox(2.4f, -1.8f, -6, 1.2f, 1, 3)
                .addBox(-3.55f, -2.45f, -6, 1, .8f, 1).addBox(2.45f, -2.45f, -6, 1, .8f, 1), PartPose.ZERO);
        var baked = LayerDefinition.create(mesh, 32, 32).bakeRoot();
        return new Detail(baked, baked.getChild("body"), tint[0], tint[1], tint[2]);
    }

    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                float limbSwing, float limbAmount, float partialTick, float age, float yaw, float pitch) {
        var character = (PlayerCharacter) player;
        var race = character.dnd5e$race();
        var detail = details.get(race);
        if (detail == null || !character.dnd5e$showsCosmetics() || player.isInvisible() || character.dnd5e$disguise() != 0) return;
        var vertex = buffers.getBuffer(RenderType.entityCutout(TEXTURE));
        pose.pushPose();
        getParentModel().head.translateAndRotate(pose);
        detail.head().getChild("head").render(pose, vertex, light, OverlayTexture.NO_OVERLAY, detail.r(), detail.g(), detail.b(), 1);
        if (race == RaceType.LOXODON) detail.head().getChild("tusks").render(pose, vertex, light, OverlayTexture.NO_OVERLAY, .97f, .9f, .71f, 1);
        pose.popPose();
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        detail.body().render(pose, vertex, light, OverlayTexture.NO_OVERLAY, detail.r(), detail.g(), detail.b(), 1);
        pose.popPose();
    }
}
