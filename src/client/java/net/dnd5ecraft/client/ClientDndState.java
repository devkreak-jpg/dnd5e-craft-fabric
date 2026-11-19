package net.dnd5ecraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dnd5ecraft.network.TabletopCodec.Card;
import net.dnd5ecraft.network.TabletopCodec.Prompt;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Weapon;
import net.dnd5ecraft.combat.PlayerWeaponProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
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

    public record TabletopResult(Card card, long time) {}
    private record WeaponHit(Weapon weapon, ItemStack item, boolean critical, boolean powered) {}
    private static final List<TabletopResult> results = new ArrayList<>();
    private static RollMode mode = RollMode.NORMAL;
    private static boolean whisper;
    private static boolean smell;

    public static boolean smell() { return smell; }
    public static void smell(boolean enabled) { smell = enabled; }
    private static boolean inspiration;
    private static boolean tabletop;
    private static boolean shortRest;
    private static Prompt prompt;
    private static WeaponHit lastHit;
    private static Map<UUID, String> concentration = Map.of();

    public static RollMode rollMode() { return mode; }
    public static void rollMode(RollMode next) { mode = next; }
    public static boolean whisper() { return whisper; }
    public static void whisper(boolean next) { whisper = next; }
    public static boolean inspiration() { return inspiration && sheet != null && sheet.resources().inspiration(); }
    public static void inspiration(boolean next) { inspiration = next; }
    public static boolean tabletop() { return tabletop; }
    public static boolean shortRest() { return shortRest; }
    public static Prompt prompt() { return prompt; }
    public static String concentration(UUID id) { return concentration.get(id); }

    static void tabletopReady() { tabletop = true; concentration = Map.of(); }
    static void shortRest(boolean next) { shortRest = next; }
    static void prompt(Prompt next) { prompt = next; }
    static void endPrompt(UUID id) { if (prompt != null && prompt.id().equals(id)) prompt = null; }
    static void concentration(Map<UUID, String> next) { concentration = Map.copyOf(next); }

    public static List<TabletopResult> tabletopResults() { return List.copyOf(results); }
    public static TabletopResult latestTabletop() { return results.isEmpty() ? null : results.get(results.size() - 1); }
    private static boolean matchesHit(Weapon weapon) {
        var player = Minecraft.getInstance().player;
        return player != null && lastHit != null && lastHit.weapon() == weapon
                && ItemStack.isSameItemSameTags(lastHit.item(), PlayerWeaponProfiles.equipped(player, weapon));
    }

    public static boolean critical(Weapon weapon) { return matchesHit(weapon) && lastHit.critical(); }
    public static boolean powerDamage(Weapon weapon, boolean selected) { return matchesHit(weapon) ? lastHit.powered() : selected; }

    static void tabletopResult(Card card) {
        long now = Util.getMillis();
        if (results.size() >= 36) results.remove(0);
        results.add(new TabletopResult(card, now));
        var player = Minecraft.getInstance().player;
        if (player == null || !card.roller().equals(player.getUUID())) return;
        player.playSound(net.minecraft.sounds.SoundEvents.BONE_BLOCK_PLACE, .45f, 1.35f);
        if (card.check().kind() == Kind.HIT) {
            var weapon = Weapon.valueOf(card.check().id());
            boolean powered = sheet != null && PlayerWeaponProfiles.tabletop(player, sheet, weapon).attackBonus()
                    - card.outcome().modifier() == 5;
            lastHit = new WeaponHit(weapon, PlayerWeaponProfiles.equipped(player, weapon).copy(),
                    card.outcome().kept() == 20, powered);
        } else if (card.check().kind() == Kind.DAMAGE && lastHit != null && lastHit.weapon().name().equals(card.check().id())) {
            lastHit = null;
        }
    }

    private ClientDndState() {}

    public static Protocol.State state() { return state; }
    public static Protocol.Combat combat() { return state.combat(); }
    public static boolean controlsCombatant() { return combat() != null && combat().hasActor(); }
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
            long deadline = Util.getMillis() + reaction.millisecondsLeft();
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
        draft = List.of();
        reactionDeadline = 0;
        mode = RollMode.NORMAL;
        whisper = inspiration = tabletop = smell = false;
        results.clear();
        prompt = null;
        clearWorldEffects();
    }

    static void clearWorldEffects() {
        banner = null;
        shortRest = false;
        lastHit = null;
        concentration = Map.of();
        dice.clear();
        pings.clear();
        notice = null;
        shakeUntil = 0;
        ClientActions.cancelAim();
    }
}
