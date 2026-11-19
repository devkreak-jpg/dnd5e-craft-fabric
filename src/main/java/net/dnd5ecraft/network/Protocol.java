package net.dnd5ecraft.network;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.network.PacketPolicy.TurnToken;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;

public final class Protocol {
    static final UUID NO_ACTOR = new UUID(0, 0);
    public static final int VERSION = 3;
    public static final int MAX_REQUEST = 16_000;
    public static final int MAX_COMBATANTS = 64;
    public static final int MAX_CELLS = 4096;
    public static final int MAX_SPELL_TARGETS = 16;
    public static final int MAX_UPDATE = 180_000;

    public enum Update { STATE, SHEET, ROLL, PING, DIALOGUE, DIALOGUE_END, NPC, ZONE, DRAFT_ROLL, NOTICE, DAMAGE, PARTY, CINEMATIC_BANNER, PASSIVE_DETECTION_GLINT, TABLETOP_READY, TABLETOP_RESULT, ROLL_PROMPT, PROMPT_END, DIALOGUE_ROLL, CONCENTRATION, SHORT_REST, DIRECTOR_SELECTION, JOURNAL, S2C_COMBAT_FLOAT_TEXT, S2C_TACTICAL_PING, S2C_SHORT_REST_FX, S2C_NATURAL, S2C_INSPECT }
    public static final int JOURNAL_LIMIT = 25, JOURNAL_NOTES_LIMIT = 512;
    public enum AbilityMethod { POINT_BUY, STANDARD_ARRAY, ROLLED }

    public record Creature(UUID id, String name, int team, int initiative, Cell cell, int hp, int maxHp,
                           int temporary, int armor, int reach, DeathSaveTracker.State life, Set<ConditionType> conditions) {
        public Creature { conditions = Set.copyOf(conditions); }
    }
    public record Slots(List<Integer> regular, List<Integer> pact) {
        public Slots { regular = List.copyOf(regular); pact = List.copyOf(pact); }

        public static final Slots EMPTY = new Slots(java.util.Collections.nCopies(9, 0), java.util.Collections.nCopies(9, 0));

        public int available(int level, boolean usePact) {
            var pool = usePact ? pact : regular;
            return level >= 1 && level <= pool.size() && level <= 9 ? pool.get(level - 1) : 0;
        }
    }
    public record Reaction(long id, int millisecondsLeft, List<Kind> choices, int totalDurationMs) {
        public Reaction(long id, int millisecondsLeft, List<Kind> choices) {
            this(id, millisecondsLeft, choices, ReactionWindowManager.WINDOW_MILLIS);
        }

        public Reaction {
            choices = List.copyOf(choices);
            if (totalDurationMs <= 0) totalDurationMs = ReactionWindowManager.WINDOW_MILLIS;
            millisecondsLeft = Math.max(0, Math.min(totalDurationMs, millisecondsLeft));
        }

        public double progress(long remainingMs) {
            return Math.max(0, Math.min(1, remainingMs / (double) totalDurationMs));
        }
    }
    public record Combat(UUID id, UUID controlledActor, UUID activeActor, int round, boolean paused,
                         List<Creature> initiative, Set<Resource> resources, int movement,
                         Map<Cell, Integer> reachable, Map<Cell, List<UUID>> threats, List<String> attacks,
                         List<String> spells, Slots slots, Reaction reaction) {
        public Combat {
            initiative = List.copyOf(initiative);
            if (NO_ACTOR.equals(controlledActor)) controlledActor = null;
            if (NO_ACTOR.equals(activeActor)) activeActor = null;
            resources = Set.copyOf(resources);
            reachable = Map.copyOf(reachable);
            threats = Map.copyOf(threats);
            attacks = List.copyOf(attacks);
            spells = List.copyOf(spells);
        }

        public boolean hasActor() { return creature(controlledActor) != null; }
        public boolean ownsTurn() { return !paused && activeActor != null && activeActor.equals(controlledActor) && hasActor(); }
        public TurnToken turn() { return new TurnToken(id, activeActor, round); }
        public Creature creature(UUID id) { return initiative.stream().filter(actor -> actor.id().equals(id)).findFirst().orElse(null); }
    }

    public record State(boolean operator, boolean dm, int darkvision, Combat combat) {}
    public record DialogueOption(String id, String label, Skill skill, int dc) {}
    public record Dialogue(UUID token, String title, String text, NpcAttitude attitude, List<DialogueOption> options) {
        public Dialogue { options = List.copyOf(options); }
    }
    public record Npc(UUID id, long revision, String name, BestiaryPreset preset, NpcAttitude attitude, boolean lair,
                      String greeting, List<DialogueOption> opening) {
        public Npc { opening = List.copyOf(opening); }
    }
    public record Zone(Cell marker, long revision, TriggerZoneSpec spec, boolean armed) {}
    public record PartyMember(UUID id, String name, int level, int hp, int maxHp, int temporary, int armor,
            DeathSaveTracker.State life) {}

    public record Roll(UUID player, String name, Vec3 position, int result, long seed) {}

    private Protocol() {}
}
