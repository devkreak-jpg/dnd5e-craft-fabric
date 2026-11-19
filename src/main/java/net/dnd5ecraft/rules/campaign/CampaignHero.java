package net.dnd5ecraft.rules.campaign;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;

public enum CampaignHero {
    FINNEAS("Finneas \"Shtil\" Thorn", RaceType.TRITON, ClassType.ARTIFICER, SubclassType.ARTILLERIST, BackgroundType.SAGE,
            List.of(11, 12, 15, 15, 10, 12), 30, 18, Map.of(ClassType.ARTIFICER, 3, ClassType.WIZARD, 1),
            Set.of("mending", "fire_bolt", "message", "lightning_lure", "prestidigitation", "booming_blade",
                    "thunderwave", "shield", "absorb_elements", "cure_wounds", "faerie_fire", "grease", "disguise_self", "fog_cloud")),
    ALEXANDER("Alexander Rasputin", RaceType.LOXODON, ClassType.BARBARIAN, SubclassType.WORLDBREAKER, BackgroundType.SOLDIER,
            List.of(20, 18, 18, 10, 12, 10), 50, 18, Map.of(ClassType.BARBARIAN, 4), Set.of()),
    HARLEQUIN("Harlequin", RaceType.HUMAN, ClassType.HARLEQUIN, null, BackgroundType.ENTERTAINER,
            List.of(10, 16, 14, 10, 12, 16), 31, 13, Map.of(ClassType.HARLEQUIN, 4), Set.of()),
    MINOTAUR("Minotaur", RaceType.MINOTAUR, ClassType.FIGHTER, SubclassType.ECHO_KNIGHT, BackgroundType.SOLDIER,
            List.of(16, 12, 16, 8, 12, 10), 40, 17, Map.of(ClassType.FIGHTER, 4), Set.of());

    private final String defaultName;
    private final RaceType race;
    private final ClassType startingClass;
    private final SubclassType path;
    private final BackgroundType background;
    private final List<Integer> scores;
    private final int hp, armor;
    private final Map<ClassType, Integer> levels;
    private final Set<String> spells;

    CampaignHero(String defaultName, RaceType race, ClassType startingClass, SubclassType path, BackgroundType background,
                 List<Integer> scores, int hp, int armor, Map<ClassType, Integer> levels, Set<String> spells) {
        this.defaultName = defaultName;
        this.race = race;
        this.startingClass = startingClass;
        this.path = path;
        this.background = background;
        this.scores = scores;
        this.hp = hp;
        this.armor = armor;
        this.levels = levels;
        this.spells = spells;
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String defaultName() { return defaultName; }
    public RaceType race() { return race; }
    public ClassType startingClass() { return startingClass; }
    public BackgroundType background() { return background; }
    public List<Integer> scores() { return scores; }
    public int hp() { return hp; }
    public int armor() { return armor; }
    public Map<ClassType, Integer> levels() { return levels; }
    public Set<String> spells() { return spells; }

    public boolean matches(CharacterSheetData sheet) {
        return path == null ? sheet.classLevels().containsKey(startingClass) : sheet.subclasses().get(startingClass) == path;
    }

    public CharacterSheetData create() {
        return create(defaultName, race, background, scores, hp, CharacterSheetData.Personality.EMPTY);
    }

    public CharacterSheetData create(String name, RaceType race, BackgroundType background, List<Integer> scores, int hp,
                                     CharacterSheetData.Personality personality) {
        if (name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)
                || scores.size() != 6 || hp < 1 || hp > 100) throw new IllegalArgumentException("Invalid campaign character");
        Map<Ability, Integer> abilities = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) abilities.put(ability, scores.get(ability.ordinal()));
        var base = CharacterSheetData.create(name, race, background, startingClass, abilities);
        Map<Skill, ProficiencyLevel> skills = new EnumMap<>(Skill.class);
        skills.putAll(base.skillProficiencies());
        if (this == FINNEAS) skills.put(Skill.PERCEPTION, ProficiencyLevel.PROFICIENT);
        var sheet = new CharacterSheetData(name, race, background, personality, startingClass, levels,
                path == null ? Map.of() : Map.of(startingClass, path), abilities, skills, base.savingThrowProficiencies(),
                base.feats(), 900, new HitPoints(hp, hp, 0, 0), base.coins(), base.attunement(),
                base.exhaustion(), base.conditions(), DeathSaveTracker.CONSCIOUS);
        return this == HARLEQUIN ? sheet.withResources(sheet.resources().withSpent(
                net.dnd5ecraft.rules.character.ClassFeature.CHAOS_POINTS, CampaignRules.chaosLimit(4))) : sheet;
    }
}
