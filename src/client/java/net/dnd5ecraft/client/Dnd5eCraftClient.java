package net.dnd5ecraft.client;

import net.dnd5ecraft.client.gui.hud.CombatHudOverlay;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.client.render.DndNpcEntityRenderer;
import net.dnd5ecraft.client.render.FloatingCombatBarRenderer;
import net.dnd5ecraft.client.render.Spinning3dDieRenderer;
import net.dnd5ecraft.client.render.TacticalGridWorldRenderer;
import net.dnd5ecraft.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class Dnd5eCraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.dnd5ecraft.client.render.SentinelShieldModel.register();
        net.dnd5ecraft.client.render.MasterworkArmorRenderer.register();
        net.minecraft.client.renderer.item.ItemProperties.register(net.dnd5ecraft.item.ModItems.SENTINEL_SHIELD,
                new net.minecraft.resources.ResourceLocation("blocking"),
                (stack, level, wearer, seed) -> wearer != null && wearer.isUsingItem() && wearer.getUseItem() == stack ? 1 : 0);
        ClientPacketHandlers.register();
        ClientCampaign.register();
        ModKeybinds.register();
        CombatHudOverlay.register();
        EntityRendererRegistry.register(ModEntities.DND_NPC, DndNpcEntityRenderer::new);
        DndNpcEntityRenderer.registerAmbientParticles();
        EntityRendererRegistry.register(ModEntities.ELDRITCH_CANNON, net.dnd5ecraft.client.render.EldritchCannonRenderer::new);
        EntityRendererRegistry.register(ModEntities.ECHO_AVATAR, net.dnd5ecraft.client.render.EchoAvatarRenderer::new);
        TacticalGridWorldRenderer.register();
        FloatingCombatBarRenderer.register();
        net.dnd5ecraft.client.render.FloatingCombatTextRenderer.register();
        net.dnd5ecraft.client.render.TacticalPingRenderer.register();
        net.dnd5ecraft.client.render.ShortRestEffects.register();
        Spinning3dDieRenderer.register();
    }
}
