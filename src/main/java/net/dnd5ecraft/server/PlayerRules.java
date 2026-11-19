package net.dnd5ecraft.server;

import java.util.Locale;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.entity.DiceDisplaySpawner;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.CheckResult;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.PassiveSenseTracker;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Options;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.PlayerSheetStorage;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerRules {
    private PlayerRules() {}

    public static IntUnaryOperator dice(ServerPlayer player) { return sides -> 1 + player.getRandom().nextInt(sides); }

    public static CheckResult check(ServerPlayer player, Skill skill, int dc) {
        var sheet = PlayerSheets.getOrCreate(player);
        var roll = new DiceRoller(dice(player)).d20(checkMode(sheet), sheet.skillModifier(skill), lucky(sheet));
        return new CheckResult(roll.kept().get(0), roll.modifier(), dc);
    }

    public static CheckResult save(ServerPlayer player, Ability ability, int dc) {
        var sheet = PlayerSheets.getOrCreate(player);
        RollMode mode = RollMode.from(false, sheet.exhaustion().savingThrowDisadvantage());
        var roll = new DiceRoller(dice(player)).d20(mode, sheet.savingThrowModifier(ability), lucky(sheet));
        return new CheckResult(roll.kept().get(0), roll.modifier(), dc);
    }

    public static int passive(ServerPlayer player, Skill skill) {
        var sheet = PlayerSheets.getOrCreate(player);
        return PassiveSenseTracker.score(sheet.skillModifier(skill), checkMode(sheet));
    }

    public static void requestRoll(ServerPlayer player, boolean savingThrow, String name, int dc) {
        if (player.isSpectator() && !player.hasPermissions(2)) throw new IllegalStateException("A spectator cannot roll");
        if (savingThrow) {
            Ability ability = Ability.fromId(name);
            show(player, label("ability", ability.name()), save(player, ability, dc));
        } else {
            Skill skill = Skill.valueOf(name.toUpperCase(Locale.ROOT));
            show(player, label("skill", skill.name()), check(player, skill, dc));
        }
    }

    public static void show(ServerPlayer player, Component label, CheckResult result) {
        ModPackets.sendRoll(player, result.naturalRoll());
        if (!ModPackets.supports(player)) DiceDisplaySpawner.spawn(player.serverLevel(), player, result.naturalRoll());
        ModPackets.notice(player, result.difficulty() > 0
                ? Component.translatable("dnd-5e-craft.roll.check_dc", label, result.naturalRoll(), result.modifier(),
                        result.total(), result.difficulty(), Component.translatable(result.succeeds()
                                ? "dnd-5e-craft.roll.success" : "dnd-5e-craft.roll.failure"))
                : Component.translatable("dnd-5e-craft.roll.check", label, result.naturalRoll(), result.modifier(), result.total()));
    }

    public static void roll(ServerPlayer player, String expression) {
        var result = new DiceRoller(dice(player)).roll(expression, PlayerSheets.get(player).map(PlayerRules::lucky).orElse(false), false);
        String normalized = expression.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (java.util.Set.of("d20", "1d20", "advantage", "disadvantage").contains(normalized)) {
            ModPackets.sendRoll(player, result.kept().get(0));
            if (!ModPackets.supports(player)) DiceDisplaySpawner.spawn(player.serverLevel(), player, result.kept().get(0));
        }
        ModPackets.notice(player, Component.translatable("dnd-5e-craft.roll.expression", expression,
                result.kept().toString(), result.modifier(), result.total()));
    }

    public static RollMode tabletopMode(ServerPlayer player, Check check, Options options) {
        var sheet = PlayerSheets.get(player).orElse(null);
        boolean disadvantage = TabletopRolls.disadvantaged(sheet, check.kind());
        if (options.inspiration()) {
            if (sheet == null || !sheet.resources().inspiration()) throw new IllegalStateException("Inspiration is unavailable");
            if (TacticalCombat.isLocked(player)) TacticalCombat.require(player).spendTabletopInspiration(player.getUUID());
            PlayerSheets.set(player, sheet.withResources(sheet.resources().withInspiration(false)));
        }
        return TabletopRolls.checkMode(options.mode(), options.inspiration(), disadvantage);
    }

    public static boolean lucky(ServerPlayer player) { return PlayerSheets.get(player).map(PlayerRules::lucky).orElse(false); }

    public static void rest(ServerPlayer player, boolean longRest) { rest(player, longRest, true); }

    public static void rest(ServerPlayer player, boolean longRest, boolean spendDie) {
        requireDowntime(player);
        var sheet = PlayerSheets.getOrCreate(player);
        if (sheet.deathSaves().state() != DeathSaveTracker.State.CONSCIOUS) {
            throw new IllegalStateException("You must be conscious to rest");
        }
        var storage = PlayerSheetStorage.get(player.serverLevel().getServer());
        long now = player.serverLevel().getServer().overworld().getGameTime();
        if (!storage.canRest(player.getUUID(), now, longRest)) throw new IllegalStateException("You rested too recently");
        Optional<ClassType> die = sheet.resources().hitDice().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(java.util.Map.Entry::getKey).max(java.util.Comparator.comparingInt(ClassType::hitDie));
        boolean nourished = player.getFoodData().getFoodLevel() >= 6;
        var after = longRest ? sheet.longRest(nourished)
                : sheet.shortRest(spendDie && sheet.hitPoints().current() < sheet.hitPoints().maximum() ? die : Optional.empty(), dice(player));
        if (!longRest) after = arcaneRecovery(after);
        PlayerSheets.set(player, after);
        storage.recordRest(player.getUUID(), now, longRest);
        player.setHealth(Math.max(1, after.hitPoints().current()));
        ModPackets.notice(player, Component.translatable(longRest ? "dnd-5e-craft.rest.long_done" : "dnd-5e-craft.rest.short_done"));
    }

    public static Outcome spendHitDie(ServerPlayer player) {
        requireDowntime(player);
        var sheet = PlayerSheets.getOrCreate(player);
        if (sheet.deathSaves().state() != DeathSaveTracker.State.CONSCIOUS || sheet.exhaustion().dead()
                || sheet.hitPoints().current() >= sheet.hitPoints().maximum()) {
            throw new IllegalStateException("A conscious character must be injured to spend a hit die");
        }
        var type = sheet.resources().hitDice().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(java.util.Map.Entry::getKey).max(java.util.Comparator.comparingInt(ClassType::hitDie))
                .orElseThrow(() -> new IllegalStateException("No hit dice remain"));
        int roll = dice(player).applyAsInt(type.hitDie());
        int con = Ability.modifier(sheet.score(Ability.CONSTITUTION));
        int total = Math.max(0, roll + con);
        var hp = sheet.hitPoints().heal(total);
        PlayerSheets.set(player, sheet.withCombatState(hp, sheet.conditions(), sheet.deathSaves())
                .withResources(sheet.resources().spendHitDie(type)));
        player.setHealth(Math.max(1, hp.current()));
        return new Outcome(java.util.List.of(roll), roll, con, total, type.hitDie(), RollMode.NORMAL, false);
    }

    private static CharacterSheetData arcaneRecovery(CharacterSheetData sheet) {
        var feature = net.dnd5ecraft.rules.character.ClassFeature.ARCANE_RECOVERY;
        if (sheet.remaining(feature) == 0) return sheet;
        int wizard = sheet.classLevels().getOrDefault(ClassType.WIZARD, 0);
        int budget = (wizard + 1) / 2;
        var ledger = new net.dnd5ecraft.rules.spells.SpellSlotTable.Ledger(sheet.classLevels(), sheet.resources().slots());
        boolean restored = false;
        for (int slot = Math.min(budget, 5); slot > 0; slot--) {
            while (budget >= slot && ledger.canRecover(slot)) {
                ledger.recover(slot);
                budget -= slot;
                restored = true;
            }
        }
        if (!restored) return sheet;
        return sheet.withResources(sheet.spend(feature, 1).resources().withSlots(ledger.spent()));
    }

    public static void requireDowntime(ServerPlayer player) {
        if (TacticalCombat.isLocked(player) || CinematicFreeze.frozen(player) || player.isSpectator()) {
            throw new IllegalStateException("This action requires downtime");
        }
    }

    private static RollMode checkMode(CharacterSheetData sheet) {
        return RollMode.from(false, sheet.exhaustion().abilityCheckDisadvantage()
                || sheet.conditions().stream().anyMatch(active -> active.type() == ConditionType.POISONED
                        || active.type() == ConditionType.FRIGHTENED));
    }

    private static boolean lucky(CharacterSheetData sheet) { return sheet.race() == RaceType.HALFLING; }

    public static Component label(String category, String name) {
        return Component.translatable("dnd-5e-craft.ui." + category + "." + name.toLowerCase(Locale.ROOT));
    }
}
