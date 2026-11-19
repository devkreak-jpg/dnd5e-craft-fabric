package net.dnd5ecraft.server;

import java.util.Map;
import java.util.Optional;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.network.TabletopPackets;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Options;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.PlayerSheetStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerSheets {
    private record DeathSaveTiming(long automatic, long manual) {}
    private static final Map<ServerPlayer, DeathSaveTiming> NEXT_DEATH_SAVE = new java.util.HashMap<>();

    private PlayerSheets() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                get(handler.player).ifPresent(sheet -> ((PlayerCharacter) handler.player).dnd5e$setSheet(sheet)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NEXT_DEATH_SAVE.remove(handler.player));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server ->
                server.getPlayerList().getPlayers().forEach(PlayerSheets::tickDeathSaves));
        ServerPlayerEvents.COPY_FROM.register((previous, current, alive) -> {
            NEXT_DEATH_SAVE.remove(previous);
            ((PlayerCharacter) current).dnd5e$journalNotes(((PlayerCharacter) previous).dnd5e$journalNotes());
            ((PlayerCharacter) current).dnd5e$setCosmetics(((PlayerCharacter) previous).dnd5e$showsCosmetics());
            get(previous).ifPresent(sheet -> {
                set(current, sheet);
                current.setHealth(Math.max(1, sheet.hitPoints().current()));
            });
        });
    }

    public static boolean unconscious(ServerPlayer player) {
        return ((PlayerCharacter) player).dnd5e$sheet().filter(sheet -> sheet.hitPoints().current() == 0).isPresent()
                && !player.isSpectator() && !player.isCreative();
    }

    private static void tickDeathSaves(ServerPlayer player) {
        var dying = DeathSaveTracker.State.DYING;
        var sheet = ((PlayerCharacter) player).dnd5e$sheet().orElse(null);
        if (sheet == null || sheet.deathSaves().state() != dying || !unconscious(player)
                || TacticalCombat.isLocked(player) || CinematicFreeze.frozen(player)) {
            NEXT_DEATH_SAVE.remove(player);
            return;
        }
        long now = player.serverLevel().getGameTime();
        var timing = NEXT_DEATH_SAVE.computeIfAbsent(player, ignored -> new DeathSaveTiming(now + deathSaveDelay(player), now));
        if (now < timing.automatic()) return;
        var roll = rollDeathSave(player, sheet, RollMode.NORMAL);
        ModPackets.sendRoll(player, roll.kept());
        var result = get(player).orElseThrow().deathSaves();
        ModPackets.notice(player, net.minecraft.network.chat.Component.translatable(
                "dnd-5e-craft.roll.death_save", roll.kept(), result.successes(), result.failures()));
    }

    public static Outcome tabletopDeathSave(ServerPlayer player,
            Options options) {
        var check = new Check(Kind.DEATH_SAVE, "");
        var sheet = get(player).orElseThrow(() -> new IllegalStateException("A character is required"));
        if (sheet.deathSaves().state() != DeathSaveTracker.State.DYING
                || player.isCreative() || player.isSpectator() || CinematicFreeze.frozen(player)) {
            throw new IllegalStateException("No death save is due");
        }
        if (TacticalCombat.isLocked(player)) {
            TacticalCombat.requireUnfrozen(player);
            if (TacticalCombat.require(player).pendingDeathSave().filter(player.getUUID()::equals).isEmpty()) {
                throw new IllegalStateException("No death save is due");
            }
            return TacticalCombat.rollDeathSave(player, PlayerRules.tabletopMode(player, check, options));
        }
        long now = player.serverLevel().getGameTime();
        var timing = NEXT_DEATH_SAVE.get(player);
        if (timing != null && now < timing.manual()) throw new IllegalStateException("Wait before rolling another death save");
        var mode = PlayerRules.tabletopMode(player, check, options);
        return rollDeathSave(player, get(player).orElseThrow(), mode);
    }

    private static Outcome rollDeathSave(ServerPlayer player,
            CharacterSheetData sheet, RollMode mode) {
        long now = player.serverLevel().getGameTime();
        NEXT_DEATH_SAVE.put(player, new DeathSaveTiming(now + deathSaveDelay(player), now + 120));
        var roll = TabletopRolls.d20(
                new DiceRoller(PlayerRules.dice(player)), mode, 0, sheet.race() == RaceType.HALFLING);
        var result = sheet.deathSaves().roll(roll.kept(), 0);
        var hp = sheet.hitPoints().heal(result.hitPointsRestored());
        set(player, sheet.withCombatState(hp, sheet.conditions(), result.tracker()));
        player.setHealth(Math.max(1, hp.current()));
        announceDowntimeDeathState(player, sheet.deathSaves(), result.tracker());
        return roll;
    }

    static void announceDowntimeDeathState(ServerPlayer player, DeathSaveTracker before, DeathSaveTracker after) {
        if (before.state() == after.state()) return;
        String key = switch (after.state()) {
            case DYING -> "dnd-5e-craft.combat.downtime_downed";
            case DEAD -> "dnd-5e-craft.combat.downtime_dead";
            default -> null;
        };
        if (key == null) return;
        player.getServer().getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.translatable(key, player.getDisplayName()), false);
    }

    private static int deathSaveDelay(ServerPlayer player) {
        return TabletopPackets.supports(player) ? 200 : 120;
    }

    public static Optional<CharacterSheetData> get(ServerPlayer player) {
        var saved = PlayerSheetStorage.get(player.serverLevel().getServer());
        return saved.find(player.getUUID()).or(() -> {
            var legacy = ((PlayerCharacter) player).dnd5e$sheet();
            legacy.ifPresent(sheet -> saved.put(player.getUUID(), sheet));
            return legacy;
        });
    }

    public static void set(ServerPlayer player, CharacterSheetData sheet) {
        PlayerSheetStorage.get(player.serverLevel().getServer()).put(player.getUUID(), sheet);
        ((PlayerCharacter) player).dnd5e$setSheet(sheet);
    }

    public static CharacterSheetData getOrCreate(ServerPlayer player) {
        return get(player).orElseGet(() -> {
            var sheet = CharacterSheetData.create(player.getGameProfile().getName(), RaceType.HUMAN, BackgroundType.SOLDIER,
                    ClassType.FIGHTER, Map.of(Ability.STRENGTH, 15, Ability.DEXTERITY, 13, Ability.CONSTITUTION, 14,
                            Ability.INTELLIGENCE, 10, Ability.WISDOM, 12, Ability.CHARISMA, 8));
            set(player, sheet);
            return sheet;
        });
    }
}
