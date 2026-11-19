package net.dnd5ecraft.network;

import java.util.UUID;

public final class PacketPolicy {
    public enum Action {
        SYNC(false, false), ROLL(false, false), PING(false, false), DRAFT_ROLL(false, false), CREATE(false, false),
        TALK(false, false), CHOOSE(false, false), CLOSE_DIALOGUE(false, false),
        MOVE(false, true), ATTACK(false, true), END_TURN(false, true), DASH(false, true),
        DISENGAGE(false, true), STAND(false, true), SURGE(false, true), INTERACT(false, true), CAST(false, true),
        RITUAL_START(false, false), REACT(false, false),
        DM_MODE(true, false), START_COMBAT(true, false), STOP_COMBAT(true, false), POSSESS(true, false),
        RELEASE(true, false), PILOT(true, false), NPC_INSPECT(true, false), NPC_SAVE(true, false), NPC_SPAWN(true, false),
        ZONE_INSPECT(true, false), ZONE_SAVE(true, false),
        REQUEST_ROLL(false, false), REST(false, false), MANEUVER(false, true), PARTY(true, false),
        TABLETOP_ROLL(false, false), DM_ROLL_REQUEST(true, false), DM_ROLL_ANSWER(false, false),
        TABLETOP_CHOOSE(false, false), TABLETOP_REST(false, false),
        CAMPAIGN_PRESET(false, false), CAMPAIGN_ACTION(false, false), CAMPAIGN_COSMETICS(false, false),
        DIRECTOR(true, false), CAMPAIGN_SPELL(false, false), CAMPAIGN_POSE(false, false);

        private final boolean operator;
        private final boolean turn;
        Action(boolean operator, boolean turn) { this.operator = operator; this.turn = turn; }
        public boolean operator() { return operator; }
        public boolean turn() { return turn; }
    }

    public record TurnToken(UUID encounter, UUID actor, int round) {}

    private PacketPolicy() {}

    public static void require(Action action, boolean operator, TurnToken submitted, TurnToken authoritative) {
        if (action.operator() && !operator) throw new IllegalStateException("Operator permissions are required");
        if (action.turn() && (authoritative == null || !authoritative.equals(submitted))) {
            throw new IllegalStateException("The tactical turn has changed");
        }
    }

    public static int count(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Packet field exceeds its limit");
        return value;
    }

    public static final class RateLimit {
        private static final int BURST = 20;
        private static final long INTERVAL_NANOS = 100_000_000;
        private long last;
        private int available = BURST;
        private boolean initialized;

        public synchronized boolean take(long now) {
            if (!initialized) {
                last = now;
                initialized = true;
            }
            long elapsed = now - last;
            if (elapsed >= INTERVAL_NANOS) {
                long restored = elapsed / INTERVAL_NANOS;
                available = (int) Math.min(BURST, available + restored);
                last = now - elapsed % INTERVAL_NANOS;
            }
            if (available == 0) return false;
            available--;
            return true;
        }
    }
}
