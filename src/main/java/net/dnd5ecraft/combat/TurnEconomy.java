package net.dnd5ecraft.combat;

import java.util.EnumSet;
import java.util.Set;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.Spell.CastingTime;

public final class TurnEconomy {
    public enum Resource { ACTION, BONUS_ACTION, REACTION, MOVEMENT, OBJECT_INTERACTION }

    public record Snapshot(Set<Resource> available, int actions, int movementSpentFeet, int movementRemainingFeet, int dashes,
                           boolean disengaged) {
        public Snapshot {
            available = Set.copyOf(available);
        }
    }

    private int actions;
    private boolean bonusAction;
    private boolean reaction = true;
    private boolean objectInteraction;
    private int movementSpent;
    private int dashes;
    private boolean disengaged;
    private boolean onTurn;
    private boolean bonusSpell;
    private boolean incompatibleSpell;

    public void startTurn() {
        if (onTurn) throw new IllegalStateException("Turn is already open");
        onTurn = true;
        actions = 1;
        reaction = true;
        bonusAction = objectInteraction = true;
        bonusSpell = incompatibleSpell = disengaged = false;
        movementSpent = dashes = 0;
    }

    public void endTurn() {
        if (!onTurn) throw new IllegalStateException("No turn is open");
        onTurn = false;
        disengaged = false;
    }

    public boolean canSpend(Resource resource) {
        return switch (resource) {
            case ACTION -> onTurn && actions > 0;
            case BONUS_ACTION -> onTurn && bonusAction;
            case REACTION -> reaction;
            case OBJECT_INTERACTION -> onTurn && objectInteraction;
            case MOVEMENT -> throw new IllegalArgumentException("Use remainingMovement for movement");
        };
    }

    public boolean spend(Resource resource) {
        if (!canSpend(resource)) return false;
        switch (resource) {
            case ACTION -> actions--;
            case BONUS_ACTION -> bonusAction = false;
            case REACTION -> reaction = false;
            case OBJECT_INTERACTION -> objectInteraction = false;
            case MOVEMENT -> throw new AssertionError("Movement takes a distance");
        }
        return true;
    }

    public void grantAction() {
        if (!onTurn) throw new IllegalStateException("Extra actions require an open turn");
        actions = Math.incrementExact(actions);
    }

    public boolean dash(Resource resource) {
        requireActionOrBonus(resource);
        if (!spend(resource)) return false;
        dashes = Math.incrementExact(dashes);
        return true;
    }

    public boolean disengage(Resource resource) {
        requireActionOrBonus(resource);
        if (!spend(resource)) return false;
        disengaged = true;
        return true;
    }

    private static void requireActionOrBonus(Resource resource) {
        if (resource != Resource.ACTION && resource != Resource.BONUS_ACTION) {
            throw new IllegalArgumentException("An action or bonus action is required");
        }
    }

    void extraDash() { dashes++; }
    void extraDisengage() { disengaged = true; }

    public int remainingMovement(int speed) {
        if (speed < 0) throw new IllegalArgumentException("Speed cannot be negative");
        return onTurn ? Math.max(0, Math.multiplyExact(speed, dashes + 1) - movementSpent) : 0;
    }

    public boolean move(int cost, int speed) {
        if (cost < 0) throw new IllegalArgumentException("Movement cost cannot be negative");
        if (!onTurn || cost > remainingMovement(speed)) return false;
        movementSpent += cost;
        return true;
    }

    public boolean canCast(Spell spell) {
        return canCast(spell, false);
    }

    boolean canCast(Spell spell, boolean quickened) {
        if (spell.castingTime() == CastingTime.TEN_MINUTES || quickened && spell.castingTime() != CastingTime.ACTION) return false;
        Resource resource = quickened ? Resource.BONUS_ACTION : spellResource(spell);
        if (!canSpend(resource)) return false;
        if (!onTurn) return resource == Resource.REACTION;
        if (resource == Resource.BONUS_ACTION) return !incompatibleSpell;
        return !bonusSpell || isActionCantrip(spell);
    }

    public boolean cast(Spell spell) {
        return cast(spell, false);
    }

    boolean cast(Spell spell, boolean quickened) {
        if (!canCast(spell, quickened)) return false;
        Resource resource = quickened ? Resource.BONUS_ACTION : spellResource(spell);
        spend(resource);
        if (onTurn) {
            if (resource == Resource.BONUS_ACTION) bonusSpell = true;
            else if (!isActionCantrip(spell)) incompatibleSpell = true;
        }
        return true;
    }

    private static boolean isActionCantrip(Spell spell) {
        return spell.castingTime() == CastingTime.ACTION && spell.level() == 0;
    }

    private static Resource spellResource(Spell spell) {
        return switch (spell.castingTime()) {
            case ACTION -> Resource.ACTION;
            case BONUS_ACTION -> Resource.BONUS_ACTION;
            case REACTION -> Resource.REACTION;
            case TEN_MINUTES -> throw new IllegalArgumentException("Long casting is resolved outside turn actions");
        };
    }

    public boolean disengaged() { return disengaged; }

    public Snapshot snapshot(int speed) {
        EnumSet<Resource> available = EnumSet.noneOf(Resource.class);
        for (Resource resource : Resource.values()) {
            if (resource == Resource.MOVEMENT ? remainingMovement(speed) > 0 : canSpend(resource)) {
                available.add(resource);
            }
        }
        return new Snapshot(available, onTurn ? actions : 0, movementSpent, remainingMovement(speed), dashes, disengaged);
    }
}
