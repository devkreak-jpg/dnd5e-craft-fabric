package net.dnd5ecraft.rules.character;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.AttunementManager;
import net.dnd5ecraft.rules.equipment.CoinPurse;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public record CharacterSheetData(
        String name, RaceType race, BackgroundType background, Personality personality,
        ClassType startingClass, Map<ClassType, Integer> classLevels, Map<ClassType, SubclassType> subclasses,
        Map<Ability, Integer> abilityScores, Map<Skill, ProficiencyLevel> skillProficiencies,
        Set<Ability> savingThrowProficiencies, Set<FeatType> feats, int experiencePoints,
        HitPoints hitPoints, CoinPurse coins, AttunementManager attunement,
        ExhaustionLevel exhaustion, List<ConditionInstance> conditions, DeathSaveTracker deathSaves, CharacterResources resources) {

    private static final int NBT_VERSION = 1;

    public record Personality(String ideal, String bond, String flaw, String description) {
        public Personality(String ideal, String bond, String flaw) { this(ideal, bond, flaw, ""); }
        public static final Personality EMPTY = new Personality("", "", "");

        public Personality {
            Objects.requireNonNull(ideal);
            Objects.requireNonNull(bond);
            Objects.requireNonNull(flaw);
            if (description == null || description.length() > 256 || description.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid personality description");
            }
        }
    }

    public CharacterSheetData(String name, RaceType race, BackgroundType background, Personality personality,
            ClassType startingClass, Map<ClassType, Integer> classLevels, Map<ClassType, SubclassType> subclasses,
            Map<Ability, Integer> abilityScores, Map<Skill, ProficiencyLevel> skillProficiencies,
            Set<Ability> savingThrowProficiencies, Set<FeatType> feats, int experiencePoints, HitPoints hitPoints,
            CoinPurse coins, AttunementManager attunement, ExhaustionLevel exhaustion, List<ConditionInstance> conditions,
            DeathSaveTracker deathSaves) {
        this(name, race, background, personality, startingClass, classLevels, subclasses, abilityScores, skillProficiencies,
                savingThrowProficiencies, feats, experiencePoints, hitPoints, coins, attunement, exhaustion, conditions,
                deathSaves, CharacterResources.full(classLevels));
    }

    public CharacterSheetData {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A character needs a name");
        }
        Objects.requireNonNull(race);
        Objects.requireNonNull(background);
        Objects.requireNonNull(personality);
        Objects.requireNonNull(startingClass);
        Objects.requireNonNull(hitPoints);
        Objects.requireNonNull(coins);
        Objects.requireNonNull(attunement);
        Objects.requireNonNull(exhaustion);
        Objects.requireNonNull(deathSaves);
        Objects.requireNonNull(resources);

        classLevels = Map.copyOf(classLevels);
        subclasses = Map.copyOf(subclasses);
        abilityScores = Map.copyOf(abilityScores);
        skillProficiencies = Map.copyOf(skillProficiencies);
        savingThrowProficiencies = Set.copyOf(savingThrowProficiencies);
        feats = Set.copyOf(feats);
        conditions = List.copyOf(conditions);
        if (!classLevels.containsKey(startingClass)
                || classLevels.values().stream().anyMatch(value -> value < 1 || value > 20)
                || classLevels.values().stream().mapToInt(Integer::intValue).sum() > 20) {
            throw new IllegalArgumentException("Invalid class levels");
        }
        for (var entry : resources.hitDice().entrySet()) {
            if (entry.getValue() > classLevels.getOrDefault(entry.getKey(), 0)) throw new IllegalArgumentException("Too many hit dice");
        }
        for (var entry : subclasses.entrySet()) {
            SubclassType subclass = entry.getValue();
            if (subclass.parent() != entry.getKey()
                    || classLevels.getOrDefault(entry.getKey(), 0) < subclass.selectionLevel()) {
                throw new IllegalArgumentException("Subclass does not match class and level: " + subclass);
            }
        }
        for (Ability ability : Ability.values()) {
            Integer score = abilityScores.get(ability);
            if (score == null) {
                throw new IllegalArgumentException("Missing ability score: " + ability);
            }
            Ability.modifier(score);
        }
        if (experiencePoints < 0) {
            throw new IllegalArgumentException("Experience cannot be negative");
        }
        if ((hitPoints.current() > 0) != (deathSaves.state() == DeathSaveTracker.State.CONSCIOUS)) {
            throw new IllegalArgumentException("Death save state must match current hit points");
        }
    }

    public static CharacterSheetData create(String name, RaceType race, BackgroundType background,
            ClassType startingClass, Map<Ability, Integer> abilityScores) {
        Integer constitution = abilityScores.get(Ability.CONSTITUTION);
        if (constitution == null) {
            throw new IllegalArgumentException("Missing ability score: CONSTITUTION");
        }
        int hp = Math.max(1, startingClass.hitDie() + Ability.modifier(constitution));
        Map<Skill, ProficiencyLevel> skills = new EnumMap<>(Skill.class);
        background.skills().forEach(skill -> skills.put(skill, ProficiencyLevel.PROFICIENT));
        return new CharacterSheetData(name, race, background, Personality.EMPTY, startingClass,
                Map.of(startingClass, 1), Map.of(), abilityScores, skills, startingClass.savingThrows(),
                Set.of(), 0, new HitPoints(hp, hp, 0, 0), new CoinPurse(0, 0, 0, background.startingGold(), 0), AttunementManager.EMPTY,
                ExhaustionLevel.NONE, List.of(), DeathSaveTracker.CONSCIOUS);
    }

    public int level() {
        return classLevels.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int score(Ability ability) {
        return abilityScores.get(ability);
    }

    public int skillModifier(Skill skill) {
        return skillModifier(skill, skill.ability());
    }

    public int skillModifier(Skill skill, Ability governingAbility) {
        return Ability.modifier(score(governingAbility)) + AbilityCheckEngine.jackOfAllTrades(
                classLevels.getOrDefault(ClassType.BARD, 0), level(), skillProficiencies.getOrDefault(skill, ProficiencyLevel.NONE));
    }

    public int savingThrowModifier(Ability ability) {
        ProficiencyLevel proficiency = savingThrowProficiencies.contains(ability)
                ? ProficiencyLevel.PROFICIENT : ProficiencyLevel.NONE;
        return AbilityCheckEngine.modifier(score(ability), proficiency, level());
    }

    public CharacterSheetData withCombatState(HitPoints health, List<ConditionInstance> active,
            DeathSaveTracker saves) {
        return new CharacterSheetData(name, race, background, personality, startingClass, classLevels, subclasses,
                abilityScores, skillProficiencies, savingThrowProficiencies, feats, experiencePoints,
                health, coins, attunement, exhaustion, active, saves, resources);
    }

    public CharacterSheetData withResources(CharacterResources updated) {
        return copy(classLevels, experiencePoints, hitPoints, exhaustion, updated);
    }

    public CharacterSheetData withCoins(CoinPurse updated) {
        return new CharacterSheetData(name, race, background, personality, startingClass, classLevels, subclasses,
                abilityScores, skillProficiencies, savingThrowProficiencies, feats, experiencePoints, hitPoints, updated,
                attunement, exhaustion, conditions, deathSaves, resources);
    }

    public CharacterSheetData withRace(RaceType updated) {
        return new CharacterSheetData(name, updated, background, personality, startingClass, classLevels, subclasses,
                abilityScores, skillProficiencies, savingThrowProficiencies, feats, experiencePoints, hitPoints, coins,
                attunement, exhaustion, conditions, deathSaves, resources);
    }

    public int remaining(ClassFeature feature) {
        if (feature == ClassFeature.UNLEASH_INCARNATION) {
            if (subclasses.get(ClassType.FIGHTER) != SubclassType.ECHO_KNIGHT) return 0;
            return Math.max(0, Ability.modifier(score(Ability.CONSTITUTION)) - resources.spent().getOrDefault(feature, 0));
        }
        return resources.remaining(feature, classLevels, Ability.modifier(score(Ability.CHARISMA)), race);
    }

    public CharacterSheetData spend(ClassFeature feature, int amount) {
        return withResources(resources.spend(feature, amount, classLevels, Ability.modifier(score(Ability.CHARISMA)), race));
    }

    public CharacterSheetData awardExperience(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Experience award cannot be negative");
        return copy(classLevels, Math.addExact(experiencePoints, amount), hitPoints, exhaustion, resources);
    }

    public CharacterSheetData levelUp(ClassType target, boolean milestone, IntUnaryOperator roller) {
        requireConscious();
        if (!MulticlassValidator.canTakeLevel(this, target)) throw new IllegalStateException("Class prerequisites or level limit");
        int next = CharacterProgression.threshold(level() + 1);
        if (!milestone && experiencePoints < next) throw new IllegalStateException("Not enough experience to level up");
        int gain = Math.max(1, AbilityCheckEngine.rollDie(target.hitDie(), roller) + Ability.modifier(score(Ability.CONSTITUTION)))
                + (feats.contains(FeatType.TOUGH) ? 2 : 0);
        var levels = new EnumMap<ClassType, Integer>(ClassType.class);
        levels.putAll(classLevels);
        levels.merge(target, 1, Integer::sum);
        var paths = new EnumMap<ClassType, SubclassType>(ClassType.class);
        paths.putAll(subclasses);
        if (!paths.containsKey(target)) {
            java.util.Arrays.stream(SubclassType.values()).filter(type -> type.parent() == target
                    && type.selectionLevel() <= levels.get(target)).findFirst().ifPresent(type -> paths.put(target, type));
        }
        return new CharacterSheetData(name, race, background, personality, startingClass, levels, paths, abilityScores,
                skillProficiencies, savingThrowProficiencies, feats, milestone ? Math.max(next, experiencePoints) : experiencePoints,
                new HitPoints(hitPoints.current() + gain, hitPoints.maximum() + gain, hitPoints.temporary(), hitPoints.arcaneWard(),
                        hitPoints.beast()), coins, attunement, exhaustion, conditions, deathSaves, resources.addHitDie(target));
    }

    public CharacterSheetData withPersonality(String description) {
        return new CharacterSheetData(name, race, background,
                new Personality(personality.ideal(), personality.bond(), personality.flaw(), description),
                startingClass, classLevels, subclasses, abilityScores, skillProficiencies, savingThrowProficiencies, feats,
                experiencePoints, hitPoints, coins, attunement, exhaustion, conditions, deathSaves, resources);
    }

    public CharacterSheetData chooseSubclass(SubclassType type) {
        requireConscious();
        if (classLevels.getOrDefault(type.parent(), 0) != type.selectionLevel()) {
            throw new IllegalStateException("Choose a subclass at its class level");
        }
        var paths = new EnumMap<ClassType, SubclassType>(ClassType.class);
        paths.putAll(subclasses);
        paths.put(type.parent(), type);
        return new CharacterSheetData(name, race, background, personality, startingClass, classLevels, paths,
                abilityScores, skillProficiencies, savingThrowProficiencies, feats, experiencePoints, hitPoints, coins,
                attunement, exhaustion, conditions, deathSaves, resources);
    }

    public CharacterSheetData takeFeat(FeatType feat, Ability resilientAbility) {
        requireConscious();
        int choices = classLevels.entrySet().stream().mapToInt(entry -> Math.min(4, entry.getValue() / 4)
                + (entry.getValue() >= 19 ? 1 : 0)
                + (entry.getKey() == ClassType.FIGHTER ? (entry.getValue() >= 6 ? 1 : 0) + (entry.getValue() >= 14 ? 1 : 0) : 0)
                + (entry.getKey() == ClassType.ROGUE && entry.getValue() >= 10 ? 1 : 0)).sum();
        if (feats.contains(feat) || feats.size() >= choices) throw new IllegalStateException("No feat choice is available");
        var updated = EnumSet.noneOf(FeatType.class);
        updated.addAll(feats);
        updated.add(feat);
        var scores = new EnumMap<Ability, Integer>(Ability.class);
        scores.putAll(abilityScores);
        var saves = EnumSet.noneOf(Ability.class);
        saves.addAll(savingThrowProficiencies);
        int hpGain = feat == FeatType.TOUGH ? 2 * level() : 0;
        if (feat == FeatType.RESILIENT) {
            if (resilientAbility == null) throw new IllegalArgumentException("Choose a saving throw for Resilient");
            int before = scores.get(resilientAbility);
            int after = Math.max(before, Math.min(20, before + 1));
            if (before < after) scores.put(resilientAbility, after);
            saves.add(resilientAbility);
            if (resilientAbility == Ability.CONSTITUTION) hpGain += level() * Math.max(0, Ability.modifier(after) - Ability.modifier(before));
        }
        return new CharacterSheetData(name, race, background, personality, startingClass, classLevels, subclasses, scores,
                skillProficiencies, saves, updated, experiencePoints, new HitPoints(hitPoints.current() + hpGain,
                        hitPoints.maximum() + hpGain, hitPoints.temporary(), hitPoints.arcaneWard(), hitPoints.beast()),
                coins, attunement, exhaustion, conditions, deathSaves, resources);
    }

    public CharacterSheetData shortRest(Optional<ClassType> hitDie, IntUnaryOperator roller) {
        requireConscious();
        CharacterResources healed = resources.rested(classLevels, false);
        HitPoints hp = hitPoints;
        if (hitDie.isPresent()) {
            ClassType type = hitDie.orElseThrow();
            healed = healed.spendHitDie(type);
            hp = hp.heal(Math.max(0, AbilityCheckEngine.rollDie(type.hitDie(), roller) + Ability.modifier(score(Ability.CONSTITUTION))));
        }
        return copy(classLevels, experiencePoints, hp, exhaustion, healed);
    }

    public CharacterSheetData longRest(boolean consumedFoodAndWater) {
        requireConscious();
        var rested = copy(classLevels, experiencePoints, new HitPoints(hitPoints.maximum(), hitPoints.maximum(), 0, 0),
                exhaustion.afterLongRest(consumedFoodAndWater), resources.rested(classLevels, true));
        int role = resources.spent().getOrDefault(ClassFeature.CAST_OF_DIE, 0);
        if (role < 32 || role / 32 > Ability.values().length) return rested;
        return rested.withRoleScore(Ability.values()[role / 32 - 1], role % 32, rested.resources());
    }

    public CharacterSheetData castOfTheDie(Ability ability) {
        requireConscious();
        if (remaining(ClassFeature.CAST_OF_DIE) == 0) throw new IllegalStateException("Cast of the Die is unavailable");
        int original = score(ability);
        int role = (ability.ordinal() + 1) * 32 + original;
        return withRoleScore(ability, Math.min(30, original + 4), resources.withSpent(ClassFeature.CAST_OF_DIE, role));
    }

    private CharacterSheetData withRoleScore(Ability ability, int score, CharacterResources updated) {
        var scores = new EnumMap<Ability, Integer>(Ability.class);
        scores.putAll(abilityScores);
        scores.put(ability, score);
        return new CharacterSheetData(name, race, background, personality, startingClass, classLevels, subclasses, scores,
                skillProficiencies, savingThrowProficiencies, feats, experiencePoints, hitPoints, coins, attunement,
                exhaustion, conditions, deathSaves, updated);
    }

    private void requireConscious() {
        if (deathSaves.state() != DeathSaveTracker.State.CONSCIOUS || exhaustion.dead()) {
            throw new IllegalStateException("This action requires a conscious character");
        }
    }

    private CharacterSheetData copy(Map<ClassType, Integer> levels, int xp, HitPoints hp, ExhaustionLevel tired,
            CharacterResources updated) {
        return new CharacterSheetData(name, race, background, personality, startingClass, levels, subclasses, abilityScores,
                skillProficiencies, savingThrowProficiencies, feats, xp, hp, coins, attunement, tired, conditions, deathSaves, updated);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", NBT_VERSION);
        tag.putString("Name", name);
        tag.putString("Race", race.name());
        tag.putString("Background", background.name());
        CompoundTag traits = new CompoundTag();
        traits.putString("Description", personality.description());
        traits.putString("Ideal", personality.ideal());
        traits.putString("Bond", personality.bond());
        traits.putString("Flaw", personality.flaw());
        tag.put("Personality", traits);

        tag.putString("StartingClass", startingClass.name());
        tag.put("ClassLevels", writeIntMap(classLevels));
        tag.put("AbilityScores", writeIntMap(abilityScores));
        CompoundTag specializations = new CompoundTag();
        subclasses.forEach((type, value) -> specializations.putString(type.name(), value.name()));
        tag.put("Subclasses", specializations);
        CompoundTag skills = new CompoundTag();
        skillProficiencies.forEach((skill, value) -> skills.putString(skill.name(), value.name()));
        tag.put("SkillProficiencies", skills);
        tag.put("SavingThrowProficiencies", writeEnumSet(savingThrowProficiencies));
        tag.put("Feats", writeEnumSet(feats));
        tag.putInt("Experience", experiencePoints);

        CompoundTag hp = new CompoundTag();
        hp.putInt("Current", hitPoints.current());
        hp.putInt("Maximum", hitPoints.maximum());
        hp.putInt("Temporary", hitPoints.temporary());
        hp.putInt("ArcaneWard", hitPoints.arcaneWard());
        hp.putInt("Beast", hitPoints.beast());
        tag.put("HitPoints", hp);
        var pools = new CompoundTag();
        pools.put("HitDice", writeIntMap(resources.hitDice()));
        pools.put("Spent", writeIntMap(resources.spent()));
        pools.putBoolean("Inspiration", resources.inspiration());
        pools.putIntArray("RegularSlotsSpent", resources.slots().regular());
        pools.putInt("PactSlotsSpent", resources.slots().pact());
        tag.put("Resources", pools);
        CompoundTag purse = new CompoundTag();
        purse.putLong("Copper", coins.copper());
        purse.putLong("Silver", coins.silver());
        purse.putLong("Electrum", coins.electrum());
        purse.putLong("Gold", coins.gold());
        purse.putLong("Platinum", coins.platinum());
        tag.put("Coins", purse);
        CompoundTag attuned = new CompoundTag();
        attunement.items().forEach((id, type) -> attuned.putString(id.toString(), type));
        tag.put("Attunement", attuned);
        tag.putInt("Exhaustion", exhaustion.level());

        ListTag active = new ListTag();
        for (ConditionInstance condition : conditions) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Type", condition.type().name());
            entry.putInt("Rounds", condition.roundsRemaining());
            condition.source().ifPresent(id -> entry.putUUID("Source", id));
            active.add(entry);
        }
        tag.put("Conditions", active);
        CompoundTag saves = new CompoundTag();
        saves.putString("State", deathSaves.state().name());
        saves.putInt("Successes", deathSaves.successes());
        saves.putInt("Failures", deathSaves.failures());
        tag.put("DeathSaves", saves);
        return tag;
    }

    public static CharacterSheetData fromNbt(CompoundTag tag) {
        int version = readInt(tag, "Version");
        if (version != NBT_VERSION) {
            throw new IllegalArgumentException("Unsupported character sheet version: " + version);
        }
        CompoundTag traits = compound(tag, "Personality");
        CompoundTag hp = compound(tag, "HitPoints");
        CompoundTag purse = compound(tag, "Coins");
        CompoundTag saves = compound(tag, "DeathSaves");
        Map<UUID, String> items = new HashMap<>();
        CompoundTag attuned = compound(tag, "Attunement");
        for (String key : attuned.getAllKeys()) {
            items.put(UUID.fromString(key), readString(attuned, key));
        }

        List<ConditionInstance> conditions = new ArrayList<>();
        ListTag active = list(tag, "Conditions", Tag.TAG_COMPOUND);
        for (int index = 0; index < active.size(); index++) {
            CompoundTag entry = active.getCompound(index);
            Optional<UUID> source = entry.contains("Source") ? Optional.of(entry.getUUID("Source")) : Optional.empty();
            conditions.add(new ConditionInstance(ConditionType.valueOf(readString(entry, "Type")),
                    source, readInt(entry, "Rounds")));
        }

        Map<ClassType, Integer> levels = readEnumMap(tag, "ClassLevels", ClassType.class, CharacterSheetData::readInt);
        var pools = tag.getCompound("Resources");
        CharacterResources resources = tag.contains("Resources", Tag.TAG_COMPOUND)
                ? new CharacterResources(readEnumMap(pools, "HitDice", ClassType.class, CharacterSheetData::readInt),
                        readEnumMap(pools, "Spent", ClassFeature.class, CharacterSheetData::readInt), pools.getBoolean("Inspiration"), readSpentSlots(pools))
                : CharacterResources.full(levels);
        return new CharacterSheetData(readString(tag, "Name"), RaceType.valueOf(readString(tag, "Race")),
                BackgroundType.valueOf(readString(tag, "Background")),
                new Personality(readString(traits, "Ideal"), readString(traits, "Bond"), readString(traits, "Flaw"), traits.getString("Description")),
                ClassType.valueOf(readString(tag, "StartingClass")),
                levels,
                readEnumMap(tag, "Subclasses", ClassType.class, (t, k) -> SubclassType.valueOf(readString(t, k))),
                readEnumMap(tag, "AbilityScores", Ability.class, CharacterSheetData::readInt),
                readEnumMap(tag, "SkillProficiencies", Skill.class, (t, k) -> ProficiencyLevel.valueOf(readString(t, k))),
                readEnumSet(tag, "SavingThrowProficiencies", Ability::valueOf),
                readEnumSet(tag, "Feats", FeatType::valueOf), readInt(tag, "Experience"),
                new HitPoints(readInt(hp, "Current"), readInt(hp, "Maximum"), readInt(hp, "Temporary"), readInt(hp, "ArcaneWard"), hp.getInt("Beast")),
                new CoinPurse(readLong(purse, "Copper"), readLong(purse, "Silver"), readLong(purse, "Electrum"),
                        readLong(purse, "Gold"), readLong(purse, "Platinum")),
                new AttunementManager(items), ExhaustionLevel.of(readInt(tag, "Exhaustion")), conditions,
                new DeathSaveTracker(DeathSaveTracker.State.valueOf(readString(saves, "State")),
                        readInt(saves, "Successes"), readInt(saves, "Failures")), resources);
    }

    private static net.dnd5ecraft.rules.spells.SpellSlotTable.SpentSlots readSpentSlots(CompoundTag pools) {
        if (!pools.contains("RegularSlotsSpent", Tag.TAG_INT_ARRAY)) {
            return net.dnd5ecraft.rules.spells.SpellSlotTable.SpentSlots.EMPTY;
        }
        return new net.dnd5ecraft.rules.spells.SpellSlotTable.SpentSlots(
                java.util.Arrays.stream(pools.getIntArray("RegularSlotsSpent")).boxed().toList(), pools.getInt("PactSlotsSpent"));
    }

    private static CompoundTag writeIntMap(Map<? extends Enum<?>, Integer> values) {
        CompoundTag data = new CompoundTag();
        values.forEach((key, value) -> data.putInt(key.name(), value));
        return data;
    }

    private static ListTag writeEnumSet(Set<? extends Enum<?>> values) {
        ListTag data = new ListTag();
        values.stream().map(Enum::name).sorted().forEach(value -> data.add(StringTag.valueOf(value)));
        return data;
    }

    private static <T> Set<T> readEnumSet(CompoundTag tag, String key, Function<String, T> parse) {
        ListTag data = list(tag, key, Tag.TAG_STRING);
        Set<T> result = new HashSet<>();
        for (int index = 0; index < data.size(); index++) {
            result.add(parse.apply(data.getString(index)));
        }
        return result;
    }

    private static <K extends Enum<K>, V> Map<K, V> readEnumMap(CompoundTag parent, String name,
            Class<K> keyType, BiFunction<CompoundTag, String, V> readValue) {
        CompoundTag tag = compound(parent, name);
        Map<K, V> result = new EnumMap<>(keyType);
        for (String key : tag.getAllKeys()) {
            result.put(Enum.valueOf(keyType, key), readValue.apply(tag, key));
        }
        return result;
    }

    private static void requireType(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Missing or invalid character sheet field: " + key);
        }
    }

    private static CompoundTag compound(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_COMPOUND);
        return tag.getCompound(key);
    }

    private static ListTag list(CompoundTag tag, String key, int type) {
        requireType(tag, key, Tag.TAG_LIST);
        ListTag list = (ListTag) tag.get(key);
        if (!list.isEmpty() && list.getElementType() != type) {
            throw new IllegalArgumentException("Invalid character sheet list: " + key);
        }
        return list;
    }

    private static String readString(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_STRING);
        return tag.getString(key);
    }

    private static int readInt(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_INT);
        return tag.getInt(key);
    }

    private static long readLong(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_LONG);
        return tag.getLong(key);
    }
}
