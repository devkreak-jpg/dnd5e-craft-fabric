package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.entity.BestiaryTurnState;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.character.CharacterResources;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.conditions.ConditionEffectApplier;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.SpellSlotTable;

public final class CombatantEntry {
    public enum Trait { HUMANOID, UNDEAD, CONSTRUCT, METAL_ARMOR, INORGANIC, MAGICAL, CAN_UNCANNY_DODGE, FIEND, SHAPECHANGER, DEVILS_SIGHT }

    public record Attack(String name, int attackBonus, int reachFeet, int normalRangeFeet, int longRangeFeet,
                         List<DamageRoll> damage, boolean finesse, boolean light, boolean heavy, boolean strengthBased, String weaponKey) {
        public Attack {
            damage = List.copyOf(damage);
            if (name.isBlank() || damage.isEmpty() || reachFeet < 0 || normalRangeFeet < 0
                    || longRangeFeet < normalRangeFeet || (reachFeet == 0) == (normalRangeFeet == 0)) {
                throw new IllegalArgumentException("An attack needs exactly one valid melee or ranged profile");
            }
        }

        public Attack(String name, int attackBonus, int reachFeet, int normalRangeFeet, int longRangeFeet,
                      List<DamageRoll> damage, boolean finesse, boolean light, boolean heavy, boolean strengthBased) {
            this(name, attackBonus, reachFeet, normalRangeFeet, longRangeFeet, damage, finesse, light, heavy, strengthBased, "");
        }

        public Attack(String name, int attackBonus, int reachFeet, int normalRangeFeet, int longRangeFeet,
                List<DamageRoll> damage, boolean finesse) {
            this(name, attackBonus, reachFeet, normalRangeFeet, longRangeFeet, damage, finesse, false, false, !finesse);
        }

        public Attack(String name, int attackBonus, int reachFeet, int normalRangeFeet, int longRangeFeet,
                List<DamageRoll> damage) {
            this(name, attackBonus, reachFeet, normalRangeFeet, longRangeFeet, damage, false);
        }

        public boolean melee() { return reachFeet > 0; }
        public boolean inRange(int feet) { return feet <= (melee() ? reachFeet : longRangeFeet); }
    }

    public record Stats(int speedFeet, int armorClass, boolean armored, int darkvisionFeet, int level,
                        Map<Ability, Integer> abilityScores, Map<Ability, Integer> savingThrows,
                        Map<ClassType, Integer> classes, Map<String, Ability> spells, Set<String> rituals,
                        Set<Trait> traits, List<Attack> attacks) {
        public Stats {
            abilityScores = Map.copyOf(abilityScores);
            savingThrows = Map.copyOf(savingThrows);
            classes = Map.copyOf(classes);
            spells = Map.copyOf(spells);
            rituals = Set.copyOf(rituals);
            traits = Set.copyOf(traits);
            attacks = List.copyOf(attacks);
            if (speedFeet < 0 || armorClass < 0 || darkvisionFeet < 0 || level < 1 || level > 20
                    || !spells.keySet().containsAll(rituals)) {
                throw new IllegalArgumentException("Invalid combat statistics");
            }
            abilityScores.values().forEach(Ability::modifier);
        }

        public int abilityModifier(Ability ability) { return Ability.modifier(abilityScores.getOrDefault(ability, 10)); }
        public int saveModifier(Ability ability) { return savingThrows.getOrDefault(ability, abilityModifier(ability)); }
        public int meleeReachFeet() { return attacks.stream().mapToInt(Attack::reachFeet).max().orElse(0); }
        public int proficiencyBonus() { return ProficiencyLevel.bonusAtLevel(level); }

        public int attacksPerAction() {
            int fighter = classes.getOrDefault(ClassType.FIGHTER, 0);
            if (fighter >= 20) return 4;
            if (fighter >= 11) return 3;
            if (fighter >= 5 || classes.getOrDefault(ClassType.BARBARIAN, 0) >= 5
                    || classes.getOrDefault(ClassType.MONK, 0) >= 5 || classes.getOrDefault(ClassType.PALADIN, 0) >= 5
                    || classes.getOrDefault(ClassType.RANGER, 0) >= 5) return 2;
            return 1;
        }
    }

    enum Effect {
        BLESS, BANE, ARMOR, SHIELD, DETECT_MAGIC, RESISTANCE, PARALYZED, GHOUL_PARALYSIS, BLINDED, DEAFENED,
        NO_HEALING, SLOWED, NO_REACTIONS, GUIDING_LIGHT, CHILLED_UNDEAD, FALSE_LIFE,
        GUIDANCE, MOCKED, REVEALED, MARKED, HASTE, LETHARGY, WEBBED, RAGING, SPIRITUAL_WEAPON, NETTED, BOOMING, ABSORBED
    }

    record TimedEffect(Effect effect, UUID caster, long castId, UUID expiryActor, long expiryTurn,
                       boolean atStart, int saveDc, DamageType5e resistance) {}

    public int campaignFlags() {
        return (has(Effect.RAGING) ? 1 : 0) | (campaignFist && turn.canSpend(TurnEconomy.Resource.BONUS_ACTION) ? 2 : 0)
                | (campaignFist && !campaignFury ? 4 : 0) | (campaignLastHit != null ? 8 : 0);
    }

    public record View(UUID id, String name, int team, int initiative, Cell cell, HitPoints hitPoints,
                       DeathSaveTracker deathSaves, Set<ConditionType> conditions, TurnEconomy.Snapshot resources) {
        public View { conditions = Set.copyOf(conditions); }
    }

    private final UUID id;
    private final String name;
    private final int team;
    private final int initiative;
    private final Stats stats;
    UUID campaignLastHit;
    boolean campaignFist, campaignFury, reckless, campaignDash, echoMoved;
    int laughterRound;
    boolean laughterClaimed;
    int chaosShift;
    private final Defenses defenses;
    private final ExhaustionLevel exhaustion;
    private final BestiaryTurnState bestiary;
    final TurnEconomy turn = new TurnEconomy();
    SpellSlotTable.Ledger slots;
    final List<ConditionInstance> conditions;
    final List<TimedEffect> effects = new ArrayList<>();
    Cell cell;
    HitPoints hp;
    DeathSaveTracker death;
    long turns;
    long lastSneakAttack = -1;
    int attacksRemaining;
    RaceType race = RaceType.HUMAN;
    Set<FeatType> feats = Set.of();
    Map<Skill, Integer> skills = Map.of();
    CharacterResources characterResources;
    boolean dodging;
    boolean surprised;
    Set<SubclassType> subclasses = Set.of();
    boolean hasteAction;
    boolean tookAttackAction;
    boolean unleashedThisAction;
    int stealth = -1;
    int inspirationDie;
    boolean guardiansSlow;
    long diedAtRound = Long.MIN_VALUE;
    int speedPenaltyFeet;
    boolean surgedThisTurn;
    boolean advantageOnNextRoll;
    boolean luckyRoll;
    boolean quickenNextSpell;
    boolean armorStealthDisadvantage;
    long speedLockedTurn = -1;
    final Set<UUID> threatenedByMobile = new java.util.HashSet<>();

    public CombatantEntry(UUID id, String name, int team, int initiative, Cell cell, Stats stats, HitPoints hp,
            DeathSaveTracker death, Defenses defenses, ExhaustionLevel exhaustion, List<ConditionInstance> conditions) {
        this(id, name, team, initiative, cell, stats, hp, death, defenses, exhaustion, conditions, null);
    }

    public CombatantEntry(UUID id, String name, int team, int initiative, Cell cell, Stats stats, HitPoints hp,
            DeathSaveTracker death, Defenses defenses, ExhaustionLevel exhaustion, List<ConditionInstance> conditions,
            BestiaryTurnState bestiary) {
        this.bestiary = bestiary;
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.team = team;
        this.initiative = initiative;
        this.cell = Objects.requireNonNull(cell);
        this.stats = Objects.requireNonNull(stats);
        this.hp = Objects.requireNonNull(hp);
        this.death = Objects.requireNonNull(death);
        this.defenses = Objects.requireNonNull(defenses);
        this.exhaustion = Objects.requireNonNull(exhaustion);
        this.conditions = new ArrayList<>(conditions);
        this.slots = new SpellSlotTable.Ledger(stats.classes());
        this.characterResources = CharacterResources.full(stats.classes());
        if (name.isBlank() || (hp.current() > 0) != (death.state() == DeathSaveTracker.State.CONSCIOUS)) {
            throw new IllegalArgumentException("Invalid creature name or death save state");
        }
    }

    public static CombatantEntry fromSheet(UUID id, int team, int initiative, Cell cell, CharacterSheetData sheet,
            int armorClass, boolean armored, Map<String, Ability> spells, Set<String> rituals, Set<Trait> traits,
            List<Attack> attacks, Defenses defenses) {
        Map<Ability, Integer> saves = new java.util.EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) saves.put(ability, sheet.savingThrowModifier(ability));
        EnumSet<Trait> effectiveTraits = EnumSet.of(Trait.HUMANOID);
        effectiveTraits.addAll(traits);
        if (sheet.classLevels().getOrDefault(ClassType.ROGUE, 0) >= 5) effectiveTraits.add(Trait.CAN_UNCANNY_DODGE);
        var entry = new CombatantEntry(id, sheet.name(), team, initiative, cell,
                new Stats(sheet.race().walkingFeet(), armorClass, armored, sheet.race().darkvisionFeet(), sheet.level(),
                        sheet.abilityScores(), saves, sheet.classLevels(), spells, rituals, effectiveTraits, attacks),
                sheet.hitPoints(), sheet.deathSaves(), defenses, sheet.exhaustion(), sheet.conditions());
        entry.race = sheet.race();
        entry.feats = sheet.feats();
        entry.subclasses = Set.copyOf(sheet.subclasses().values());
        entry.characterResources = sheet.resources();
        entry.slots = new SpellSlotTable.Ledger(sheet.classLevels(), sheet.resources().slots());
        var skills = new java.util.EnumMap<Skill, Integer>(Skill.class);
        for (Skill skill : Skill.values()) skills.put(skill, sheet.skillModifier(skill));
        entry.skills = Map.copyOf(skills);
        return entry;
    }

    public CharacterResources characterResources() { return characterResources.withSlots(slots.spent()); }

    public void setArmorRestrictions(int feet, boolean stealthDisadvantage) {
        if (feet < 0) throw new IllegalArgumentException("Invalid armor speed penalty");
        speedPenaltyFeet = feet;
        armorStealthDisadvantage = stealthDisadvantage;
    }

    public int stealthScore() { return stealth; }

    public int skillModifier(Skill skill) { return skills.getOrDefault(skill, stats.abilityModifier(skill.ability())); }
    boolean feat(FeatType feat) { return feats.contains(feat); }
    int remaining(ClassFeature feature) {
        return characterResources.remaining(feature, stats.classes(), stats.abilityModifier(Ability.CHARISMA), race);
    }
    void spend(ClassFeature feature, int amount) {
        characterResources = characterResources.spend(feature, amount, stats.classes(), stats.abilityModifier(Ability.CHARISMA), race);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public int team() { return team; }
    public int initiative() { return initiative; }
    public Cell cell() { return cell; }
    public Stats stats() { return stats; }
    public HitPoints hitPoints() { return hp; }
    public DeathSaveTracker deathSaves() { return death; }
    public List<ConditionInstance> activeConditions() { return List.copyOf(conditions); }
    public Optional<BestiaryTurnState> bestiary() { return Optional.ofNullable(bestiary); }
    int attacksPerAction() { return bestiary == null ? stats.attacksPerAction() : bestiary.preset().attacksPerAction(); }
    public boolean dead() { return death.state() == DeathSaveTracker.State.DEAD || exhaustion.dead(); }

    public Box bounds() {
        var center = cell.center();
        return new Box(center.minus(new Vec3(.4, .49, .4)),
                center.plus(new Vec3(.4, 1.29, .4)));
    }

    public ConditionEffectApplier conditionEffects() {
        EnumSet<ConditionType> active = EnumSet.noneOf(ConditionType.class);
        conditions.forEach(condition -> active.add(condition.type()));
        if (has(Effect.PARALYZED) || has(Effect.GHOUL_PARALYSIS)) active.add(ConditionType.PARALYZED);
        if (has(Effect.BLINDED)) active.add(ConditionType.BLINDED);
        if (has(Effect.DEAFENED)) active.add(ConditionType.DEAFENED);
        if (has(Effect.WEBBED) || has(Effect.NETTED)) active.add(ConditionType.RESTRAINED);
        if (has(Effect.LETHARGY)) active.add(ConditionType.INCAPACITATED);
        if (hp.current() == 0) active.add(ConditionType.UNCONSCIOUS);
        return new ConditionEffectApplier(active, exhaustion);
    }

    int drainedMaximum;
    boolean offline;
    public boolean offline() { return offline; }

    public boolean canAct() { return !offline && !surprised && !dead() && conditionEffects().canAct(); }

    boolean canAttack() {
        return canAct() && (attacksRemaining > 0 || hasteAction || turn.canSpend(TurnEconomy.Resource.ACTION));
    }

    public boolean canReact() {
        return canAct() && !has(Effect.NO_REACTIONS) && turn.canSpend(TurnEconomy.Resource.REACTION);
    }

    public int speedFeet() {
        int speed = Math.max(0, stats.speedFeet + (feat(FeatType.MOBILE) ? 10 : 0) - speedPenaltyFeet - (has(Effect.SLOWED) ? 10 : 0));
        if (has(Effect.HASTE)) speed *= 2;
        if (guardiansSlow) speed /= 2;
        return offline || surprised || dead() || has(Effect.LETHARGY) || speedLockedTurn == turns ? 0 : conditionEffects().speedFeet(speed);
    }

    public int armorClass() {
        int base = has(Effect.ARMOR) && !stats.armored
                ? Math.max(stats.armorClass, 13 + stats.abilityModifier(Ability.DEXTERITY)) : stats.armorClass;
        return base + (has(Effect.SHIELD) ? 5 : 0) + (has(Effect.HASTE) ? 2 : 0);
    }

    Defenses defenses() {
        EnumSet<DamageType5e> resistances = EnumSet.noneOf(DamageType5e.class);
        resistances.addAll(defenses.resistances());
        resistances.addAll(race.resistances());
        if (has(Effect.RAGING)) resistances.addAll(Set.of(DamageType5e.BLUDGEONING, DamageType5e.PIERCING, DamageType5e.SLASHING));
        effects.stream().filter(effect -> effect.effect == Effect.RESISTANCE).forEach(effect -> resistances.add(effect.resistance));
        return conditionEffects().adjustDefenses(new Defenses(defenses.immunities(), resistances, defenses.vulnerabilities()));
    }

    boolean has(Effect kind) { return effects.stream().anyMatch(effect -> effect.effect == kind); }

    boolean originatingCondition(ConditionType type, UUID source) {
        return conditions.stream().anyMatch(condition -> condition.type() == type && condition.comesFrom(source));
    }

    public int slotsAvailable(SpellSlotTable.Pool pool, int level) { return slots.available(pool, level); }

    public View view() {
        EnumSet<ConditionType> active = EnumSet.noneOf(ConditionType.class);
        var modifiers = conditionEffects();
        for (ConditionType condition : ConditionType.values()) if (modifiers.has(condition)) active.add(condition);
        TurnEconomy.Snapshot snapshot = turn.snapshot(speedFeet());
        EnumSet<TurnEconomy.Resource> available = EnumSet.noneOf(TurnEconomy.Resource.class);
        available.addAll(snapshot.available());
        if (hasteAction && canAct()) available.add(TurnEconomy.Resource.ACTION);
        if (!canAct()) available.retainAll(Set.of(TurnEconomy.Resource.MOVEMENT));
        if (!canReact()) available.remove(TurnEconomy.Resource.REACTION);
        return new View(id, name, team, initiative, cell, hp, death, active,
                new TurnEconomy.Snapshot(available, canAct() ? snapshot.actions() + (hasteAction ? 1 : 0) : 0, snapshot.movementSpentFeet(),
                        snapshot.movementRemainingFeet(), snapshot.dashes(), snapshot.disengaged()));
    }
}
