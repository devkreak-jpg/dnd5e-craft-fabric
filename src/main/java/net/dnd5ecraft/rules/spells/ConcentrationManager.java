package net.dnd5ecraft.rules.spells;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;

public final class ConcentrationManager {
    public record Active(UUID caster, String spellId, long castId) {}
    private final Map<UUID, Active> active = new HashMap<>();

    public Optional<Active> begin(UUID caster, Spell spell, long castId) {
        Objects.requireNonNull(caster);
        if (!spell.concentration()) throw new IllegalArgumentException("Spell does not require concentration");
        return Optional.ofNullable(active.put(caster, new Active(caster, spell.id(), castId)));
    }

    public Optional<Active> current(UUID caster) {
        return Optional.ofNullable(active.get(caster));
    }

    public Optional<Active> end(UUID caster) {
        return Optional.ofNullable(active.remove(caster));
    }

    public boolean end(Active cast) {
        return active.remove(cast.caster(), cast);
    }

    public Optional<Active> onDamage(UUID caster, int damage, boolean incapacitated,
            int constitutionSaveModifier, AbilityCheckEngine.RollMode mode, IntUnaryOperator roller) {
        if (damage < 0) throw new IllegalArgumentException("Damage cannot be negative");
        if (!active.containsKey(caster)) return Optional.empty();
        if (incapacitated) return end(caster);
        if (damage == 0 || AbilityCheckEngine.check(constitutionSaveModifier, damageDc(damage), mode, roller).succeeds()) {
            return Optional.empty();
        }
        return end(caster);
    }

    public static int damageDc(int damage) {
        if (damage < 0) throw new IllegalArgumentException("Damage cannot be negative");
        return Math.max(10, damage / 2);
    }
}
