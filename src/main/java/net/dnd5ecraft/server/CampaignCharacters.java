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

    static void giveGear(ServerPlayer player, CampaignHero hero) {
        PlayerRules.requireDowntime(player);
        switch (hero) {
            case FINNEAS -> {
                replaceEquipment(player, EquipmentSlot.MAINHAND, ModItems.REPEATING_LIGHT_CROSSBOW);
                replaceEquipment(player, EquipmentSlot.OFFHAND, ModItems.SENTINEL_SHIELD);
                replaceEquipment(player, EquipmentSlot.CHEST, ModItems.SCALE_MAIL);
                give(player, ModItems.ELDRITCH_CANNON_CONTROLLER, ModItems.BAG_OF_HOLDING, ModItems.THIEVES_TOOLS);
            }
            case ALEXANDER -> {
                replaceEquipment(player, EquipmentSlot.MAINHAND, ModItems.WORLDBREAKER_WRAPS);
                give(player, ModItems.weapon(Weapon.HANDAXE), ModItems.weapon(Weapon.HANDAXE));
            }
            case HARLEQUIN -> replaceEquipment(player, EquipmentSlot.MAINHAND, ModItems.HARLEQUIN_DECK_WAND);
            case MINOTAUR -> {
                replaceEquipment(player, EquipmentSlot.MAINHAND, ModItems.weapon(Weapon.GREATAXE));
                replaceEquipment(player, EquipmentSlot.CHEST, ModItems.SPLINT_ARMOR);
                give(player, ModItems.ECHO_SIGIL);
            }
        }
    }

    private static void replaceEquipment(ServerPlayer player, EquipmentSlot slot, Item item) {
        var previous = player.getItemBySlot(slot);
        if (previous.is(item)) return;
        player.setItemSlot(slot, item.getDefaultInstance());
        if (!previous.isEmpty() && !player.addItem(previous)) player.drop(previous, false);
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
