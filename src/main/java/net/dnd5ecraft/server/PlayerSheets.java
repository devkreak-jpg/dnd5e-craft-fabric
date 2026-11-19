package net.dnd5ecraft.server;

import java.util.Map;
import java.util.Optional;
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
    private static final Map<ServerPlayer, Long> NEXT_DEATH_SAVE = new java.util.HashMap<>();

    private PlayerSheets() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                get(handler.player).ifPresent(sheet -> ((PlayerCharacter) handler.player).dnd5e$setSheet(sheet)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NEXT_DEATH_SAVE.remove(handler.player));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server ->
                server.getPlayerList().getPlayers().forEach(PlayerSheets::tickDeathSaves));
        ServerPlayerEvents.COPY_FROM.register((previous, current, alive) -> {
            NEXT_DEATH_SAVE.remove(previous);
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
        var dying = net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.DYING;
        var sheet = ((PlayerCharacter) player).dnd5e$sheet().orElse(null);
        if (sheet == null || sheet.deathSaves().state() != dying || !unconscious(player)
                || TacticalCombat.isLocked(player) || CinematicFreeze.frozen(player)) {
            NEXT_DEATH_SAVE.remove(player);
            return;
        }
        long now = player.serverLevel().getGameTime();
        if (now < NEXT_DEATH_SAVE.computeIfAbsent(player, ignored -> now + 120)) return;
        NEXT_DEATH_SAVE.put(player, now + 120);
        int natural = sheet.race().naturalD20(PlayerRules.dice(player));
        var result = sheet.deathSaves().roll(natural, 0);
        var hp = sheet.hitPoints().heal(result.hitPointsRestored());
        set(player, sheet.withCombatState(hp, sheet.conditions(), result.tracker()));
        player.setHealth(Math.max(1, hp.current()));
        net.dnd5ecraft.network.ModPackets.sendRoll(player, natural);
        net.dnd5ecraft.network.ModPackets.notice(player, net.minecraft.network.chat.Component.translatable(
                "dnd-5e-craft.roll.death_save", natural, result.tracker().successes(), result.tracker().failures()));
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
