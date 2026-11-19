package net.dnd5ecraft.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.dnd5ecraft.combat.PlayerWeaponProfiles;
import net.dnd5ecraft.network.Protocol.Roll;
import net.dnd5ecraft.network.Protocol.Update;
import net.dnd5ecraft.network.TabletopCodec.Card;
import net.dnd5ecraft.network.TabletopCodec.Prompt;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Options;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.dnd5ecraft.rules.ability.TabletopRolls.Weapon;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.server.PlayerRules;
import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.server.TacticalCombat;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class TabletopPackets {
    private static final long PROMPT_TICKS = 1_200;
    private static final long SHORT_REST_TICKS = 1_200;
    private static final PlayerSessionStore<Session> SESSIONS = new PlayerSessionStore<>();

    private record Pending(Prompt prompt, UUID requester, long expires) {}
    private record LastHit(Weapon slot, ItemStack item, boolean critical, boolean power) {}
    private static final class Session {
        Pending prompt;
        LastHit hit;
        long nextPrompt;
        long restingUntil;
        boolean ready;
        Map<UUID, String> concentration = Map.of();
    }

    private TabletopPackets() {}

    public static boolean supports(ServerPlayer player) {
        return player.connection != null && ServerPlayNetworking.canSend(player, ModPackets.TABLETOP);
    }

    static void sync(ServerPlayer player) {
        if (!supports(player)) return;
        var session = session(player);
        if (!session.ready) {
            send(player, Update.TABLETOP_READY, out -> {});
            session.ready = true;
            if (session.prompt != null) send(player, Update.ROLL_PROMPT, out -> TabletopCodec.prompt(out, session.prompt.prompt()));
        }
        Map<UUID, String> concentration = TacticalCombat.networkConcentration(player);
        if (!concentration.equals(session.concentration)) {
            send(player, Update.CONCENTRATION, out -> {
                out.writeVarInt(concentration.size());
                concentration.forEach((id, spell) -> {
                    out.writeUUID(id);
                    out.writeUtf(spell, 64);
                });
            });
            session.concentration = concentration;
        }
    }

    static void resync(ServerPlayer player) {
        var session = session(player);
        session.ready = false;
        session.concentration = Map.of();
    }

    static void disconnect(ServerPlayer player) { SESSIONS.remove(player.getUUID()); }
    static void clear() { SESSIONS.clear(); }

    static void tick(ServerPlayer player) {
        if (!supports(player)) return;
        var session = session(player);
        long now = now(player);
        if (session.prompt != null && session.prompt.expires() <= now) endPrompt(player, session);
        if (session.restingUntil != 0 && (now >= session.restingUntil || TacticalCombat.isLocked(player)
                || PlayerSheets.unconscious(player) || player.isSpectator())) {
            session.restingUntil = 0;
            send(player, Update.SHORT_REST, out -> out.writeBoolean(false));
        }
    }

    static void startRest(ServerPlayer player) {
        require(player);
        PlayerRules.rest(player, false, false);
        session(player).restingUntil = now(player) + SHORT_REST_TICKS;
        send(player, Update.SHORT_REST, out -> out.writeBoolean(true));
    }

    static void roll(ServerPlayer player, Check check, Options options, boolean power) {
        require(player);
        if (power && check.kind() != Kind.HIT && check.kind() != Kind.DAMAGE) throw new IllegalArgumentException("Invalid power attack");
        var roller = new DiceRoller(PlayerRules.dice(player));
        var sheet = PlayerSheets.get(player).orElse(null);
        Component label = TabletopText.label(check);
        Outcome outcome;
        boolean whisper = options.whisper();
        switch (check.kind()) {
            case DICE -> {
                var pool = TabletopRolls.tray(check.id());
                if (pool.count() == 1 && pool.sides() == 20) {
                    outcome = TabletopRolls.d20(roller, PlayerRules.tabletopMode(player, check, options), 0, PlayerRules.lucky(player));
                } else {
                    var roll = roller.roll(check.id());
                    outcome = new Outcome(roll.rolls(), roll.kept().get(0), 0, roll.total(), pool.sides(), RollMode.NORMAL, false);
                }
            }
            case HIT, DAMAGE -> {
                var character = PlayerSheets.getOrCreate(player);
                var slot = Weapon.valueOf(check.id());
                if (!PlayerWeaponProfiles.available(player, slot)) throw new IllegalStateException("Weapon unavailable");
                var profile = PlayerWeaponProfiles.tabletop(player, character, slot);
                var state = session(player);
                var item = PlayerWeaponProfiles.equipped(player, slot);
                var prior = state.hit;
                label = TabletopText.text(check.kind() == Kind.HIT ? "tabletop.hit_name" : "tabletop.damage_name",
                        PlayerWeaponProfiles.label(player, slot));
                if (check.kind() == Kind.HIT) {
                    if (power && !PlayerWeaponProfiles.canPower(character, profile)) throw new IllegalStateException("Power attack unavailable");
                    outcome = TabletopRolls.d20(roller, PlayerRules.tabletopMode(player, check, options),
                            profile.attackBonus() - (power ? 5 : 0), PlayerRules.lucky(player));
                    state.hit = new LastHit(slot, item.copy(), outcome.kept() == 20, power);
                } else {
                    boolean matching = prior != null && prior.slot() == slot
                            && ItemStack.isSameItemSameTags(prior.item(), item);
                    boolean powered = (matching ? prior.power() : power) && PlayerWeaponProfiles.canPower(character, profile);
                    if (power && !powered && !matching) throw new IllegalStateException("Power attack unavailable");
                    outcome = TabletopRolls.weaponDamage(roller, profile.damage().get(0), matching && prior.critical(), powered ? 10 : 0);
                    if (matching) state.hit = null;
                }
            }
            case HIT_DIE -> {
                if (sheet == null || sheet.hitPoints().current() >= sheet.hitPoints().maximum()
                        || sheet.resources().hitDice().values().stream().noneMatch(value -> value > 0)) {
                    throw new IllegalStateException("No hit die can be spent");
                }
                var session = session(player);
                if (session.restingUntil <= now(player)) startRest(player);
                outcome = PlayerRules.spendHitDie(player);
            }
            case DEATH_SAVE -> outcome = PlayerSheets.tabletopDeathSave(player, options);
            default -> outcome = TabletopRolls.d20(roller, PlayerRules.tabletopMode(player, check, options),
                    TabletopRolls.modifier(sheet, check), PlayerRules.lucky(player));
        }
        publish(player, check, label, outcome, check.kind() == Kind.DEATH_SAVE ? 10 : 0, null, whisper);
    }

    static void request(ServerPlayer dm, UUID target, Check check, RollMode mode, int dc, boolean secret) {
        require(dm);
        if (!dm.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        var prompt = new Prompt(UUID.randomUUID(), dm.getGameProfile().getName(), check, mode, dc, secret);
        long now = now(dm);
        var session = session(dm);
        if (session.nextPrompt > now) throw new IllegalStateException("Wait before requesting another roll");
        var participants = dm.serverLevel().players().stream()
                .filter(player -> player.isAlive() && !player.isSpectator() && supports(player))
                .filter(player -> target == null || player.getUUID().equals(target))
                .limit(Protocol.MAX_COMBATANTS).toList();
        if (participants.isEmpty()) throw new IllegalStateException("No eligible players in this dimension");
        session.nextPrompt = now + 20;
        for (var participant : participants) {
            session(participant).prompt = new Pending(prompt, dm.getUUID(), now + PROMPT_TICKS);
            send(participant, Update.ROLL_PROMPT, out -> TabletopCodec.prompt(out, prompt));
        }
        ModPackets.notice(dm, TabletopText.text("tabletop.dm_sent", participants.size()));
    }

    static void answer(ServerPlayer player, UUID id) {
        require(player);
        var session = session(player);
        var pending = session.prompt;
        if (pending == null || !pending.prompt().id().equals(id) || pending.expires() <= now(player)) {
            throw new IllegalStateException("Roll prompt expired or unavailable");
        }
        var dm = player.getServer().getPlayerList().getPlayer(pending.requester());
        if (dm == null || !dm.hasPermissions(2)) {
            endPrompt(player, session);
            throw new IllegalStateException("Roll requester is unavailable");
        }
        endPrompt(player, session);
        var prompt = pending.prompt();
        var sheet = PlayerSheets.get(player).orElse(null);
        Outcome outcome = TabletopRolls.d20(new DiceRoller(PlayerRules.dice(player)), prompt.mode(),
                TabletopRolls.modifier(sheet, prompt.check()), PlayerRules.lucky(player));
        publish(player, prompt.check(), TabletopText.label(prompt.check()), outcome, prompt.dc(), pending.requester(), prompt.secret());
    }

    public static void dialogue(ServerPlayer player, UUID token, Check check, Outcome outcome, int dc, boolean whisper) {
        var card = publish(player, check, TabletopText.label(check), outcome, dc, null, whisper);
        send(player, Update.DIALOGUE_ROLL, out -> {
            out.writeUUID(token);
            TabletopCodec.card(out, card);
        });
    }

    private static Card publish(ServerPlayer player, Check check, Component label, Outcome outcome, int dc,
                                UUID requester, boolean whisper) {
        var card = new Card(player.getUUID(), player.getGameProfile().getName(), check, label, outcome, dc, requester, whisper);
        Roll die = outcome.sides() == 20 && check.kind() != Kind.DAMAGE
                && (check.kind() != Kind.DICE || check.id().equals("1d20"))
                ? new Roll(player.getUUID(), player.getGameProfile().getName(),
                            new Vec3(player.getX(), player.getY() + player.getBbHeight() + .55, player.getZ()),
                            outcome.kept(), player.getRandom().nextLong()) : null;
        for (var recipient : player.getServer().getPlayerList().getPlayers()) {
            boolean self = recipient == player;
            boolean sameLevel = player.level() == recipient.level();
            boolean requested = recipient.getUUID().equals(requester);
            if (!TabletopRolls.visible(whisper, self, recipient.hasPermissions(2))
                    || !whisper && !sameLevel && !requested) continue;
            if (die != null && sameLevel && player.distanceToSqr(recipient) <= 1600) {
                ModPackets.send(recipient, Update.ROLL, out -> PacketCodec.roll(out, die));
            }
            send(recipient, Update.TABLETOP_RESULT, out -> TabletopCodec.card(out, card));
            recipient.sendSystemMessage(TabletopText.line(card));
        }
        return card;
    }

    private static void endPrompt(ServerPlayer player, Session session) {
        UUID id = session.prompt.prompt().id();
        session.prompt = null;
        send(player, Update.PROMPT_END, out -> out.writeUUID(id));
    }

    static void require(ServerPlayer player) {
        if (!supports(player) || !player.isAlive() || player.isSpectator() && !player.hasPermissions(2)) {
            throw new IllegalStateException("Tabletop rolls unavailable");
        }
    }

    static void worldChanged(ServerPlayer player) {
        var session = session(player);
        session.hit = null;
        session.restingUntil = 0;
        send(player, Update.SHORT_REST, out -> out.writeBoolean(false));
        resync(player);
    }

    private static void send(ServerPlayer player, Update update, Consumer<FriendlyByteBuf> writer) {
        ModPackets.send(player, ModPackets.TABLETOP, update, writer);
    }

    private static Session session(ServerPlayer player) { return SESSIONS.getOrCreate(player.getUUID(), Session::new); }
    private static long now(ServerPlayer player) { return player.getServer().overworld().getGameTime(); }
}
