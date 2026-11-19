package net.dnd5ecraft.client;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;

public final class ClientDndState {
    public record Die(Protocol.Roll roll, long started) {}
    public record Ping(Cell cell, String player, long started) {}
    public record Notice(Component message, long until) {}

    private static Protocol.State state = new Protocol.State(false, false, 0, null);
    private static CharacterSheetData sheet;
    private static int sheetArmor;
    private static List<Protocol.PartyMember> party = List.of();
    private static Notice banner;
    private static List<Integer> draft = List.of();
    private static final List<Die> dice = new ArrayList<>();
    private static final List<Ping> pings = new ArrayList<>();
    private static Notice notice;
    private static long reactionDeadline;
    private static long shakeUntil;
    private static int shakeStrength;

    private ClientDndState() {}

    public static Protocol.State state() { return state; }
    public static Protocol.Combat combat() { return state.combat(); }
    public static CharacterSheetData sheet() { return sheet; }
    public static int sheetArmor() { return sheetArmor; }
    public static List<Protocol.PartyMember> party() { return party; }
    public static Notice banner() { return banner; }
    public static List<Integer> draft() { return draft; }
    public static List<Die> dice() { return List.copyOf(dice); }
    public static List<Ping> pings() { return List.copyOf(pings); }
    public static Notice notice() { return notice; }

    static void state(Protocol.State next) {
        var before = combat();
        var old = before == null ? null : before.reaction();
        var after = next.combat();
        var reaction = after == null ? null : after.reaction();
        if (reaction == null) reactionDeadline = 0;
        else {
            long deadline = Util.getMillis() + Math.min(3500, Math.max(0, reaction.millisecondsLeft()));
            reactionDeadline = old != null && before.id().equals(after.id()) && old.id() == reaction.id()
                    ? Math.min(reactionDeadline, deadline) : deadline;
        }
        state = next;
        if (!next.operator()) party = List.of();
    }

    static void sheet(CharacterSheetData next, int armor) { sheet = next; sheetArmor = armor; }
    static void party(List<Protocol.PartyMember> next) { party = state.operator() ? List.copyOf(next) : List.of(); }
    static void banner(Component title) { banner = new Notice(title, Util.getMillis() + 3000); }
    static void draft(List<Integer> next) { draft = List.copyOf(next); }
    static void roll(Protocol.Roll roll) {
        if (dice.size() >= 24) dice.remove(0);
        dice.add(new Die(roll, Util.getMillis()));
    }
    static void ping(Cell cell, String player) {
        if (pings.size() >= 16) pings.remove(0);
        pings.add(new Ping(cell, player, Util.getMillis()));
    }
    public static void notify(Component message) { notice = new Notice(message, Util.getMillis() + 3500); }
    static void damage(int amount) {
        shakeStrength = Math.min(8, amount);
        shakeUntil = Util.getMillis() + 320;
    }
    public static double shake() { return Math.max(0, shakeUntil - Util.getMillis()) / 320.0 * shakeStrength; }
    public static long reactionRemaining() { return Math.max(0, reactionDeadline - Util.getMillis()); }

    public static void tick() {
        long now = Util.getMillis();
        dice.removeIf(die -> now - die.started() > 3200);
        pings.removeIf(ping -> now - ping.started() > 6500);
        if (notice != null && notice.until() < now) notice = null;
        if (banner != null && banner.until() < now) banner = null;
    }

    static void reset() {
        state = new Protocol.State(false, false, 0, null);
        sheet = null;
        sheetArmor = 0;
        party = List.of();
        banner = null;
        draft = List.of();
        dice.clear();
        pings.clear();
        notice = null;
        reactionDeadline = shakeUntil = 0;
        ClientActions.cancelAim();
    }
}
