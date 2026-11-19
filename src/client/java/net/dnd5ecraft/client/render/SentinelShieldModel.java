package net.dnd5ecraft.client.render;

import java.util.function.Supplier;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

public final class SentinelShieldModel extends ForwardingBakedModel {
    private static final ResourceLocation EYE = Dnd5eCraftMod.id("item/sentinel_eye");
    private final RenderMaterial standard, glowing;

    private SentinelShieldModel(BakedModel model, RenderMaterial standard, RenderMaterial glowing) {
        wrapped = model;
        this.standard = standard;
        this.glowing = glowing;
    }

    public static void register() {
        var equipped = new ModelResourceLocation(Dnd5eCraftMod.id("sentinel_shield"), "inventory");
        var blocking = Dnd5eCraftMod.id("item/sentinel_shield_blocking");
        ModelLoadingPlugin.register(context -> context.modifyModelAfterBake().register((model, bake) -> {
            if (model == null || !equipped.equals(bake.id()) && !blocking.equals(bake.id())) return model;
            var renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer == null) return model;
            return new SentinelShieldModel(model, renderer.materialFinder().find(), renderer.materialFinder().emissive(true).find());
        }));
    }

    @Override public boolean isVanillaAdapter() { return false; }

    @Override public void emitItemQuads(ItemStack stack, Supplier<RandomSource> random, RenderContext context) {
        for (Direction face : Direction.values()) emit(face, random, context);
        emit(null, random, context);
    }

    private void emit(Direction face, Supplier<RandomSource> random, RenderContext context) {
        for (var quad : wrapped.getQuads(null, face, random.get())) {
            var material = EYE.equals(quad.getSprite().contents().name()) ? glowing : standard;
            context.getEmitter().fromVanilla(quad, material, face).emit();
        }
    }
}
