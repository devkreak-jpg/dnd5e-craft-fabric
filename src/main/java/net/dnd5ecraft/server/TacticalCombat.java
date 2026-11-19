package net.dnd5ecraft.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import net.dnd5ecraft.combat.CombatEncounterState;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.CombatantEntry;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.MonsterAiDirector;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.network.PacketPolicy.TurnToken;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.rules.spells.SpellAccess;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine.Formula;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.equipment.WeaponProperty;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TacticalCombat {
    public static final String MAGIC_DARKNESS_TAG = "dnd-5e-craft.magical_darkness";
    private static final Map<ServerLevel, Session> ACTIVE = new HashMap<>();

    public record Control(CombatManager manager, UUID actor) {}

    private TacticalCombat() {}

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            Session encounter = ACTIVE.get(level);
            if (encounter != null) encounter.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session session = session(handler.player);
            if (session != null) session.remove(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var levels = ACTIVE.keySet().stream().filter(level -> level.getServer() == server).toList();
            levels.forEach(TacticalCombat::stop);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !blocksVanillaInteraction(player));
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                blocksVanillaInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                blocksVanillaInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((actor, level, hand, target, hit) -> {
            if (!(actor instanceof ServerPlayer player)) return InteractionResult.PASS;
            if (CinematicFreeze.frozen(player) || PlayerSheets.unconscious(player)) return InteractionResult.FAIL;
            if (!isLocked(player)) return InteractionResult.PASS;
            if (target instanceof LivingEntity defender) {
                try {
                    requireUnfrozen(player);
                    require(player).attack(player.getUUID(), defender.getUUID(), 0, now());
                    return InteractionResult.SUCCESS;
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    player.displayClientMessage(Component.literal(failure.getMessage()), true);
                }
            }
            return InteractionResult.FAIL;
        });
    }

    private static boolean blocksVanillaInteraction(LivingEntity entity) {
        return isLocked(entity) || entity instanceof ServerPlayer player
                && (CinematicFreeze.frozen(player) || PlayerSheets.unconscious(player));
    }

    public static long now() { return System.nanoTime() / 1_000_000L; }

    public static boolean isLocked(LivingEntity entity) { return session(entity) != null; }

    public static void requireUnfrozen(ServerPlayer player) {
        if (CinematicFreeze.active(player.serverLevel())) throw new IllegalStateException("The scene is paused");
    }

    public static CombatManager require(ServerPlayer player) {
        Session session = session(player);
        if (session == null) throw new IllegalStateException("You are not in a tactical encounter");
        return session.manager;
    }

    public static Control control(ServerPlayer player) {
        if (player.getCamera() instanceof DndNpcEntity npc && DmMode.controls(player, npc)) {
            Session session = session(npc);
            if (session != null) return new Control(session.manager, npc.getUUID());
        }
        if (player.isSpectator()) throw new IllegalStateException("A spectator does not own a tactical actor");
        return new Control(require(player), player.getUUID());
    }

    private static Session controlledSession(ServerPlayer player) {
        if (player.getCamera() instanceof DndNpcEntity npc && DmMode.controls(player, npc)) return session(npc);
        return player.isSpectator() ? null : session(player);
    }

    public static UUID encounterId(ServerPlayer player) {
        Session session = controlledSession(player);
        return session == null ? null : session.id;
    }

    public static TurnToken turnToken(ServerPlayer player) {
        Session session = controlledSession(player);
        if (session == null || CinematicFreeze.active(session.level) || session.manager.reactionWindow().isPresent()) return null;
        var actor = control(player).actor();
        return session.manager.snapshot().current().filter(actor::equals)
                .map(current -> new TurnToken(session.id, current, session.state.round())).orElse(null);
    }

    public static Protocol.Combat networkView(ServerPlayer player) {
        Session session = controlledSession(player);
        return session == null ? null : session.networkView(control(player).actor());
    }

    public static int networkDarkvision(ServerPlayer player) {
        if (player.getCamera().getTags().contains(MAGIC_DARKNESS_TAG)) return 0;
        Session session = controlledSession(player);
        if (session != null && session.state.require(control(player).actor()).conditionEffects()
                .has(net.dnd5ecraft.rules.conditions.ConditionType.BLINDED)) return 0;
        if (player.getCamera() instanceof DndNpcEntity npc && DmMode.controls(player, npc)) return npc.preset().darkvisionFeet();
        return PlayerSheets.get(player).map(sheet -> sheet.race().darkvisionFeet()).orElse(0);
    }

    public static void movePath(ServerPlayer player, Cell destination) {
        Session session = controlledSession(player);
        if (session == null) throw new IllegalStateException("No active encounter");
        UUID actor = control(player).actor();
        var path = session.manager.path(actor, destination).orElseThrow(() -> new IllegalArgumentException("Destination cannot be reached this turn"));
        session.path.clear();
        session.pendingStep = null;
        session.pathActor = actor;
        session.path.addAll(path.cells().subList(1, path.cells().size()));
        session.advancePath(now());
    }

    public static void cancelPath(ServerPlayer player) {
        Session session = controlledSession(player);
        if (session != null && control(player).actor().equals(session.pathActor)) session.path.clear();
    }

    public static List<String> performRitual(ServerPlayer player, String spell) {
        var sheet = PlayerSheets.get(player).orElseThrow(() -> new IllegalStateException("Create a character first"));
        var access = SpellAccess.available(sheet);
        if (isLocked(player) || !SpellAccess.rituals(sheet, access).contains(spell)) throw new IllegalStateException("Ritual unavailable");
        var level = player.serverLevel();
        var targets = level.getEntitiesOfClass(DndNpcEntity.class, player.getBoundingBox().inflate(6),
                npc -> npc.isAlive() && !npc.blockPosition().equals(player.blockPosition()));
        List<LivingEntity> entities = new ArrayList<>();
        entities.add(player);
        Set<BlockPos> occupied = new java.util.HashSet<>();
        occupied.add(player.blockPosition());
        targets.stream().filter(npc -> occupied.add(npc.blockPosition())).limit(Protocol.MAX_COMBATANTS - 1L).forEach(entities::add);
        Session ritual = new Session(level, entities);
        ritual.manager.castOutsideCombat(player.getUUID(), new CombatManager.CastRequest(spell, 0, List.of()),
                true, net.dnd5ecraft.rules.spells.SpellRegistry5e.require(spell).ritualCastingMinutes(), now());
        return ritual.manager.detectedMagic(player.getUUID()).stream()
                .map(id -> ritual.entities.get(id).getDisplayName().getString()).toList();
    }

    private static Session session(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        Session session = ACTIVE.get(level);
        return session != null && session.entities.get(entity.getUUID()) == entity ? session : null;
    }

    public static void startNearby(ServerPlayer operator) {
        if (!operator.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        ServerLevel level = operator.serverLevel();
        AABB area = operator.getBoundingBox().inflate(20, 8, 20);
        var players = level.getEntitiesOfClass(ServerPlayer.class, area, TacticalCombat::canEnroll);
        var npcs = level.getEntitiesOfClass(DndNpcEntity.class, area, npc -> npc.isAlive()
                && npc.deathSaves().state() != DeathSaveTracker.State.DEAD
                && players.stream().anyMatch(player -> npc.attitudeFor(player.getUUID()) == NpcAttitude.HOSTILE));
        start(level, players, npcs, Set.of());
    }

    private static boolean canEnroll(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
                && !player.getAbilities().flying
                && PlayerSheets.get(player).map(sheet -> sheet.deathSaves().state() != DeathSaveTracker.State.DEAD).orElse(true);
    }

    public static void tryAmbush(ServerLevel level, List<ServerPlayer> players, List<DndNpcEntity> npcs) {
        if (ACTIVE.containsKey(level)) return;
        try {
            start(level, players.stream().filter(TacticalCombat::canEnroll).toList(), npcs,
                    players.stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet()));
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            // The spawned creatures remain in the world when an encounter cannot be started.
        }
    }

    private static void start(ServerLevel level, List<ServerPlayer> players, List<DndNpcEntity> npcs, Set<UUID> surprised) {
        if (ACTIVE.containsKey(level)) throw new IllegalStateException("This dimension already has a tactical encounter");
        if (players.isEmpty() || npcs.isEmpty()) throw new IllegalStateException("An encounter needs players and hostile NPCs");
        List<LivingEntity> entities = new ArrayList<>(players);
        entities.addAll(npcs);
        if (entities.size() > Protocol.MAX_COMBATANTS) throw new IllegalArgumentException("Too many combatants");
        if (entities.stream().map(LivingEntity::blockPosition).distinct().count() != entities.size()) {
            throw new IllegalStateException("Move creatures into separate grid cells first");
        }
        Session session = new Session(level, entities);
        ACTIVE.put(level, session);
        try {
            session.activate(surprised);
        } catch (RuntimeException failure) {
            ACTIVE.remove(level, session);
            entities.stream().filter(DndNpcEntity.class::isInstance).map(DndNpcEntity.class::cast)
                    .forEach(DndNpcEntity::restoreAiAfterCombat);
            throw failure;
        }
    }

    public static boolean stop(ServerLevel level) {
        Session session = ACTIVE.remove(level);
        if (session == null) return false;
        session.restore();
        return true;
    }

    public static boolean environmentDamage(LivingEntity target, DamageType5e damage, int amount) {
        Session session = session(target);
        if (session != null) {
            session.manager.damage(target.getUUID(), target.getUUID(), Map.of(damage, amount), false);
            return true;
        }
        if (target instanceof ServerPlayer player) {
            applyDowntimeDamage(player, PlayerSheets.getOrCreate(player), damage, amount);
            return true;
        }
        return false;
    }

    private static boolean applyDowntimeDamage(ServerPlayer player, CharacterSheetData sheet, DamageType5e type, int amount) {
        if (sheet.deathSaves().state() == DeathSaveTracker.State.DEAD) return false;
        var hp = sheet.hitPoints();
        var defenses = new Defenses(Set.of(), sheet.race().resistances(), Set.of());
        var feature = net.dnd5ecraft.rules.character.ClassFeature.RELENTLESS_ENDURANCE;
        var hit = net.dnd5ecraft.rules.equipment.DamagePipeline.apply(Map.of(type, amount), defenses, hp, sheet.remaining(feature) > 0);
        var death = sheet.deathSaves().afterDamage(hp, hit, false);
        var next = sheet.withCombatState(hit.remaining(), sheet.conditions(), death);
        PlayerSheets.set(player, hit.enduranceUsed() ? next.spend(feature, 1) : next);
        player.setHealth(Math.max(1, hit.remaining().current()));
        if (hit.damageTaken() > 0) ModPackets.sendDamage(player, hit.damageTaken());
        return hit.damageTaken() > 0;
    }

    /** Empty delegates to the vanilla damage method. */
    public static Optional<Boolean> interceptDamage(LivingEntity target, DamageSource source, float amount) {
        if (!(target.level() instanceof ServerLevel level) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return Optional.empty();
        }
        Session session = session(target);
        Session attacker = source.getEntity() instanceof LivingEntity living ? session(living) : null;
        if (!Float.isFinite(amount) || amount <= 0) return Optional.of(false);
        if (session == null) {
            if (attacker != null) return Optional.of(false);
            if (target instanceof ServerPlayer player) {
                var sheet = PlayerSheets.get(player).orElse(null);
                if (CinematicFreeze.frozen(player)) return Optional.of(false);
                if (sheet != null && sheet.hitPoints().current() == 0) {
                    if (player.isInvulnerableTo(source) || player.getAbilities().invulnerable || player.hurtTime > 0) {
                        return Optional.of(false);
                    }
                    boolean applied = applyDowntimeDamage(player, sheet, damageType(source),
                            (int) Math.ceil(Math.min(amount, 10_000)));
                    if (applied) player.hurtTime = 10;
                    return Optional.of(applied);
                }
            }
            return Optional.empty();
        }
        if (source.getEntity() != null || CinematicFreeze.active(level)) return Optional.of(false);
        long gameTime = level.getGameTime();
        long last = session.lastHazard.getOrDefault(target.getUUID(), Long.MIN_VALUE / 2);
        if (gameTime - last < 10) return Optional.of(false);
        session.lastHazard.put(target.getUUID(), gameTime);
        return Optional.of(session.manager.damage(target.getUUID(), target.getUUID(),
                Map.of(damageType(source), (int) Math.ceil(amount)), false).damageTaken() > 0);
    }

    private static DamageType5e damageType(DamageSource source) {
        if (source.is(DamageTypeTags.IS_FIRE)) return DamageType5e.FIRE;
        if (source.is(DamageTypeTags.IS_FREEZING)) return DamageType5e.COLD;
        return switch (source.getMsgId()) {
            case "lightningBolt" -> DamageType5e.LIGHTNING;
            case "wither" -> DamageType5e.NECROTIC;
            case "magic", "indirectMagic", "dragonBreath" -> DamageType5e.FORCE;
            case "cactus", "stalagmite", "sweetBerryBush" -> DamageType5e.PIERCING;
            default -> DamageType5e.BLUDGEONING;
        };
    }

    static void dmHeal(ServerPlayer player) {
        Session session = session(player);
        if (session != null) {
            session.manager.healFully(player.getUUID());
            return;
        }
        var sheet = PlayerSheets.getOrCreate(player);
        if (sheet.deathSaves().state() == DeathSaveTracker.State.DEAD) return;
        var healed = sheet.hitPoints().heal(sheet.hitPoints().maximum());
        PlayerSheets.set(player, sheet.withCombatState(healed, sheet.conditions(), sheet.deathSaves().afterHealing(healed.current())));
        player.setHealth(healed.current());
    }

    static void dmInspire(ServerPlayer player) {
        Session session = session(player);
        if (session != null) session.manager.grantInspiration(player.getUUID());
        else {
            var sheet = PlayerSheets.getOrCreate(player);
            PlayerSheets.set(player, sheet.withResources(sheet.resources().withInspiration(true)));
        }
    }

    public static boolean drinkHealingDraught(LivingEntity drinker, int amount) {
        Session session = session(drinker);
        if (session != null) {
            try {
                session.manager.useHealingItem(drinker.getUUID(), amount, now());
                return true;
            } catch (IllegalStateException unavailable) {
                if (drinker instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.literal(unavailable.getMessage()), true);
                }
                return false;
            }
        }
        if (drinker instanceof ServerPlayer player && PlayerSheets.get(player).isPresent()) {
            var sheet = PlayerSheets.get(player).orElseThrow();
            if (sheet.deathSaves().state() == DeathSaveTracker.State.DEAD) return false;
            var healed = sheet.hitPoints().heal(amount);
            PlayerSheets.set(player, sheet.withCombatState(healed, sheet.conditions(), sheet.deathSaves().afterHealing(healed.current())));
        } else if (drinker instanceof DndNpcEntity npc) {
            if (npc.deathSaves().state() == DeathSaveTracker.State.DEAD) return false;
            var healed = npc.tacticalHealth().heal(amount);
            npc.syncCombatHealth(healed, npc.deathSaves().afterHealing(healed.current()));
            return true;
        }
        drinker.heal(amount);
        return true;
    }

    private static final class Session {
        private final ServerLevel level;
        private final UUID id = UUID.randomUUID();
        private final ArrayDeque<Cell> path = new ArrayDeque<>();
        private final Set<Detection> seen = new java.util.HashSet<>();
        private record Detection(UUID viewer, UUID target, int check) {}
        private UUID pathActor;
        private Cell pendingStep;
        private final Map<UUID, LivingEntity> entities = new LinkedHashMap<>();
        private final Map<UUID, Long> lastHazard = new LinkedHashMap<>();
        private final Map<UUID, Cell> lastCells = new LinkedHashMap<>();
        private final CombatEncounterState state;
        private final CombatManager manager;
        private long lastPrompt = -1;

        private Session(ServerLevel level, List<LivingEntity> entities) {
            this.level = level;
            entities.forEach(entity -> this.entities.put(entity.getUUID(), entity));
            state = new CombatEncounterState(entities.stream().map(this::entry).toList());
            manager = new CombatManager(state, new MinecraftBattlefield(level, this.entities), sides -> 1 + level.getRandom().nextInt(sides));
        }

        private CombatantEntry entry(LivingEntity entity) {
            Cell cell = cell(entity.blockPosition());
            if (entity instanceof DndNpcEntity npc) {
                var stats = npc.preset().combatStats();
                int initiative = initiative(npc, stats.abilityScores().get(Ability.DEXTERITY), RollMode.NORMAL);
                return new CombatantEntry(npc.getUUID(), npc.getDisplayName().getString(), 1, initiative, cell, stats,
                        npc.tacticalHealth(), npc.deathSaves(), npc.preset().defenses(), ExhaustionLevel.NONE, List.of(),
                        npc.bestiary());
            }
            ServerPlayer player = (ServerPlayer) entity;
            var sheet = PlayerSheets.getOrCreate(player);
            RollMode mode = RollMode.from(false, sheet.exhaustion().abilityCheckDisadvantage());
            int initiative = initiative(player, sheet.score(Ability.DEXTERITY), mode)
                    + (sheet.feats().contains(net.dnd5ecraft.rules.character.FeatType.ALERT) ? 5 : 0);
            Formula armor = armorFormula(player, sheet);
            var spells = SpellAccess.available(sheet);
            var attacks = new ArrayList<CombatantEntry.Attack>();
            var main = player.getMainHandItem();
            var mainId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(main.getItem());
            boolean requiresBothHands = WeaponStatsRegistry.findItem(mainId.getNamespace(), mainId.getPath())
                    .filter(stats -> stats.has(WeaponProperty.TWO_HANDED)).isPresent();
            if (requiresBothHands && !player.getOffhandItem().isEmpty()) main = net.minecraft.world.item.ItemStack.EMPTY;
            attacks.add(playerAttack(main, sheet, player.getOffhandItem().isEmpty()));
            if (player.getOffhandItem().getItem() instanceof net.minecraft.world.item.TieredItem
                    || player.getOffhandItem().is(Items.TRIDENT)) {
                attacks.add(playerAttack(player.getOffhandItem(), sheet, false));
            }
            var result = CombatantEntry.fromSheet(player.getUUID(), 0, initiative, cell, sheet, armorClass(player, sheet),
                    player.getArmorValue() > 0, spells, SpellAccess.rituals(sheet, spells), Set.of(), attacks, Defenses.NONE);
            boolean mediumMaster = sheet.feats().contains(net.dnd5ecraft.rules.character.FeatType.MEDIUM_ARMOR_MASTER)
                    && armor.category() == net.dnd5ecraft.rules.equipment.ArmorCategory.MEDIUM;
            result.setArmorRestrictions(armor.speedPenaltyFeet(sheet.score(Ability.STRENGTH),
                    sheet.race() == net.dnd5ecraft.rules.character.RaceType.DWARF), armor.stealthDisadvantage() && !mediumMaster);
            return result;
        }

        private void activate(Set<UUID> surprised) {
            for (LivingEntity entity : entities.values()) {
                entity.stopRiding();
                entity.setDeltaMovement(Vec3.ZERO);
                if (entity instanceof DndNpcEntity npc) npc.suspendAiForCombat();
            }
            manager.start(surprised);
            synchronize();
            publish();
        }

        private void tick() {
            entities.values().stream().filter(entity -> entity.level() != level || !entity.isAlive() || entity.isRemoved()
                    || entity instanceof ServerPlayer player && player.isSpectator()).toList().forEach(this::remove);
            if (manager.snapshot().current().isEmpty() || state.initiativeOrder().stream().map(CombatantEntry::team).distinct().count() < 2) {
                stop(level);
                return;
            }
            if (CinematicFreeze.active(level)) return;
            if (level.getGameTime() % 20 == 0) detectHidden();
            long time = now();
            manager.tick(time);
            var window = manager.reactionWindow();
            if (window.isPresent()) {
                for (var offer : window.get().offers()) {
                    if (entities.get(offer.reactor()) instanceof DndNpcEntity && offer.kind() == Kind.OPPORTUNITY_ATTACK) {
                        manager.answerReaction(window.get().id(), offer.reactor(),
                                Optional.of(new CombatManager.Reaction(offer.kind())), time);
                    }
                }
            } else if ((!path.isEmpty() || pendingStep != null) && level.getGameTime() % 2 == 0) {
                advancePath(time);
            } else if (level.getGameTime() % 5 == 0) {
                var current = state.require(manager.snapshot().current().orElseThrow());
                boolean survivingPlayer = state.initiativeOrder().stream().anyMatch(entry -> entry.team() == 0 && !entry.dead());
                boolean survivingNpc = state.initiativeOrder().stream().anyMatch(entry -> entry.team() == 1 && !entry.dead());
                if (!survivingPlayer || !survivingNpc) {
                    stop(level);
                    return;
                }
                if (!current.canAct()) manager.endTurn(current.id(), time);
                else if (entities.get(current.id()) instanceof DndNpcEntity npc
                        && level.players().stream().noneMatch(player -> DmMode.controls(player, npc))) act(npc, current, time);
            }
            synchronize();
            publish();
        }

        private void detectHidden() {
            var hidden = state.initiativeOrder().stream().filter(entry -> entry.stealthScore() >= 0).toList();
            seen.removeIf(detection -> hidden.stream().noneMatch(entry -> entry.id().equals(detection.target())
                    && entry.stealthScore() == detection.check()));
            for (var target : hidden) {
                for (var player : level.getEntitiesOfClass(ServerPlayer.class, new AABB(new BlockPos(
                        target.cell().x(), target.cell().y(), target.cell().z())).inflate(8),
                        viewer -> viewer.isAlive() && !viewer.isSpectator() && !viewer.getUUID().equals(target.id()))) {
                    if (PlayerSheets.get(player).isEmpty()
                            || PlayerRules.passive(player, net.dnd5ecraft.rules.ability.Skill.PERCEPTION) < target.stealthScore()) continue;
                    if (seen.add(new Detection(player.getUUID(), target.id(), target.stealthScore()))) {
                        ModPackets.glint(player, new BlockPos(target.cell().x(), target.cell().y(), target.cell().z()),
                                Component.translatable("dnd-5e-craft.combat.hidden_detected", target.name()));
                    }
                }
            }
        }

        private void remove(LivingEntity entity) {
            state.initiativeOrder().stream().filter(entry -> entry.id().equals(entity.getUUID())).findFirst()
                    .filter(entry -> entity.isAlive()).ifPresent(entry -> syncHealth(entry, entity));
            manager.removeCombatant(entity.getUUID());
            entities.remove(entity.getUUID());
            lastHazard.remove(entity.getUUID());
            lastCells.remove(entity.getUUID());
            if (entity instanceof DndNpcEntity npc) npc.restoreAiAfterCombat();
        }

        private void advancePath(long time) {
            if (pathActor == null || manager.reactionWindow().isPresent()) return;
            if (manager.snapshot().current().filter(pathActor::equals).isEmpty()) {
                path.clear();
                pendingStep = null;
                return;
            }
            if (pendingStep != null && !state.require(pathActor).cell().equals(pendingStep)) path.clear();
            pendingStep = null;
            if (path.isEmpty()) return;
            Cell next = path.remove();
            try {
                if (manager.move(pathActor, next, time).isPresent()) pendingStep = next;
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                path.clear();
            }
        }

        private Protocol.Combat networkView(UUID actorId) {
            var snapshot = manager.snapshot();
            var actor = state.require(actorId);
            var current = snapshot.current().orElseThrow();
            var view = actor.view();
            var initiative = state.initiativeOrder().stream().map(entry -> new Protocol.Creature(entry.id(),
                    ModPackets.trim(entry.name(), 80), entry.team(), entry.initiative(), entry.cell(), entry.hitPoints().current(),
                    entry.hitPoints().maximum(), entry.hitPoints().temporary(), entry.armorClass(), entry.stats().meleeReachFeet(),
                    entry.deathSaves().state(), entry.view().conditions())).toList();
            Map<Cell, Integer> reachable = new LinkedHashMap<>();
            Map<Cell, List<UUID>> threats = new LinkedHashMap<>();
            if (current.equals(actorId) && !CinematicFreeze.active(level) && manager.reactionWindow().isEmpty() && path.isEmpty()) {
                manager.reachable(actorId).entrySet().stream().limit(Protocol.MAX_CELLS).forEach(entry -> {
                    reachable.put(entry.getKey(), entry.getValue());
                    if (actor.cell().distanceFeet(entry.getKey()) == 5) {
                        var danger = manager.opportunityThreats(actorId, entry.getKey());
                        if (!danger.isEmpty()) threats.put(entry.getKey(), danger);
                    }
                });
            }
            var reaction = manager.reactionWindow().filter(window -> !window.answered().contains(actorId)).map(window ->
                    new Protocol.Reaction(window.id(), (int) Math.max(0, window.deadlineMillis() - now()),
                            window.offers().stream().filter(offer -> offer.reactor().equals(actorId))
                                    .map(offer -> offer.kind()).distinct().toList())).filter(r -> !r.choices().isEmpty()).orElse(null);
            var slots = new Protocol.Slots(java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(level -> actor.slotsAvailable(Pool.REGULAR, level)).toList(),
                    java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(level -> actor.slotsAvailable(Pool.PACT, level)).toList());
            var resources = current.equals(actorId) && manager.reactionWindow().isPresent()
                    ? Set.<Resource>of() : view.resources().available();
            return new Protocol.Combat(id, actorId, current, snapshot.round(), CinematicFreeze.active(level) || manager.reactionWindow().isPresent(), initiative, resources, view.resources().movementRemainingFeet(),
                    reachable, threats, actor.stats().attacks().stream().map(attack -> ModPackets.trim(attack.name(), 80)).toList(),
                    actor.stats().spells().keySet().stream().sorted().toList(), slots, reaction);
        }

        private void act(DndNpcEntity npc, CombatantEntry actor, long time) {
            if (npc.bestiary().canUseBreath() && actor.view().resources().available().contains(Resource.ACTION)) {
                var target = state.initiativeOrder().stream().filter(entry -> !entry.dead() && entry.team() != actor.team()
                        && actor.cell().distanceFeet(entry.cell()) <= npc.preset().breath().lengthFeet()
                        && manager.canSee(actor, entry)).findFirst();
                if (target.isPresent()) {
                    manager.useBreath(actor.id(), target.get().cell().center(), time);
                    return;
                }
            }
            MonsterAiDirector.execute(manager, actor.id(), time);
        }

        private void synchronize() {
            for (CombatantEntry entry : state.initiativeOrder()) {
                LivingEntity entity = entities.get(entry.id());
                if (entity.level() != level || entity.isRemoved() || !entity.isAlive()) continue;
                Cell cell = entry.cell();
                double x = cell.x() + .5;
                double z = cell.z() + .5;
                if (!cell.equals(lastCells.put(entry.id(), cell))
                        || entity.distanceToSqr(x, cell.y(), z) > .015625) {
                    if (entity instanceof ServerPlayer player) {
                        player.connection.teleport(x, cell.y(), z, player.getYRot(), player.getXRot());
                    } else entity.moveTo(x, cell.y(), z, entity.getYRot(), entity.getXRot());
                    entity.setDeltaMovement(Vec3.ZERO);
                }
                syncHealth(entry, entity);
            }
        }

        private static void syncHealth(CombatantEntry entry, LivingEntity entity) {
            if (entity instanceof DndNpcEntity npc) {
                npc.syncCombatHealth(entry.hitPoints(), entry.deathSaves());
            } else if (entity instanceof ServerPlayer player) {
                var sheet = PlayerSheets.getOrCreate(player);
                if (!sheet.hitPoints().equals(entry.hitPoints()) || !sheet.deathSaves().equals(entry.deathSaves())
                        || !sheet.conditions().equals(entry.activeConditions()) || !sheet.resources().equals(entry.characterResources())) {
                    PlayerSheets.set(player, sheet.withCombatState(entry.hitPoints(), entry.activeConditions(), entry.deathSaves())
                            .withResources(entry.characterResources()));
                }
                player.setHealth(Math.max(1, entry.hitPoints().current()));
            }
        }

        private void publish() {
            var events = manager.drainEvents();
            for (var event : events) {
                if (event.type() == CombatManager.EventType.DAMAGE && event.value() > 0
                        && entities.get(event.target()) instanceof ServerPlayer target) ModPackets.sendDamage(target, event.value());
                if (event.type() != CombatManager.EventType.TURN) continue;
                var actor = entities.get(event.actor());
                for (var entity : entities.values()) {
                    if (entity instanceof ServerPlayer player) {
                        player.displayClientMessage(Component.translatable("dnd-5e-craft.combat.turn", event.value(), actor == null ? Component.empty() : actor.getDisplayName()), true);
                    }
                }
            }
            manager.reactionWindow().filter(window -> window.id() != lastPrompt).ifPresent(window -> {
                lastPrompt = window.id();
                for (var offer : window.offers()) {
                    if (!(entities.get(offer.reactor()) instanceof ServerPlayer player) || ModPackets.supports(player)) continue;
                    String kind = offer.kind().name().toLowerCase(java.util.Locale.ROOT);
                    int slot = offer.kind() == Kind.SHIELD ? 1 : offer.kind() == Kind.COUNTERSPELL ? 3 : 0;
                    player.sendSystemMessage(Component.translatable("dnd-5e-craft.combat.react", kind).withStyle(style ->
                            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dnd react " + window.id() + " " + kind + " " + slot))));
                }
            });
        }

        private void restore() {
            for (CombatantEntry entry : state.initiativeOrder()) {
                var entity = entities.get(entry.id());
                if (entity.isRemoved()) continue;
                if (entity.isAlive()) syncHealth(entry, entity);
                if (entity instanceof DndNpcEntity npc) {
                    if (entry.dead()) npc.discard();
                    else npc.restoreAiAfterCombat();
                }
            }
        }
    }

    private static int initiative(LivingEntity entity, int dexterity, RollMode mode) {
        return CombatEncounterState.rollInitiative(dexterity, mode, sides -> 1 + entity.getRandom().nextInt(sides));
    }

    private static Formula armorFormula(ServerPlayer player, CharacterSheetData sheet) {
        if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem armor) {
            var material = armor.getMaterial();
            if (material == ArmorMaterials.LEATHER) return Formula.LEATHER;
            if (material == ArmorMaterials.CHAIN) return Formula.CHAIN_SHIRT;
            if (material == ArmorMaterials.GOLD) return Formula.BREASTPLATE;
            return material == ArmorMaterials.IRON ? Formula.CHAIN_MAIL : Formula.PLATE;
        }
        if (player.getArmorValue() > 0) return Formula.LEATHER;
        if (sheet.startingClass() == ClassType.BARBARIAN) return Formula.BARBARIAN;
        if (!player.getOffhandItem().is(Items.SHIELD) && (sheet.startingClass() == ClassType.MONK || (sheet.classLevels().containsKey(ClassType.MONK)
                && !sheet.classLevels().containsKey(ClassType.BARBARIAN)))) return Formula.MONK;
        return sheet.classLevels().containsKey(ClassType.BARBARIAN) ? Formula.BARBARIAN : Formula.UNARMORED;
    }

    public static int armorClass(ServerPlayer player, CharacterSheetData sheet) {
        return ArmorFormulaEngine.armorClass(armorFormula(player, sheet), sheet.score(Ability.DEXTERITY),
                sheet.score(Ability.CONSTITUTION), sheet.score(Ability.WISDOM), player.getOffhandItem().is(Items.SHIELD),
                sheet.feats().contains(net.dnd5ecraft.rules.character.FeatType.MEDIUM_ARMOR_MASTER));
    }

    private static CombatantEntry.Attack playerAttack(net.minecraft.world.item.ItemStack stack, CharacterSheetData sheet,
            boolean twoHanded) {
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        var weapon = WeaponStatsRegistry.findItem(id.getNamespace(), id.getPath()).filter(WeaponStatsRegistry.Stats::dealsDamage);
        int strength = sheet.score(Ability.STRENGTH);
        Ability ability = weapon.map(stats -> stats.preferredAttackAbility(strength, sheet.score(Ability.DEXTERITY))).orElse(Ability.STRENGTH);
        int modifier = Ability.modifier(sheet.score(ability));
        var dice = weapon.map(stats -> stats.damageDice(twoHanded)).orElseGet(() -> new Dice(0, 0));
        var type = weapon.flatMap(WeaponStatsRegistry.Stats::damageType).orElse(DamageType5e.BLUDGEONING);
        int flat = modifier + weapon.map(WeaponStatsRegistry.Stats::flatDamage).orElse(1);
        boolean ranged = weapon.filter(stats -> stats.has(WeaponProperty.RANGED)).isPresent();
        return new CombatantEntry.Attack(weapon.isPresent() ? stack.getHoverName().getString() : "Unarmed strike",
                modifier + net.dnd5ecraft.rules.ability.ProficiencyLevel.bonusAtLevel(sheet.level()),
                ranged ? 0 : weapon.map(WeaponStatsRegistry.Stats::meleeReachFeet).orElse(5),
                ranged ? weapon.orElseThrow().normalRangeFeet() : 0, ranged ? weapon.orElseThrow().longRangeFeet() : 0,
                List.of(new DamageRoll(type, dice, flat)), weapon.filter(stats -> stats.has(WeaponProperty.FINESSE)).isPresent(),
                weapon.filter(stats -> stats.has(WeaponProperty.LIGHT)).isPresent(),
                weapon.filter(stats -> stats.has(WeaponProperty.HEAVY)).isPresent(), ability == Ability.STRENGTH);
    }

    public static Cell cell(BlockPos position) { return new Cell(position.getX(), position.getY(), position.getZ()); }
}
