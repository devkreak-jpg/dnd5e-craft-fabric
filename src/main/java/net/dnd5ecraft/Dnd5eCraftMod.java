package net.dnd5ecraft;

import net.dnd5ecraft.entity.DiceDisplaySpawner;
import net.dnd5ecraft.entity.ModEntities;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.server.DialogueSessions;
import net.dnd5ecraft.server.DmMode;
import net.dnd5ecraft.server.DndCommands;
import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.server.TacticalCombat;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.LoggerFactory;

public final class Dnd5eCraftMod implements ModInitializer {
    public static final String MOD_ID = "dnd-5e-craft";

    @Override
    public void onInitialize() {
        ModEntities.register();
        ModItems.register();
        PlayerSheets.register();
        net.dnd5ecraft.server.CampaignPhysics.register();
        net.dnd5ecraft.server.CampaignMagic.register();
        net.dnd5ecraft.server.CampaignPoses.register();
        net.dnd5ecraft.server.DmDirector.register();
        DiceDisplaySpawner.register();
        DialogueSessions.register();
        DmMode.register();
        net.dnd5ecraft.server.CinematicFreeze.register();
        TacticalCombat.register();
        DndCommands.register();
        ModPackets.register();
        LoggerFactory.getLogger(MOD_ID).info("Initialized DnD 5e Craft");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
