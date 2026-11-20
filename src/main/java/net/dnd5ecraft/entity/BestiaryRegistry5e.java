package net.dnd5ecraft.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.dialogue.DialogueTree;
import net.dnd5ecraft.dialogue.DialogueNode;
import net.dnd5ecraft.dialogue.DialogueChoice;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.rules.ability.Skill;

import static net.dnd5ecraft.entity.BestiaryPreset.*;

public final class BestiaryRegistry5e {
    private static final List<BestiaryPreset> PRESETS = List.of(
            BANDIT, BANDIT_CROSSBOWMAN, CULTIST, KOBOLD, KOBOLD_SHAMAN, GOBLIN, GOBLIN_ARCHER, SKELETON,
            SKELETON_ARCHER, ZOMBIE, DROW_WARRIOR, HOBGOBLIN_LEGIONARY, GNOLL_HUNTER, LIZARDFOLK, SAHUAGIN, ORC, WOLF,
            GOBLIN_BOSS, BUGBEAR, GHOUL, SPECTER, ANIMATED_ARMOR, DIRE_WOLF, BANDIT_CUTTHROAT, CULT_FANATIC,
            GARGOYLE, OGRE, MINOTAUR_SKELETON, MUMMY, WIGHT, OWLBEAR, BEARDED_DEVIL, CAPTAIN_MORGAN, DRAGONBORN_KNIGHT,
            TROLL, VAMPIRE_SPAWN, HILL_GIANT, ORC_WARLORD, ASSASSIN, STONE_GOLEM, YOUNG_RED_DRAGON, DEATH_KNIGHT, LICH_ARCHMAGE);

    private static final List<BestiaryPreset> CIVILIANS = List.of(TAVERN_KEEPER, MERCHANT, TOWN_GUARD);
    private static final List<BestiaryPreset> SPAWNABLE = List.of(BestiaryPreset.values());
    private static final List<BestiaryPreset> AMBUSHERS = Arrays.stream(BestiaryPreset.values())
            .filter(preset -> !preset.civilian()).toList();

    private BestiaryRegistry5e() {}

    public static List<BestiaryPreset> presets() { return PRESETS; }
    public static List<BestiaryPreset> civilians() { return CIVILIANS; }
    public static List<BestiaryPreset> spawnable() { return SPAWNABLE; }
    public static List<BestiaryPreset> ambushers() { return AMBUSHERS; }

    public static Optional<NpcSkinCatalog> defaultSkin(BestiaryPreset preset) {
        return Optional.ofNullable(switch (preset) {
            case GOBLIN -> NpcSkinCatalog.GOBLIN_RAIDER;
            case GOBLIN_ARCHER -> NpcSkinCatalog.GOBLIN_ARCHER;
            case KOBOLD -> NpcSkinCatalog.KOBOLD_SKIRMISHER;
            case SKELETON -> NpcSkinCatalog.SKELETON_WARRIOR;
            case ZOMBIE -> NpcSkinCatalog.ZOMBIE_SHAMBLER;
            case ORC -> NpcSkinCatalog.ORC_BERSERKER;
            case BANDIT_CAPTAIN -> NpcSkinCatalog.BANDIT_CUTTHROAT;
            case BANDIT_THUG -> NpcSkinCatalog.BANDIT_THUG;
            case CAPTAIN_MORGAN -> NpcSkinCatalog.BANDIT_CAPTAIN_BOSS;
            case BUGBEAR -> NpcSkinCatalog.BUGBEAR_BRUISER;
            case GHOUL -> NpcSkinCatalog.GHOUL_STALKER;
            case OGRE -> NpcSkinCatalog.OGRE_CRUSHER;
            case CULTIST_ACOLYTE -> NpcSkinCatalog.CULTIST_ACOLYTE;
            case TROLL -> NpcSkinCatalog.TROLL_REGENERATOR;
            case VAMPIRE, VAMPIRE_SPAWN -> NpcSkinCatalog.VAMPIRE_SPAWN;
            case ADULT_RED_DRAGON -> NpcSkinCatalog.DRAGONBORN_KNIGHT_BOSS;
            case YOUNG_RED_DRAGON -> NpcSkinCatalog.YOUNG_RED_DRAGON;
            case OWLBEAR -> NpcSkinCatalog.OWLBEAR;
            case WOLF -> NpcSkinCatalog.WOLF;
            case DIRE_WOLF -> NpcSkinCatalog.DIRE_WOLF;
            case BANDIT -> NpcSkinCatalog.BANDIT_THUG;
            case BANDIT_CROSSBOWMAN -> NpcSkinCatalog.BANDIT_CROSSBOWMAN;
            case CULTIST -> NpcSkinCatalog.CULTIST_ACOLYTE;
            case KOBOLD_SHAMAN -> NpcSkinCatalog.KOBOLD_SHAMAN;
            case SKELETON_ARCHER -> NpcSkinCatalog.SKELETON_ARCHER;
            case DROW_WARRIOR -> NpcSkinCatalog.DROW_WARRIOR;
            case HOBGOBLIN_LEGIONARY -> NpcSkinCatalog.HOBGOBLIN_LEGIONARY;
            case GNOLL_HUNTER -> NpcSkinCatalog.GNOLL_HUNTER;
            case LIZARDFOLK -> NpcSkinCatalog.LIZARDFOLK_SHAMAN;
            case SAHUAGIN -> NpcSkinCatalog.SAHUAGIN_RAIDER;
            case GOBLIN_BOSS -> NpcSkinCatalog.GOBLIN_BOSS;
            case SPECTER -> NpcSkinCatalog.SPECTER_WRAITH;
            case ANIMATED_ARMOR -> NpcSkinCatalog.ANIMATED_ARMOR;
            case BANDIT_CUTTHROAT -> NpcSkinCatalog.BANDIT_CUTTHROAT;
            case CULT_FANATIC -> NpcSkinCatalog.CULT_FANATIC;
            case GARGOYLE -> NpcSkinCatalog.STONE_GARGOYLE;
            case MINOTAUR_SKELETON -> NpcSkinCatalog.MINOTAUR_SKELETON;
            case MUMMY -> NpcSkinCatalog.MUMMY_GUARDIAN;
            case WIGHT -> NpcSkinCatalog.WIGHT_CAPTAIN;
            case BEARDED_DEVIL -> NpcSkinCatalog.BEARDED_DEVIL;
            case DRAGONBORN_KNIGHT -> NpcSkinCatalog.DRAGONBORN_KNIGHT_BOSS;
            case HILL_GIANT -> NpcSkinCatalog.HILL_GIANT;
            case ORC_WARLORD -> NpcSkinCatalog.ORC_WARLORD;
            case ASSASSIN -> NpcSkinCatalog.ASSASSIN_SHADOW;
            case STONE_GOLEM -> NpcSkinCatalog.STONE_GOLEM;
            case DEATH_KNIGHT -> NpcSkinCatalog.DEATH_KNIGHT_BOSS;
            case LICH_ARCHMAGE -> NpcSkinCatalog.LICH_ARCHMAGE;
            case TAVERN_KEEPER -> NpcSkinCatalog.TAVERN_KEEPER_BORIS;
            case MERCHANT -> NpcSkinCatalog.MERCHANT_ELARA;
            case TOWN_GUARD -> NpcSkinCatalog.TOWN_GUARD_SPEARMAN;
            default -> null;
        });
    }

    public static DialogueTree starterDialogue(BestiaryPreset preset) {
        if (preset == CAPTAIN_MORGAN) return checkedDialogue("morgan", Skill.PERSUASION, 16, Skill.INTIMIDATION, 15);
        return preset.civilian() ? DialogueTree.civilian(preset.id()) : DialogueTree.defaultTree();
    }

    public static DialogueTree borisDialogue() {
        return checkedDialogue("boris", Skill.PERSUASION, 13, Skill.INSIGHT, 12);
    }

    private static DialogueTree checkedDialogue(String role, Skill first, int dc1, Skill second, int dc2) {
        String key = DialogueTree.BUILTIN_PREFIX + role + ".";
        var failure = new DialogueChoice.Outcome(Optional.of("refusal"),
                role.equals("morgan") ? Optional.of(NpcAttitude.HOSTILE) : Optional.empty());
        return new DialogueTree("meeting", List.of(
                new DialogueNode("meeting", key + "greeting", List.of(
                        new DialogueChoice("parley", key + "parley", Optional.of(new DialogueChoice.Check(first, dc1)),
                                DialogueChoice.Outcome.goTo("parley_success"), failure),
                        new DialogueChoice("read", key + "read", Optional.of(new DialogueChoice.Check(second, dc2)),
                                DialogueChoice.Outcome.goTo("read_success"), failure))),
                new DialogueNode("parley_success", key + "parley_success", List.of()),
                new DialogueNode("read_success", key + "read_success", List.of()),
                new DialogueNode("refusal", key + "refusal", List.of())));
    }

    public static DialogueTree dialogueAfterPresetChange(BestiaryPreset previous, BestiaryPreset next, DialogueTree dialogue) {
        if (previous == next) return dialogue;
        var starter = starterDialogue(previous);
        return dialogue.start().equals(starter.start()) && dialogue.nodes().equals(starter.nodes())
                ? starterDialogue(next) : dialogue;
    }
}
