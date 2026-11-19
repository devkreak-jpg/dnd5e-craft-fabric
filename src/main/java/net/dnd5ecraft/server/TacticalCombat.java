package net.dnd5ecraft.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.CombatEncounterState;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.CombatantEntry;
import net.dnd5ecraft.combat.GridMovementTracker;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.MonsterAiDirector;
import net.dnd5ecraft.combat.PlayerWeaponProfiles;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.entity.CampaignCompanion;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.rules.campaign.CampaignRules;
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
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
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
            UUID player = handler.player.getUUID();
            ACTIVE.values().stream().filter(session -> session.level.getServer() == server && session.contains(player))
                    .toList().forEach(session -> session.remove(player));
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
        var session = controlledSession(player);
        if (CinematicFreeze.active(player.serverLevel()) || session != null && session.paused()) {
            throw new IllegalStateException("The scene is paused");
        }
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
        if (session == null || session.paused() || session.manager.reactionWindow().isPresent()) return null;
        var actor = control(player).actor();
        return session.manager.snapshot().current().filter(actor::equals)
                .map(current -> new TurnToken(session.id, current, session.state.round())).orElse(null);
    }

    public static Protocol.Combat networkView(ServerPlayer player) {
        Session session = controlledSession(player);
        if (session != null) return session.networkView(control(player).actor());
        var suspended = suspendedSession(player);
        if (suspended != null) return suspended.networkView(null);
        if (player.hasPermissions(2) && DmMode.active(player)) {
            var observed = ACTIVE.get(player.serverLevel());
            if (observed != null) return observed.networkView(null);
        }
        return null;
    }

    public static void playerChanged(ServerPlayer current, ServerLevel origin) {
        var leaving = ACTIVE.get(origin);
        UUID id = current.getUUID();
        if (leaving != null && leaving.entities.get(id) instanceof ServerPlayer enrolled) {
            if (origin != current.serverLevel()) leaving.suspendPlayer(current);
            else if (enrolled != current) leaving.replacePlayer(current);
        }
        var arriving = ACTIVE.get(current.serverLevel());
        if (arriving != null && arriving.suspendedPlayers.contains(id)) arriving.replacePlayer(current);
    }

    private static Session suspendedSession(ServerPlayer player) {
        return ACTIVE.values().stream().filter(session -> session.level.getServer() == player.getServer()
                && session.suspendedPlayers.contains(player.getUUID())).findFirst().orElse(null);
    }

    public static Map<UUID, String> networkConcentration(ServerPlayer player) {
        Session session = controlledSession(player);
        if (session == null) session = suspendedSession(player);
        if (session == null && player.hasPermissions(2) && DmMode.active(player)) session = ACTIVE.get(player.serverLevel());
        if (session == null) return Map.of();
        var active = new LinkedHashMap<UUID, String>();
        for (var entry : session.state.initiativeOrder()) {
            session.manager.concentration(entry.id()).ifPresent(spell -> active.put(entry.id(), spell.spellId()));
        }
        return Map.copyOf(active);
    }

    public static net.dnd5ecraft.rules.ability.TabletopRolls.Outcome rollDeathSave(ServerPlayer player,
            RollMode mode) {
        requireUnfrozen(player);
        var session = session(player);
        if (session == null) throw new IllegalStateException("No active encounter");
        var outcome = session.manager.resolveDeathSave(player.getUUID(), mode);
        session.synchronize();
        session.publish();
        return outcome;
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
        var npcs = level.getEntitiesOfClass(DndNpcEntity.class, area, npc -> npc.isAlive() && !npc.preset().civilian()
                && npc.deathSaves().state() != DeathSaveTracker.State.DEAD
                && players.stream().anyMatch(player -> npc.attitudeFor(player.getUUID()) == NpcAttitude.HOSTILE));
        start(level, players, npcs, Set.of());
    }

    private static boolean canEnroll(ServerPlayer player) {
        return suspendedSession(player) == null && player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
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
        var opponents = npcs.stream().filter(npc -> !npc.preset().civilian()).toList();
        if (players.isEmpty() || opponents.isEmpty()) throw new IllegalStateException("An encounter needs players and hostile NPCs");
        List<LivingEntity> entities = new ArrayList<>(players);
        entities.addAll(opponents);
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

    public static boolean allied(ServerPlayer player, LivingEntity target) {
        if (player == target) return true;
        var encounter = session(player);
        if (encounter != null && session(target) == encounter) return encounter.manager.allied(player.getUUID(), target.getUUID());
        if (target instanceof ServerPlayer) return true;
        return target instanceof DndNpcEntity npc && npc.attitudeFor(player.getUUID()) == NpcAttitude.FRIENDLY;
    }

    public static boolean campaignAttackHits(ServerPlayer player, LivingEntity target, int bonus) {
        var encounter = session(player);
        if (encounter != null && session(target) == encounter) return encounter.manager.campaignHit(player.getUUID(), target.getUUID(), bonus);
        int roll = PlayerRules.dice(player).applyAsInt(20);
        return roll == 20 || roll != 1 && roll + bonus >= (target instanceof DndNpcEntity npc ? npc.preset().armor() : 10 + target.getArmorValue());
    }

    public static boolean campaignSave(ServerPlayer source, LivingEntity target, Ability ability, int dc) {
        var encounter = session(target);
        if (encounter != null) return encounter.manager.campaignSave(target.getUUID(), ability, dc);
        if (target instanceof ServerPlayer player) return PlayerRules.save(player, ability, dc).succeeds();
        int modifier = target instanceof DndNpcEntity npc ? npc.preset().combatStats().saveModifier(ability) : 0;
        return PlayerRules.dice(source).applyAsInt(20) + modifier >= dc;
    }

    public static void campaignDamage(ServerPlayer player, LivingEntity target, DamageType5e type, int amount) {
        var encounter = session(target);
        if (encounter != null && session(player) == encounter) {
            encounter.manager.damage(player.getUUID(), target.getUUID(), Map.of(type, amount), false);
        } else if (encounter == null && !environmentDamage(target, type, amount)) {
            target.hurt(player.damageSources().magic(), amount);
        }
    }

    public static void campaignPush(ServerPlayer player, LivingEntity target, int blocks, Vec3 origin) {
        var delta = target.position().subtract(origin);
        int dx = (int) Math.signum(delta.x), dz = (int) Math.signum(delta.z);
        var encounter = session(target);
        if (encounter != null && session(player) == encounter) {
            for (int i = 0; i < blocks; i++) {
                var from = encounter.state.require(target.getUUID()).cell();
                var to = new Cell(from.x() + dx, from.y(), from.z() + dz);
                if (!encounter.manager.relocate(target.getUUID(), to, net.dnd5ecraft.combat.OpportunityAttackDetector.Movement.FORCED, now())) break;
            }
        } else if (encounter == null) {
            target.knockback(blocks * .48, -delta.x, -delta.z);
        }
    }

    public static void campaignTempHp(LivingEntity target, int amount) {
        var encounter = session(target);
        if (encounter != null) {
            if (!encounter.paused()) encounter.manager.campaignTempHp(target.getUUID(), amount);
        } else if (target instanceof ServerPlayer player) {
            PlayerSheets.get(player).ifPresent(sheet -> PlayerSheets.set(player, sheet.withCombatState(
                    sheet.hitPoints().grantTemporary(amount), sheet.conditions(), sheet.deathSaves())));
        }
    }

    public static void swapEcho(ServerPlayer player, BlockPos echo) {
        var encounter = session(player);
        if (encounter == null) throw new IllegalStateException("No tactical encounter");
        encounter.manager.campaignSwap(player.getUUID(), cell(echo), CampaignRules.echoSwapCostFeet(), now());
        encounter.synchronize();
    }

    public static void orderNpc(ServerPlayer dm, DndNpcEntity npc, UUID target, boolean end) {
        var encounter = session(npc);
        if (!dm.hasPermissions(2) || encounter == null || encounter.paused() || dm.level() != npc.level()
                || npc.behavior() != net.dnd5ecraft.entity.NpcBehaviorMode.DM_COMMANDED) {
            throw new IllegalStateException("A DM-commanded tactical NPC is required");
        }
        if (end) encounter.manager.endTurn(npc.getUUID(), now());
        else if (target != null) encounter.manager.attack(npc.getUUID(), target, 0, now());
        else throw new IllegalArgumentException("Choose an attack target");
    }

    public static void directScenePair(ServerPlayer dm, UUID target, BlockPos second) {
        var encounter = ACTIVE.get(dm.serverLevel());
        if (!dm.hasPermissions(2) || encounter == null || encounter.paused() || second == null || target == null) {
            throw new IllegalStateException("The director needs an active Scene Change");
        }
        var caster = encounter.manager.directorScenePair(target, cell(second), now());
        if (encounter.entities.get(caster) instanceof ServerPlayer player) {
            ModPackets.notice(player, Component.translatable("dnd-5e-craft.ui.campaign.scene_directed"));
        }
    }

    public static void directNpc(ServerPlayer dm, DndNpcEntity npc, BlockPos pos) {
        if (!dm.hasPermissions(2) || dm.level() != npc.level() || !dm.serverLevel().isLoaded(pos)
                || npc.blockPosition().distSqr(pos) > 4096) throw new IllegalArgumentException("NPC destination is unavailable");
        var encounter = session(npc);
        if (encounter == null) {
            if (npc.behavior() == net.dnd5ecraft.entity.NpcBehaviorMode.STATIONARY) return;
            npc.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 1);
            return;
        }
        if (encounter.paused() || encounter.manager.reactionWindow().isPresent()
                || !encounter.manager.snapshot().current().filter(npc.getUUID()::equals).isPresent()) {
            throw new IllegalStateException("Wait for the NPC's turn");
        }
        var goal = cell(pos);
        var manager = encounter.manager;
        var options = manager.reachable(npc.getUUID());
        var nearest = options.keySet().stream().min(java.util.Comparator.comparingInt(goal::distanceFeet)).orElse(goal);
        var path = manager.path(npc.getUUID(), nearest).orElseThrow(() -> new IllegalStateException("No reachable destination"));
        encounter.path.clear();
        encounter.pendingStep = null;
        encounter.pathActor = npc.getUUID();
        encounter.path.addAll(path.cells().subList(1, path.cells().size()));
        encounter.advancePath(now());
    }

    public static boolean environmentDamage(LivingEntity target, DamageType5e damage, int amount) {
        Session session = session(target);
        if (session != null && session.paused()) return false;
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
        if (target instanceof CampaignCompanion) return Optional.empty();
        Session session = session(target);
        Session attacker = source.getEntity() instanceof LivingEntity living ? session(living) : null;
        if (!Float.isFinite(amount) || amount <= 0) return Optional.of(false);
        if (session == null) {
            if (attacker != null) return Optional.of(false);
            if (target instanceof ServerPlayer player) {
                var sheet = PlayerSheets.get(player).orElse(null);
                if (CinematicFreeze.frozen(player)) return Optional.of(false);
                if (sheet != null) {
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
        if (source.getEntity() != null || session.paused()) return Optional.of(false);
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

    public static boolean interceptVanillaHealing(LivingEntity entity, float amount) {
        if (!(entity instanceof ServerPlayer player)) return false;
        var sheet = PlayerSheets.get(player).orElse(null);
        if (sheet == null) return false;
        if (!Float.isFinite(amount) || amount <= 0 || sheet.deathSaves().state() == DeathSaveTracker.State.DEAD) return true;
        int restored = (int) Math.ceil(amount);
        var session = session(player);
        if (session != null) {
            if (!session.paused()) session.manager.restoreFromVanillaMagic(player.getUUID(), restored);
        } else {
            var health = sheet.hitPoints().heal(restored);
            PlayerSheets.set(player, sheet.withCombatState(health, sheet.conditions(), sheet.deathSaves().afterHealing(health.current())));
            player.setHealth(Math.min(player.getMaxHealth(), health.current()));
        }
        return true;
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
            interceptVanillaHealing(player, amount);
            return true;
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
        private UUID lastTurn;
        private UUID deathSaveActor;
        private long deathSaveUntil;
        private final Map<UUID, LivingEntity> entities = new LinkedHashMap<>();
        private final Set<UUID> suspendedPlayers = new java.util.HashSet<>();
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
                if (!npc.preset().civilian() && PlayerWeaponProfiles.stats(npc.getMainHandItem()).isPresent()) {
                    var profile = PlayerWeaponProfiles.npc(npc.getMainHandItem(), stats);
                    stats = new CombatantEntry.Stats(stats.speedFeet(), stats.armorClass(), stats.armored(), stats.darkvisionFeet(),
                            stats.level(), stats.abilityScores(), stats.savingThrows(), stats.classes(), stats.spells(),
                            stats.rituals(), stats.traits(), List.of(profile));
                }
                int initiative = initiative(npc, stats.abilityScores().get(Ability.DEXTERITY), RollMode.NORMAL);
                return new CombatantEntry(npc.getUUID(), npc.getDisplayName().getString(), 1, initiative, cell, stats,
                        npc.tacticalHealth(), npc.deathSaves(), npc.preset().defenses(), ExhaustionLevel.NONE, List.of(),
                        npc.bestiary());
            }
            ServerPlayer player = (ServerPlayer) entity;
            var sheet = PlayerSheets.getOrCreate(player);
            RollMode mode = RollMode.from(player.getOffhandItem().is(ModItems.SENTINEL_SHIELD), sheet.exhaustion().abilityCheckDisadvantage());
            int initiative = initiative(player, sheet.score(Ability.DEXTERITY), mode)
                    + (sheet.feats().contains(net.dnd5ecraft.rules.character.FeatType.ALERT) ? 5 : 0);
            Formula armor = armorFormula(player, sheet);
            var spells = SpellAccess.available(sheet);
            var attacks = new ArrayList<CombatantEntry.Attack>();
            var main = player.getMainHandItem();
            boolean requiresBothHands = PlayerWeaponProfiles.stats(main)
                    .filter(stats -> stats.has(WeaponProperty.TWO_HANDED)).isPresent();
            if (requiresBothHands && !player.getOffhandItem().isEmpty()) main = net.minecraft.world.item.ItemStack.EMPTY;
            attacks.add(PlayerWeaponProfiles.attack(main, sheet, player.getOffhandItem().isEmpty()));
            if (PlayerWeaponProfiles.available(player, net.dnd5ecraft.rules.ability.TabletopRolls.Weapon.OFF_HAND)) {
                attacks.add(PlayerWeaponProfiles.attack(player.getOffhandItem(), sheet, false));
            }
            PlayerWeaponProfiles.thrown(main, sheet).ifPresent(attacks::add);
            if (PlayerWeaponProfiles.available(player, net.dnd5ecraft.rules.ability.TabletopRolls.Weapon.OFF_HAND)) {
                PlayerWeaponProfiles.thrown(player.getOffhandItem(), sheet).ifPresent(attacks::add);
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
            for (var entity : List.copyOf(entities.values())) {
                if (entity instanceof ServerPlayer player && !player.isAlive() && player.connection != null) {
                    suspendPlayer(player);
                } else if (entity.level() != level || !entity.isAlive() || entity.isRemoved()
                        || entity instanceof ServerPlayer player && player.isSpectator()) {
                    remove(entity.getUUID());
                }
            }
            if (manager.snapshot().current().isEmpty() || state.initiativeOrder().stream().map(CombatantEntry::team).distinct().count() < 2) {
                stop(level);
                return;
            }
            if (paused()) return;
            if (level.getGameTime() % 20 == 0) detectHidden();
            long time = now();
            manager.tick(time);
            UUID waiting = manager.pendingDeathSave().orElse(null);
            if (!java.util.Objects.equals(waiting, deathSaveActor)) {
                deathSaveActor = waiting;
                deathSaveUntil = time + 10_000;
            }
            if (waiting != null && time < deathSaveUntil) {
                synchronize();
                publish();
                return;
            }
            if (waiting != null) manager.resolveDeathSave(waiting, RollMode.NORMAL);
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
                        && npc.behavior() != net.dnd5ecraft.entity.NpcBehaviorMode.DM_COMMANDED
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

        private boolean contains(UUID id) { return entities.containsKey(id) || suspendedPlayers.contains(id); }

        private boolean paused() { return !suspendedPlayers.isEmpty() || CinematicFreeze.active(level); }

        private void suspendPlayer(ServerPlayer player) {
            UUID id = player.getUUID();
            if (player.isAlive()) syncHealth(state.require(id), player);
            suspendedPlayers.add(id);
            entities.remove(id);
            lastCells.remove(id);
            if (id.equals(pathActor)) {
                path.clear();
                pendingStep = null;
                pathActor = null;
            }
        }

        private void replacePlayer(ServerPlayer player) {
            UUID id = player.getUUID();
            entities.put(id, player);
            suspendedPlayers.remove(id);
            lastCells.remove(id);
            syncHealth(state.require(id), player);
            if (!paused()) synchronize();
        }

        private void remove(UUID id) {
            var entity = entities.get(id);
            if (entity != null && entity.isAlive()) syncHealth(state.require(id), entity);
            manager.removeCombatant(id);
            entities.remove(id);
            suspendedPlayers.remove(id);
            lastHazard.remove(id);
            lastCells.remove(id);
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
            var current = snapshot.current().orElse(null);
            var initiative = state.initiativeOrder().stream().map(entry -> new Protocol.Creature(entry.id(),
                    ModPackets.trim(entry.name(), 80), entry.team(), entry.initiative(), entry.cell(), entry.hitPoints().current(),
                    entry.hitPoints().maximum(), entry.hitPoints().temporary(), entry.armorClass(), entry.stats().meleeReachFeet(),
                    entry.deathSaves().state(), entry.view().conditions())).toList();
            var actor = actorId == null ? null : state.initiativeOrder().stream().filter(entry -> entry.id().equals(actorId)).findFirst().orElse(null);
            if (actor == null) {
                return new Protocol.Combat(id, null, current, snapshot.round(), true, initiative, Set.of(), 0,
                        Map.of(), Map.of(), List.of(), List.of(), Protocol.Slots.EMPTY, null);
            }
            var view = actor.view();
            Map<Cell, Integer> reachable = new LinkedHashMap<>();
            Map<Cell, List<UUID>> threats = new LinkedHashMap<>();
            if (actorId.equals(current) && !paused() && manager.reactionWindow().isEmpty() && path.isEmpty()) {
                manager.reachable(actorId).entrySet().stream().limit(Protocol.MAX_CELLS).forEach(entry -> {
                    reachable.put(entry.getKey(), entry.getValue());
                    if (actor.cell().distanceFeet(entry.getKey()) == 5) {
                        var danger = manager.opportunityThreats(actorId, entry.getKey());
                        if (!danger.isEmpty()) threats.put(entry.getKey(), danger);
                    }
                });
            }
            var reaction = manager.reactionWindow().filter(window -> !paused() && !window.answered().contains(actorId)).map(window ->
                    new Protocol.Reaction(window.id(), (int) Math.max(0, window.deadlineMillis() - now()),
                            window.offers().stream().filter(offer -> offer.reactor().equals(actorId))
                                    .map(offer -> offer.kind()).distinct().toList(), window.totalDurationMs())).filter(r -> !r.choices().isEmpty()).orElse(null);
            var slots = new Protocol.Slots(java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(level -> actor.slotsAvailable(Pool.REGULAR, level)).toList(),
                    java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(level -> actor.slotsAvailable(Pool.PACT, level)).toList());
            var resources = paused() || actorId.equals(current) && manager.reactionWindow().isPresent()
                    ? Set.<Resource>of() : view.resources().available();
            return new Protocol.Combat(id, actorId, current, snapshot.round(), paused() || manager.reactionWindow().isPresent(), initiative, resources, view.resources().movementRemainingFeet(),
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
                if (entity == null || entity.level() != level || entity.isRemoved() || !entity.isAlive()
                        || entity instanceof ServerPlayer player && player.connection == null) continue;
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
                ((PlayerCharacter) player).dnd5e$campaignFlags(entry.campaignFlags());
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
                if (event.type() == CombatManager.EventType.CAMPAIGN && entities.get(event.target()) != null) {
                    var point = entities.get(event.target()).position();
                    if (event.detail().equals("earth")) {
                        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                                net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState()), point.x, point.y + .15, point.z, 32, .55, .1, .55, .07);
                    } else {
                        if (event.detail().equals("final_act")) level.playSound(null, BlockPos.containing(point),
                                net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, .62f);
                        level.sendParticles(net.minecraft.core.particles.ParticleTypes.WITCH, point.x, point.y + .7, point.z, 20, .35, .4, .35, .06);
                    }
                }
                if (event.type() != CombatManager.EventType.TURN) continue;
                if (lastTurn != null && entities.get(lastTurn) instanceof ServerPlayer previous) {
                    var echo = CampaignActions.companion(previous, EchoAvatarEntity.class);
                    if (echo != null && !CampaignRules.echoInRange(previous.distanceTo(echo))) echo.discard();
                }
                lastTurn = event.actor();
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
                    int slot = (offer.kind() == Kind.SHIELD || offer.kind() == Kind.ABSORB_ELEMENTS) ? 1 : offer.kind() == Kind.COUNTERSPELL ? 3 : 0;
                    player.sendSystemMessage(Component.translatable("dnd-5e-craft.combat.react", kind).withStyle(style ->
                            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dnd react " + window.id() + " " + kind + " " + slot))));
                }
            });
        }

        private void restore() {
            for (CombatantEntry entry : state.initiativeOrder()) {
                var entity = entities.get(entry.id());
                if (entity == null || entity.isRemoved()) continue;
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

    static Formula armorFormula(net.minecraft.world.entity.player.Player player, CharacterSheetData sheet) {
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(ModItems.SCALE_MAIL)) return Formula.SCALE_MAIL;
        if (chest.is(ModItems.SPLINT_ARMOR)) return Formula.SPLINT;
        if (chest.getItem() instanceof ArmorItem armor) {
            var material = armor.getMaterial();
            if (material == ArmorMaterials.LEATHER) return Formula.LEATHER;
            if (material == ArmorMaterials.CHAIN) return Formula.CHAIN_SHIRT;
            if (material == ArmorMaterials.GOLD) return Formula.BREASTPLATE;
            return material == ArmorMaterials.IRON ? Formula.CHAIN_MAIL : Formula.PLATE;
        }
        if (player.getArmorValue() > 0) return Formula.LEATHER;
        if (sheet.startingClass() == ClassType.BARBARIAN) return Formula.BARBARIAN;
        if (!PlayerWeaponProfiles.shield(player.getOffhandItem()) && (sheet.startingClass() == ClassType.MONK || (sheet.classLevels().containsKey(ClassType.MONK)
                && !sheet.classLevels().containsKey(ClassType.BARBARIAN)))) return Formula.MONK;
        return sheet.classLevels().containsKey(ClassType.BARBARIAN) ? Formula.BARBARIAN : Formula.UNARMORED;
    }

    public static int armorClass(net.minecraft.world.entity.player.Player player, CharacterSheetData sheet) {
        return ArmorFormulaEngine.armorClass(armorFormula(player, sheet), sheet.score(Ability.DEXTERITY),
                sheet.score(Ability.CONSTITUTION), sheet.score(Ability.WISDOM), PlayerWeaponProfiles.shield(player.getOffhandItem()),
                sheet.feats().contains(net.dnd5ecraft.rules.character.FeatType.MEDIUM_ARMOR_MASTER))
                + (player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.SCALE_MAIL)
                    && sheet.subclasses().containsValue(net.dnd5ecraft.rules.character.SubclassType.ARTILLERIST) ? 1 : 0);
    }

    public static Cell cell(BlockPos position) { return new Cell(position.getX(), position.getY(), position.getZ()); }
}
