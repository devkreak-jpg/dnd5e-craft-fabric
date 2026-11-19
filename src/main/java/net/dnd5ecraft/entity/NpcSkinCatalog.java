package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Locale;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.minecraft.resources.ResourceLocation;

public enum NpcSkinCatalog {
    GOBLIN_RAIDER, GOBLIN_ARCHER, GOBLIN_BOSS, KOBOLD_SKIRMISHER, KOBOLD_SHAMAN,
    SKELETON_WARRIOR, SKELETON_ARCHER, ZOMBIE_SHAMBLER, GHOUL_STALKER, ORC_BERSERKER, ORC_WARLORD,
    BANDIT_THUG, BANDIT_CROSSBOWMAN, BANDIT_CUTTHROAT, BANDIT_CAPTAIN_BOSS, BUGBEAR_BRUISER, OGRE_CRUSHER,
    CULTIST_ACOLYTE, CULTIST_NECROMANCER, TROLL_REGENERATOR, VAMPIRE_SPAWN, DRAGONBORN_KNIGHT_BOSS,
    TAVERN_KEEPER_BORIS, MERCHANT_ELARA, TOWN_GUARD_CAPTAIN, TOWN_GUARD_SPEARMAN, BLACKSMITH_TORIN,
    BARD_MINSTREL, NOBLE_LORD, NOBLE_LADY, PEASANT_MALE, PEASANT_FEMALE, WIZARD_SCHOLAR, MYSTERIOUS_STRANGER;

    public static final List<NpcSkinCatalog> ALL = List.of(values());

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public ResourceLocation texture() {
        return Dnd5eCraftMod.id("textures/entity/skins/" + id() + ".png");
    }
}
