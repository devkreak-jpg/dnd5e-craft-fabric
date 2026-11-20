package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class MasterworkArmorRenderer implements ArmorRenderer {
    private EntityModelSet modelSet;
    private HumanoidModel<LivingEntity> inner;
    private HumanoidModel<LivingEntity> outer;

    public static void register() {
        ArmorRenderer.register(new MasterworkArmorRenderer(), ModItems.STUDDED_LEATHER_ARMOR, ModItems.SCALE_MAIL,
                ModItems.BREASTPLATE, ModItems.HALF_PLATE, ModItems.SPLINT_ARMOR, ModItems.PLATE_ARMOR);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, ItemStack stack, LivingEntity entity,
            EquipmentSlot slot, int light, HumanoidModel<LivingEntity> context) {
        var models = Minecraft.getInstance().getEntityModels();
        if (models != modelSet || outer == null) {
            modelSet = models;
            inner = new HumanoidModel<>(models.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            outer = new HumanoidModel<>(models.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        }
        boolean leggings = slot == EquipmentSlot.LEGS;
        var model = leggings ? inner : outer;
        context.copyPropertiesTo(model);
        model.setAllVisible(false);
        model.body.visible = true;
        model.leftArm.visible = model.rightArm.visible = slot == EquipmentSlot.CHEST;
        model.leftLeg.visible = model.rightLeg.visible = leggings;
        model.head.visible = slot == EquipmentSlot.HEAD;
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        ArmorRenderer.renderPart(pose, buffers, light, stack, model,
                Dnd5eCraftMod.id("textures/models/armor/" + name + "_layer_" + (leggings ? 2 : 1) + ".png"));
    }
}
