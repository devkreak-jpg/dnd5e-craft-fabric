package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Locale;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.minecraft.resources.ResourceLocation;

public enum NpcSkinCatalog {
    GOBLIN_RAIDER(Category.HUMANOID),
    GOBLIN_ARCHER(Category.HUMANOID),
    KOBOLD_SKIRMISHER(Category.HUMANOID),
    KOBOLD_SHAMAN(Category.HUMANOID),
    ORC_BERSERKER(Category.HUMANOID),
    BANDIT_THUG(Category.HUMANOID),
    BANDIT_CROSSBOWMAN(Category.HUMANOID),
    BANDIT_CUTTHROAT(Category.HUMANOID),
    CULTIST_ACOLYTE(Category.HUMANOID),
    CULTIST_NECROMANCER(Category.HUMANOID),
    HOBGOBLIN_LEGIONARY(Category.HUMANOID),
    DROW_WARRIOR(Category.HUMANOID),
    ASSASSIN_SHADOW(Category.HUMANOID),
    CULT_FANATIC(Category.HUMANOID),
    SKELETON_WARRIOR(Category.UNDEAD),
    SKELETON_ARCHER(Category.UNDEAD),
    ZOMBIE_SHAMBLER(Category.UNDEAD),
    GHOUL_STALKER(Category.UNDEAD),
    VAMPIRE_SPAWN(Category.UNDEAD),
    MINOTAUR_SKELETON(Category.UNDEAD),
    WIGHT_CAPTAIN(Category.UNDEAD),
    MUMMY_GUARDIAN(Category.UNDEAD),
    SPECTER_WRAITH(Category.UNDEAD),
    BUGBEAR_BRUISER(Category.MONSTROUS),
    OGRE_CRUSHER(Category.MONSTROUS),
    TROLL_REGENERATOR(Category.MONSTROUS),
    GNOLL_HUNTER(Category.MONSTROUS),
    LIZARDFOLK_SHAMAN(Category.MONSTROUS),
    SAHUAGIN_RAIDER(Category.MONSTROUS),
    HILL_GIANT(Category.MONSTROUS),
    WOLF(Category.MONSTROUS),
    DIRE_WOLF(Category.MONSTROUS),
    OWLBEAR(Category.MONSTROUS),
    ANIMATED_ARMOR(Category.FIEND_CONSTRUCT),
    STONE_GARGOYLE(Category.FIEND_CONSTRUCT),
    STONE_GOLEM(Category.FIEND_CONSTRUCT),
    BEARDED_DEVIL(Category.FIEND_CONSTRUCT),
    GOBLIN_BOSS(Category.BOSS),
    ORC_WARLORD(Category.BOSS),
    BANDIT_CAPTAIN_BOSS(Category.BOSS),
    DRAGONBORN_KNIGHT_BOSS(Category.BOSS),
    LICH_ARCHMAGE(Category.BOSS),
    DEATH_KNIGHT_BOSS(Category.BOSS),
    YOUNG_RED_DRAGON(Category.BOSS),
    TAVERN_KEEPER_BORIS(Category.TOWN),
    MERCHANT_ELARA(Category.TOWN),
    TOWN_GUARD_CAPTAIN(Category.TOWN),
    TOWN_GUARD_SPEARMAN(Category.TOWN),
    BLACKSMITH_TORIN(Category.TOWN),
    BARD_MINSTREL(Category.TOWN),
    NOBLE_LORD(Category.TOWN),
    NOBLE_LADY(Category.TOWN),
    PEASANT_MALE(Category.TOWN),
    PEASANT_FEMALE(Category.TOWN),
    WIZARD_SCHOLAR(Category.TOWN),
    MYSTERIOUS_STRANGER(Category.TOWN),
    ALCHEMIST_GOGGLES(Category.TOWN),
    PLAGUE_DOCTOR(Category.TOWN),
    PIRATE_CORSAIR(Category.TOWN),
    EASTERN_SPICE_MERCHANT(Category.TOWN),
    ELVEN_RANGER(Category.TOWN),
    DWARF_MINER(Category.TOWN),
    ROYAL_PALADIN(Category.TOWN),
    CLERIC_PRIESTESS(Category.TOWN),
    DESERT_MONK(Category.TOWN),
    COURT_JESTER(Category.TOWN),
    STREET_URCHIN(Category.TOWN),
    VILLAGE_ELDER(Category.TOWN);

    public enum Category { TOWN, HUMANOID, UNDEAD, MONSTROUS, FIEND_CONSTRUCT, BOSS }

    private final Category category;

    NpcSkinCatalog(Category category) { this.category = category; }

    public Category category() { return category; }

    public static final List<NpcSkinCatalog> ALL = List.of(values());

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public ResourceLocation texture() {
        return Dnd5eCraftMod.id("textures/entity/skins/" + id() + ".png");
    }
}
