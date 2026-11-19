package net.dnd5ecraft.server;

import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

public final class CampaignCharacters {
    private CampaignCharacters() {}

    public static void install(ServerPlayer player, CampaignHero hero, CharacterSheetData sheet) {
        PlayerRules.requireDowntime(player);
        boolean first = PlayerSheets.get(player).isEmpty();
        if (!first && !player.hasPermissions(2)) throw new IllegalStateException("You already have a character");
        PlayerSheets.set(player, sheet);
        player.setHealth(Math.min(player.getMaxHealth(), sheet.hitPoints().current()));
        if (first) {
            switch (hero) {
                case FINNEAS -> {
                    equip(player, EquipmentSlot.CHEST, ModItems.SCALE_MAIL);
                    equip(player, EquipmentSlot.OFFHAND, ModItems.SENTINEL_SHIELD);
                    give(player, ModItems.REPEATING_LIGHT_CROSSBOW, ModItems.BAG_OF_HOLDING, ModItems.THIEVES_TOOLS,
                            ModItems.ELDRITCH_CANNON_CONTROLLER);
                }
                case ALEXANDER -> give(player, ModItems.WORLDBREAKER_WRAPS);
                case HARLEQUIN -> give(player, ModItems.HARLEQUIN_DECK_WAND);
                case MINOTAUR -> {
                    equip(player, EquipmentSlot.CHEST, ModItems.SPLINT_ARMOR);
                    give(player, ModItems.weapon(Weapon.LONGSWORD), ModItems.ECHO_SIGIL);
                }
            }
        }
        ModPackets.sendSheet(player, true);
    }

    private static void equip(ServerPlayer player, EquipmentSlot slot, Item item) {
        if (player.getItemBySlot(slot).isEmpty()) player.setItemSlot(slot, item.getDefaultInstance());
        else give(player, item);
    }

    private static void give(ServerPlayer player, Item... items) {
        for (Item item : items) {
            var stack = item.getDefaultInstance();
            if (!player.addItem(stack)) player.drop(stack, false);
        }
    }
}
