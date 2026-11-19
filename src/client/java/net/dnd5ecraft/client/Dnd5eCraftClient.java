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
        ClientPacketHandlers.register();
        ModKeybinds.register();
        CombatHudOverlay.register();
        EntityRendererRegistry.register(ModEntities.DND_NPC, DndNpcEntityRenderer::new);
        TacticalGridWorldRenderer.register();
        FloatingCombatBarRenderer.register();
        Spinning3dDieRenderer.register();
    }
}
