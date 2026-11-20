package net.dnd5ecraft.rules.spells;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.equipment.DamageType5e;

public record Spell(String id, String name, int level, SpellSchool school, CastingTime castingTime,
                    int rangeFeet, int durationRounds, boolean concentration, boolean ritual,
                    Targeting targeting, int targets, Optional<AoEGeometry.Template> area,
                    Resolution resolution, Optional<Ability> save, Effect effect, Set<ClassType> classes) {

    public enum CastingTime { ACTION, BONUS_ACTION, REACTION, TEN_MINUTES }
    public enum Targeting { SELF, CREATURES, AREA }
    public enum Resolution { AUTOMATIC, ATTACK, SAVE_NEGATES, SAVE_HALF }
    public enum EffectKind {
        DAMAGE, HEAL, TEMP_HP, STABILIZE, AC_FORMULA, ARMOR_BONUS, COUNTERSPELL,
        BLESS, BANE, PARALYSIS, BLINDNESS, RESTORE, DETECT_MAGIC, RESISTANCE, GUIDANCE, REVEAL, MARK, TELEPORT, SPIRITUAL_WEAPON, ZONE, HASTE, REVIVE, UTILITY, ABSORB
    }

    public enum Rider {
        NONE, NO_HEALING, SLOW_TEN_FEET, NO_REACTIONS, NO_COVER_BONUS, PUSH_TEN_FEET, GUIDING_LIGHT, MOCKERY, PULL_TEN_FEET, BOOM_ON_MOVE
    }

    /**
     * Scaling counts extra dice or targets for each slot level above the base.
     * Cantrip dice or beams scale at total character levels 5, 11 and 17.
     */
    public record Effect(EffectKind kind, Optional<DamageType5e> damageType, int dice, int sides, int flat,
                         boolean addCastingModifier, int extraDicePerSlot, int extraTargetsPerSlot,
                         boolean cantripDice, boolean cantripBeams, Rider rider) {
        public Effect {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(damageType);
            Objects.requireNonNull(rider);
            if (dice < 0 || (dice == 0 ? sides != 0 : sides < 2) || flat < 0
                    || extraDicePerSlot < 0 || extraTargetsPerSlot < 0
                    || damageType.isPresent() != (kind == EffectKind.DAMAGE || kind == EffectKind.SPIRITUAL_WEAPON || kind == EffectKind.ZONE && dice > 0)) {
                throw new IllegalArgumentException("Invalid spell effect");
            }
        }

        public int diceAt(int baseLevel, int slotLevel, int characterLevel) {
            return dice + extraDicePerSlot * (slotLevel - baseLevel)
                    + (cantripDice ? cantripTier(characterLevel) - 1 : 0);
        }

        public int targetsAt(int base, int baseLevel, int slotLevel, int characterLevel) {
            return base + extraTargetsPerSlot * (slotLevel - baseLevel)
                    + (cantripBeams ? cantripTier(characterLevel) - 1 : 0);
        }

        private static int cantripTier(int level) {
            return level >= 17 ? 4 : level >= 11 ? 3 : level >= 5 ? 2 : 1;
        }
    }

    public Spell {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(school);
        Objects.requireNonNull(castingTime);
        Objects.requireNonNull(targeting);
        Objects.requireNonNull(area);
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(save);
        Objects.requireNonNull(effect);
        classes = Set.copyOf(classes);
        if (!id.matches("[a-z0-9_]+") || name.isBlank() || level < 0 || level > 9 || rangeFeet < 0
                || durationRounds < 0 || targets < 1 || classes.isEmpty()
                || (concentration && durationRounds == 0) || (ritual && level == 0)
                || area.isPresent() != (targeting == Targeting.AREA)
                || save.isPresent() != (resolution == Resolution.SAVE_NEGATES || resolution == Resolution.SAVE_HALF)) {
            throw new IllegalArgumentException("Invalid spell definition: " + id);
        }
    }

    public int targetsAt(int slotLevel, int characterLevel) {
        return effect.targetsAt(targets, level, slotLevel, characterLevel);
    }

    public boolean canUseSlot(int slotLevel) {
        return level == 0 ? slotLevel == 0 : slotLevel >= level && slotLevel <= 9;
    }

    public int ritualCastingMinutes() {
        if (!ritual) {
            throw new IllegalStateException("This spell cannot be cast as a ritual");
        }
        return castingTime == CastingTime.TEN_MINUTES ? 20 : 10;
    }
}
