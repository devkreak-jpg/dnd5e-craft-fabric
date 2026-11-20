package net.dnd5ecraft.server;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.AABB;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.dialogue.DialogueNbt;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.NpcSpawns;
import net.dnd5ecraft.entity.TriggerZoneBlockEntity;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DndCommands {
    private DndCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(commandTree()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return literal("dnd")
                .then(literal("em").then(argument("action", StringArgumentType.greedyString()).executes(context ->
                        run(context, player -> OnlineParty.emote(player, StringArgumentType.getString(context, "action"))))))
                .then(literal("pay").then(argument("recipient", EntityArgument.player())
                        .then(argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                .then(argument("denomination", StringArgumentType.word()).suggests((context, suggestions) ->
                                        net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("cp", "sp", "ep", "gp", "pp"), suggestions))
                                        .executes(context -> run(context, player -> OnlineParty.pay(player,
                                                EntityArgument.getPlayer(context, "recipient"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount"),
                                                StringArgumentType.getString(context, "denomination"))))))))
                .then(literal("ready").then(argument("id", net.minecraft.commands.arguments.UuidArgument.uuid())
                        .then(argument("ready", BoolArgumentType.bool()).executes(context -> run(context, player ->
                                OnlineParty.readyResponse(player, net.minecraft.commands.arguments.UuidArgument.getUuid(context, "id"),
                                        BoolArgumentType.getBool(context, "ready")))))))
                .then(literal("choose").then(argument("choice", StringArgumentType.word()).executes(context -> run(context, player -> {
                    if (!DialogueSessions.choose(player, StringArgumentType.getString(context, "choice"))) {
                        throw new IllegalStateException("No active dialogue choice");
                    }
                }))))
                .then(dmCommands())
                .then(literal("preset").requires(source -> source.hasPermission(2)).then(argument("hero", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                java.util.Arrays.stream(net.dnd5ecraft.rules.campaign.CampaignHero.values())
                                        .map(net.dnd5ecraft.rules.campaign.CampaignHero::id), builder))
                        .executes(context -> run(context, player -> {
                            var hero = net.dnd5ecraft.rules.campaign.CampaignHero.valueOf(word(context, "hero"));
                            CampaignCharacters.install(player, hero, hero.create());
                        }))))
                .then(literal("smell").then(argument("skill", StringArgumentType.word()).executes(context -> run(context, player -> {
                    var skill = Skill.valueOf(word(context, "skill"));
                    PlayerRules.show(player, PlayerRules.label("skill", skill.name()), PlayerRules.smell(player, skill, 0));
                }))))
                .then(literal("message").then(argument("recipient", EntityArgument.player())
                        .then(argument("text", StringArgumentType.greedyString()).executes(context -> run(context, player ->
                                CampaignMagic.cast(player, "message", EntityArgument.getPlayer(context, "recipient").getUUID(), null,
                                        StringArgumentType.getString(context, "text")))))))
                .then(literal("sheet").executes(context -> run(context, player -> ModPackets.sendSheet(player, true))))
                .then(literal("create").executes(context -> run(context, player -> {
                    PlayerRules.requireDowntime(player);
                    if (PlayerSheets.get(player).isPresent()) throw new IllegalStateException("You already have a character");
                    ModPackets.sendSheet(player, true);
                })))
                .then(literal("class").requires(source -> source.hasPermission(2)).then(argument("name", StringArgumentType.word()).executes(context -> run(context, player -> {
                    PlayerRules.requireDowntime(player);
                    levelUp(player, ClassType.valueOf(word(context, "name")), false);
                }))))
                .then(literal("subclass").requires(source -> source.hasPermission(2)).then(argument("name", StringArgumentType.word()).executes(context -> run(context, player -> {
                    PlayerRules.requireDowntime(player);
                    var type = net.dnd5ecraft.rules.character.SubclassType.valueOf(word(context, "name"));
                    PlayerSheets.set(player, PlayerSheets.getOrCreate(player).chooseSubclass(type));
                }))))
                .then(literal("feat").requires(source -> source.hasPermission(2)).then(argument("name", StringArgumentType.word())
                        .executes(context -> takeFeat(context, null))
                        .then(argument("ability", StringArgumentType.word()).executes(context -> takeFeat(context,
                                net.dnd5ecraft.rules.ability.Ability.fromId(word(context, "ability")))))))
                .then(literal("personality").then(argument("description", StringArgumentType.greedyString())
                        .executes(context -> run(context, player -> PlayerSheets.set(player, PlayerSheets.getOrCreate(player).withPersonality(
                                StringArgumentType.getString(context, "description")))))))
                .then(literal("race").executes(context -> run(context, player -> feedback(context, PlayerSheets.getOrCreate(player).race().name())))
                        .then(argument("name", StringArgumentType.word()).requires(source -> source.hasPermission(2))
                                .executes(context -> run(context, player -> {
                                    PlayerRules.requireDowntime(player);
                                    PlayerSheets.set(player, PlayerSheets.getOrCreate(player).withRace(RaceType.valueOf(word(context, "name"))));
                                }))))
                .then(literal("roll").executes(context -> run(context, player -> PlayerRules.roll(player, "1d20")))
                        .then(argument("dice", StringArgumentType.greedyString())
                                .executes(context -> run(context, player -> PlayerRules.roll(player, StringArgumentType.getString(context, "dice"))))))
                .then(rollCommand("check", false))
                .then(rollCommand("save", true))
                .then(literal("groupcheck").requires(source -> source.hasPermission(2))
                        .then(argument("skill", StringArgumentType.word()).then(argument("dc", IntegerArgumentType.integer(1, 40))
                                .executes(context -> groupCheck(context, nearbyParty(context.getSource().getPlayerOrException())))
                                .then(argument("party", EntityArgument.players())
                                        .executes(context -> groupCheck(context, EntityArgument.getPlayers(context, "party")))))))
                .then(literal("rest").then(literal("short").executes(context -> run(context, player -> PlayerRules.rest(player, false))))
                        .then(literal("long").executes(context -> run(context, player -> PlayerRules.rest(player, true)))))
                .then(literal("cast").then(argument("spell", StringArgumentType.word())
                        .then(argument("slot", IntegerArgumentType.integer(0, 9))
                                .executes(context -> cast(context, List.of(), null, Pool.REGULAR))
                                .then(literal("at").then(argument("point", Vec3Argument.vec3())
                                        .executes(context -> cast(context, List.of(), Vec3Argument.getVec3(context, "point"), Pool.REGULAR))))
                                .then(literal("pact").executes(context -> cast(context, List.of(), null, Pool.PACT))
                                        .then(argument("targets", EntityArgument.entities())
                                                .executes(context -> cast(context, targets(context), null, Pool.PACT))))
                                .then(argument("targets", EntityArgument.entities())
                                        .executes(context -> cast(context, targets(context), null, Pool.REGULAR))))))
                .then(literal("action").then(argument("maneuver", StringArgumentType.word())
                        .executes(context -> maneuver(context, null, 0, null))
                        .then(literal("option").then(argument("value", IntegerArgumentType.integer(0, 100))
                                .executes(context -> maneuver(context, null, IntegerArgumentType.getInteger(context, "value"), null))))
                        .then(literal("at").then(argument("point", BlockPosArgument.blockPos())
                                .executes(context -> maneuver(context, null, 0, BlockPosArgument.getBlockPos(context, "point")))))
                        .then(argument("target", EntityArgument.entity()).executes(context ->
                                maneuver(context, EntityArgument.getEntity(context, "target").getUUID(), 0, null))
                                .then(argument("value", IntegerArgumentType.integer(0, 100)).executes(context ->
                                        maneuver(context, EntityArgument.getEntity(context, "target").getUUID(),
                                                IntegerArgumentType.getInteger(context, "value"), null))))))
                .then(literal("next").executes(context -> control(context, (manager, actor) -> manager.endTurn(actor, TacticalCombat.now()))))
                .then(literal("investigate").then(argument("position", BlockPosArgument.blockPos())
                        .executes(context -> run(context, player -> zoneAt(context, player).investigate(player, false)))))
                .then(literal("disarm").then(argument("position", BlockPosArgument.blockPos())
                        .executes(context -> run(context, player -> zoneAt(context, player).investigate(player, true)))))
                .then(literal("possess").requires(source -> source.hasPermission(2))
                        .then(literal("release").executes(context -> run(context, player -> {
                            if (!DmMode.active(player)) throw new IllegalStateException("Enter DM mode first");
                            DmMode.release(player);
                        })))
                        .then(argument("npc", EntityArgument.entity()).executes(context -> run(context, player ->
                                DmMode.viewThrough(player, npc(context))))))
                .then(literal("combat").requires(source -> source.hasPermission(2))
                        .then(literal("start").executes(context -> run(context, TacticalCombat::startNearby)))
                        .then(literal("stop").executes(context -> run(context, player -> {
                            if (!TacticalCombat.stop(player.serverLevel())) throw new IllegalStateException("No active encounter");
                        }))))
                .then(literal("move").then(argument("position", BlockPosArgument.blockPos()).executes(context ->
                        control(context, (manager, actor) -> manager.move(actor,
                                TacticalCombat.cell(BlockPosArgument.getBlockPos(context, "position")), TacticalCombat.now())))))
                .then(literal("attack").then(argument("target", EntityArgument.entity())
                        .executes(context -> strike(context, 0))
                        .then(argument("profile", IntegerArgumentType.integer(1))
                                .executes(context -> strike(context, IntegerArgumentType.getInteger(context, "profile") - 1)))))
                .then(literal("end").executes(context -> control(context, (manager, actor) -> manager.endTurn(actor, TacticalCombat.now()))))
                .then(literal("dash").executes(context -> control(context, (manager, actor) -> manager.dash(actor, false, TacticalCombat.now()))))
                .then(literal("disengage").executes(context -> control(context,
                        (manager, actor) -> manager.disengage(actor, false, TacticalCombat.now()))))
                .then(literal("stand").executes(context -> control(context, (manager, actor) -> manager.stand(actor, TacticalCombat.now()))))
                .then(literal("react").then(argument("window", LongArgumentType.longArg(1))
                        .then(literal("decline").executes(context -> reaction(context, Optional.empty())))
                        .then(argument("kind", StringArgumentType.word()).then(argument("slot", IntegerArgumentType.integer(0, 9))
                                .executes(context -> run(context, player -> answer(context, player, Optional.of(new CombatManager.Reaction(
                                        Kind.valueOf(StringArgumentType.getString(context, "kind").toUpperCase(Locale.ROOT)),
                                        IntegerArgumentType.getInteger(context, "slot"), Pool.REGULAR)))))))))
                .then(literal("zone").requires(source -> source.hasPermission(2)).then(argument("position", BlockPosArgument.blockPos())
                        .executes(context -> run(context, player -> {
                            var pos = BlockPosArgument.getLoadedBlockPos(context, "position");
                            if (!(player.serverLevel().getBlockEntity(pos) instanceof TriggerZoneBlockEntity zone)) {
                                throw new IllegalArgumentException("No trigger marker at that position");
                            }
                            player.displayClientMessage(Component.translatable(zone.toggleArmed()
                                    ? "dnd-5e-craft.zone.armed" : "dnd-5e-craft.zone.disarmed"), true);
                        }))))
                .then(literal("npc").requires(source -> source.hasPermission(2))
                        .then(literal("spawn").then(argument("preset", StringArgumentType.word())
                                .then(argument("position", BlockPosArgument.blockPos()).executes(context -> run(context, player -> {
                                    var preset = BestiaryPreset.fromId(StringArgumentType.getString(context, "preset"));
                                    var pos = BlockPosArgument.getLoadedBlockPos(context, "position");
                                    var npc = NpcSpawns.spawn(player.serverLevel(), pos, preset, NpcAttitude.INDIFFERENT);
                                    if (npc.isEmpty()) throw new IllegalStateException("No space to spawn that NPC");
                                })))))
                        .then(argument("npc", EntityArgument.entity())
                                .then(literal("attitude").then(argument("value", StringArgumentType.word()).executes(context ->
                                        run(context, player -> npc(context).setAttitude(NpcAttitude.valueOf(
                                                StringArgumentType.getString(context, "value").toUpperCase(Locale.ROOT)))))))
                                .then(literal("lair").then(argument("enabled", BoolArgumentType.bool()).executes(context ->
                                        run(context, player -> npc(context).bestiary().setLairEnabled(
                                                BoolArgumentType.getBool(context, "enabled"))))))
                                .then(literal("dialogue").then(argument("tree", CompoundTagArgument.compoundTag()).executes(context ->
                                        run(context, player -> npc(context).setDialogue(DialogueNbt.read(
                                                CompoundTagArgument.getCompoundTag(context, "tree")))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dmCommands() {
        return literal("dm").requires(source -> source.hasPermission(2))
                .then(literal("build_demo_map").executes(context -> run(context, DemoMapBuilder::build))
                        .then(literal("here").executes(context -> run(context, DemoMapBuilder::buildHere))))
                .then(literal("skip_turn").executes(context -> run(context, TacticalCombat::skipOfflineTurn)))
                .then(literal("readycheck").executes(context -> run(context, player -> OnlineParty.readyCheck(player, "")))
                        .then(argument("label", StringArgumentType.greedyString()).executes(context ->
                                run(context, player -> OnlineParty.readyCheck(player, StringArgumentType.getString(context, "label"))))))
                .then(literal("secret").then(argument("recipient", EntityArgument.player())
                        .then(argument("message", StringArgumentType.greedyString()).executes(context -> run(context, player ->
                                OnlineParty.secret(player, EntityArgument.getPlayer(context, "recipient"),
                                        StringArgumentType.getString(context, "message")))))))
                .then(literal("handout").then(argument("audience", StringArgumentType.word())
                        .then(argument("card", StringArgumentType.greedyString()).executes(context -> run(context, player ->
                                OnlineParty.handout(player, StringArgumentType.getString(context, "audience"),
                                        StringArgumentType.getString(context, "card")))))))
                .then(literal("give_gear").then(argument("hero", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                java.util.Arrays.stream(net.dnd5ecraft.rules.campaign.CampaignHero.values())
                                        .map(net.dnd5ecraft.rules.campaign.CampaignHero::id), builder))
                        .executes(context -> giveGear(context, context.getSource().getPlayerOrException()))
                        .then(argument("target", EntityArgument.player())
                                .executes(context -> giveGear(context, EntityArgument.getPlayer(context, "target"))))))
                .then(literal("speak").then(argument("npc", EntityArgument.entity()).executes(context ->
                        run(context, player -> DmDirector.select(player, npc(context), true)))))
                .executes(context -> run(context, DndCommands::toggleDm))
                .then(literal("mode").executes(context -> run(context, DndCommands::toggleDm)))
                .then(literal("start").executes(context -> run(context, TacticalCombat::startNearby)))
                .then(literal("end").executes(context -> run(context, player -> {
                    if (!TacticalCombat.stop(player.serverLevel())) throw new IllegalStateException("No active encounter");
                })))
                .then(literal("freeze").executes(context -> run(context, player ->
                        feedback(context, CinematicFreeze.toggle(player) ? "Scene paused" : "Scene resumed"))))
                .then(literal("possess").then(literal("release").executes(context -> run(context, DmMode::release)))
                        .then(argument("npc", EntityArgument.entity()).executes(context -> run(context, player -> DmMode.viewThrough(player, npc(context))))))
                .then(literal("appearance").then(argument("npc", EntityArgument.entity())
                        .then(argument("scale", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(.3f, 4f))
                                .executes(context -> setAppearance(context, ""))
                                .then(argument("skin", StringArgumentType.string()).executes(context ->
                                        setAppearance(context, StringArgumentType.getString(context, "skin")))))))
                .then(literal("spawn").then(argument("preset", StringArgumentType.word())
                        .then(argument("position", BlockPosArgument.blockPos()).executes(context -> run(context, player -> {
                            var preset = BestiaryPreset.valueOf(word(context, "preset"));
                            var pos = BlockPosArgument.getLoadedBlockPos(context, "position");
                            NpcSpawns.spawn(player.serverLevel(), pos, preset, NpcAttitude.HOSTILE)
                                    .orElseThrow(() -> new IllegalStateException("No space to spawn that NPC"));
                        })))))
                .then(literal("encounter").executes(context -> run(context, player -> {
                    var party = nearbyParty(player).stream().map(target -> PlayerSheets.getOrCreate(target).level()).toList();
                    var monsters = player.serverLevel().getEntitiesOfClass(DndNpcEntity.class, area(player),
                            npc -> npc.isAlive() && npc.attitude() == NpcAttitude.HOSTILE).stream()
                            .map(npc -> new EncounterDifficultyCalculator.Monster(npc.preset().challenge())).toList();
                    var score = EncounterDifficultyCalculator.assess(party, monsters);
                    feedback(context, score.difficulty() + " | " + score.baseXp() + " XP | adjusted " + score.adjustedXp());
                    ModPackets.sendParty(player);
                })))
                .then(literal("requestroll").then(argument("player", EntityArgument.player())
                        .then(rollPrompt("check", false)).then(rollPrompt("save", true))))
                .then(literal("awardxp").then(argument("player", EntityArgument.player())
                        .then(argument("xp", IntegerArgumentType.integer(1, 355000)).executes(context -> run(context, operator -> {
                            var player = EntityArgument.getPlayer(context, "player");
                            PlayerSheets.set(player, PlayerSheets.getOrCreate(player).awardExperience(IntegerArgumentType.getInteger(context, "xp")));
                        })))))
                .then(literal("milestone").then(argument("player", EntityArgument.player())
                        .then(argument("class", StringArgumentType.word()).executes(context -> run(context, operator -> {
                            var target = EntityArgument.getPlayer(context, "player");
                            PlayerRules.requireDowntime(target);
                            levelUp(target, ClassType.valueOf(word(context, "class")), true);
                        })))))
                .then(literal("healall").executes(context -> run(context, operator ->
                        nearbyParty(operator).forEach(player -> TacticalCombat.dmHeal(player)))))
                .then(literal("revive").then(argument("player", EntityArgument.player()).executes(context -> run(context, operator -> {
                    var target = EntityArgument.getPlayer(context, "player");
                    PlayerRules.requireDowntime(target);
                    var sheet = PlayerSheets.getOrCreate(target);
                    if (sheet.deathSaves().state() != net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.DEAD) {
                        throw new IllegalStateException("The character is alive");
                    }
                    PlayerSheets.set(target, sheet.withCombatState(sheet.hitPoints().heal(1), sheet.conditions(),
                            net.dnd5ecraft.rules.conditions.DeathSaveTracker.CONSCIOUS));
                    target.setHealth(1);
                }))))
                .then(literal("inspire").then(argument("player", EntityArgument.player())
                        .executes(context -> run(context, operator -> TacticalCombat.dmInspire(EntityArgument.getPlayer(context, "player"))))));
    }

    private static void toggleDm(ServerPlayer player) {
        if (!DmMode.toggle(player)) ModPackets.notice(player, Component.translatable("dnd-5e-craft.dm.disabled"));
    }

    private static int setAppearance(CommandContext<CommandSourceStack> context, String skin) throws CommandSyntaxException {
        return run(context, player -> npc(context).setAppearance(skin,
                com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "scale")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rollPrompt(String name, boolean save) {
        return literal(name).then(argument("name", StringArgumentType.word()).executes(context -> run(context, operator -> {
            var target = EntityArgument.getPlayer(context, "player");
            String id = save ? net.dnd5ecraft.rules.ability.Ability.fromId(word(context, "name")).name()
                    : Skill.valueOf(word(context, "name")).name();
            String command = "/dnd " + name + " " + id.toLowerCase(Locale.ROOT);
            var label = PlayerRules.label(save ? "ability" : "skill", id);
            target.sendSystemMessage(Component.translatable("dnd-5e-craft.roll.request", label).withStyle(style -> style
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, command))));
        })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rollCommand(String name, boolean save) {
        return literal(name).then(argument("name", StringArgumentType.word())
                .executes(context -> run(context, player -> PlayerRules.requestRoll(player, save, word(context, "name"), 0)))
                .then(argument("dc", IntegerArgumentType.integer(1, 40)).executes(context ->
                        run(context, player -> PlayerRules.requestRoll(player, save, word(context, "name"), IntegerArgumentType.getInteger(context, "dc"))))));
    }

    private static int groupCheck(CommandContext<CommandSourceStack> context, java.util.Collection<ServerPlayer> party)
            throws CommandSyntaxException {
        return run(context, operator -> {
            var skill = Skill.valueOf(word(context, "skill"));
            int dc = IntegerArgumentType.getInteger(context, "dc");
            var results = party.stream().filter(player -> player.level() == operator.level() && !player.isSpectator())
                    .limit(net.dnd5ecraft.network.Protocol.MAX_COMBATANTS).map(player -> {
                        var result = PlayerRules.check(player, skill, dc);
                        PlayerRules.show(player, PlayerRules.label("skill", skill.name()), result);
                        return result;
                    }).toList();
            feedback(context, AbilityCheckEngine.groupSucceeds(results) ? "Group check succeeded" : "Group check failed");
        });
    }

    private static List<ServerPlayer> nearbyParty(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(ServerPlayer.class, area(player), target -> target.isAlive() && !target.isSpectator());
    }

    private static AABB area(ServerPlayer player) { return player.getBoundingBox().inflate(20, 8, 20); }

    private static List<java.util.UUID> targets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var entities = EntityArgument.getEntities(context, "targets");
        if (entities.size() > net.dnd5ecraft.network.Protocol.MAX_SPELL_TARGETS) throw rejected("Too many spell targets");
        return entities.stream().map(net.minecraft.world.entity.Entity::getUUID).toList();
    }

    private static int cast(CommandContext<CommandSourceStack> context, List<java.util.UUID> targets,
            net.minecraft.world.phys.Vec3 point, Pool pool) throws CommandSyntaxException {
        String spell = word(context, "spell").toLowerCase(Locale.ROOT);
        if (SpellRegistry5e.find(spell).isEmpty()) throw rejected("Unknown spell");
        var aim = Optional.ofNullable(point).map(pos -> new net.dnd5ecraft.rules.spells.AoEGeometry.Vec3(pos.x, pos.y, pos.z));
        return control(context, (manager, actor) -> manager.cast(actor, new CombatManager.CastRequest(spell,
                IntegerArgumentType.getInteger(context, "slot"), pool, targets, aim, Optional.empty()), TacticalCombat.now()));
    }

    private static int maneuver(CommandContext<CommandSourceStack> context, java.util.UUID target, int value,
            net.minecraft.core.BlockPos point) throws CommandSyntaxException {
        return control(context, (manager, actor) -> manager.maneuver(actor, CombatManager.Maneuver.valueOf(word(context, "maneuver")),
                target, value, point == null ? null : TacticalCombat.cell(point), TacticalCombat.now()));
    }

    private static TriggerZoneBlockEntity zoneAt(CommandContext<CommandSourceStack> context, ServerPlayer player)
            throws CommandSyntaxException {
        var pos = BlockPosArgument.getLoadedBlockPos(context, "position");
        if (player.serverLevel().getBlockEntity(pos) instanceof TriggerZoneBlockEntity zone) return zone;
        throw rejected("No trigger marker at that position");
    }

    private static int takeFeat(CommandContext<CommandSourceStack> context, net.dnd5ecraft.rules.ability.Ability ability)
            throws CommandSyntaxException {
        return run(context, player -> {
            PlayerRules.requireDowntime(player);
            var feat = net.dnd5ecraft.rules.character.FeatType.valueOf(word(context, "name"));
            var sheet = PlayerSheets.getOrCreate(player).takeFeat(feat, ability);
            PlayerSheets.set(player, sheet);
            player.setHealth(sheet.hitPoints().current());
        });
    }

    private static void levelUp(ServerPlayer player, ClassType type, boolean milestone) {
        var sheet = PlayerSheets.getOrCreate(player).levelUp(type, milestone, PlayerRules.dice(player));
        PlayerSheets.set(player, sheet);
        player.setHealth(Math.max(1, sheet.hitPoints().current()));
    }

    private static String word(CommandContext<CommandSourceStack> context, String name) {
        return StringArgumentType.getString(context, name).toUpperCase(Locale.ROOT);
    }

    private static void feedback(CommandContext<CommandSourceStack> context, String result) {
        context.getSource().sendSuccess(() -> Component.literal(result), false);
    }

    private static int strike(CommandContext<CommandSourceStack> context, int profile) throws CommandSyntaxException {
        return control(context, (manager, actor) -> manager.attack(actor, EntityArgument.getEntity(context, "target").getUUID(),
                profile, TacticalCombat.now()));
    }

    private static DndNpcEntity npc(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (EntityArgument.getEntity(context, "npc") instanceof DndNpcEntity npc) return npc;
        throw rejected("Select a D&D NPC");
    }

    private static int giveGear(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        try {
            var hero = net.dnd5ecraft.rules.campaign.CampaignHero.valueOf(word(context, "hero"));
            CampaignCharacters.giveGear(target, hero);
            context.getSource().sendSuccess(() -> Component.translatable("dnd-5e-craft.dm.gear_given", target.getDisplayName()), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw rejected(rejected.getMessage());
        }
    }

    private static int reaction(CommandContext<CommandSourceStack> context, Optional<CombatManager.Reaction> reaction)
            throws CommandSyntaxException {
        return run(context, player -> answer(context, player, reaction));
    }

    private static void answer(CommandContext<CommandSourceStack> context, ServerPlayer player,
            Optional<CombatManager.Reaction> reaction) {
        TacticalCombat.requireUnfrozen(player);
        var control = TacticalCombat.control(player);
        if (!control.manager().answerReaction(LongArgumentType.getLong(context, "window"),
                control.actor(), reaction.map(value -> control.manager().commandReaction(control.actor(), value)), TacticalCombat.now())) {
            throw new IllegalStateException("This reaction is unavailable or expired");
        }
    }

    private static int control(CommandContext<CommandSourceStack> context, CombatAction action) throws CommandSyntaxException {
        return run(context, player -> {
            TacticalCombat.requireUnfrozen(player);
            var control = TacticalCombat.control(player);
            action.apply(control.manager(), control.actor());
        });
    }

    private static int run(CommandContext<CommandSourceStack> context, PlayerAction action) throws CommandSyntaxException {
        try {
            action.apply(context.getSource().getPlayerOrException());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException invalid) {
            throw rejected(invalid.getMessage());
        }
    }

    private static CommandSyntaxException rejected(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    @FunctionalInterface
    private interface PlayerAction {
        void apply(ServerPlayer player) throws CommandSyntaxException;
    }

    @FunctionalInterface
    private interface CombatAction {
        void apply(CombatManager manager, java.util.UUID actor) throws CommandSyntaxException;
    }
}
