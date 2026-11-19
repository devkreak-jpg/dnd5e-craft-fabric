package net.dnd5ecraft.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.dialogue.DialogueChoice;
import net.dnd5ecraft.dialogue.DialogueNode;
import net.dnd5ecraft.dialogue.DialogueTree;
import net.dnd5ecraft.dialogue.DialogueText;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.NpcSpawns;
import net.dnd5ecraft.entity.TriggerZoneBlockEntity;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.item.PhysicalD20Item;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.*;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.PointBuyCalculator;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.SpellAccess;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.dnd5ecraft.server.DialogueSessions;
import net.dnd5ecraft.server.DmMode;
import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.server.TacticalCombat;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import static net.dnd5ecraft.network.PacketCodec.*;

public final class ModPackets {
    public static final ResourceLocation REQUEST = Dnd5eCraftMod.id("request");
    public static final ResourceLocation UPDATE = Dnd5eCraftMod.id("update");
    public static final ResourceLocation TIMED_UPDATE = Dnd5eCraftMod.id("timed_update");
    public static final ResourceLocation TABLETOP = Dnd5eCraftMod.id("tabletop");
    public static final ResourceLocation STUDIO = Dnd5eCraftMod.id("studio_fx");
    private static final Map<ServerGamePacketListenerImpl, PacketPolicy.RateLimit> LIMITS = new ConcurrentHashMap<>();
    private static final PlayerSessionStore<Session> SESSIONS = new PlayerSessionStore<>();

    private static final class Session {
        State state;
        CharacterSheetData sheet;
        int sheetAc;
        boolean sentSheet;
        List<Integer> rolls = List.of();
        Ritual ritual;
        Long pausedRitualTicks;
        boolean timed;
        final net.dnd5ecraft.rules.campaign.TacticalFeedback.PingLimiter pings = new net.dnd5ecraft.rules.campaign.TacticalFeedback.PingLimiter();
        final java.util.ArrayDeque<Component> journal = new java.util.ArrayDeque<>();

        void invalidateSnapshot() {
            state = null;
            sentSheet = false;
        }
    }

    private record Ritual(String spell, ResourceLocation dimension, net.minecraft.world.phys.Vec3 origin,
                          float health, long ends) {}

    private ModPackets() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST, (server, player, handler, buf, sender) -> {
            if (buf.readableBytes() > Protocol.MAX_REQUEST) return;
            if (!LIMITS.computeIfAbsent(handler, ignored -> new PacketPolicy.RateLimit()).take(System.nanoTime())) return;
            try {
                if (buf.readVarInt() != Protocol.VERSION) return;
                Action action = readEnum(buf, Action.class);
                var token = action.turn() ? turn(buf) : null;
                Consumer<ServerPlayer> execute = decode(action, buf);
                if (buf.isReadable()) return;
                server.execute(() -> {
                    if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection != handler
                            || !player.isAlive()) return;
                    try {
                        PacketPolicy.require(action, player.hasPermissions(2), token,
                                action.turn() ? TacticalCombat.turnToken(player) : null);
                        if (action == Action.INSPECT_ATTACK) {
                            execute.accept(player);
                            return;
                        }
                        if (action.turn() && action != Action.MOVE) TacticalCombat.cancelPath(player);
                        execute.accept(player);
                        sync(player);
                    } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException rejected) {
                        notice(player, Component.literal(java.util.Objects.toString(rejected.getMessage(), "Action unavailable")));
                        sync(player);
                    }
                });
            } catch (RuntimeException malformed) {
                // The complete request must decode before any work is queued.
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LIMITS.remove(handler);
            SESSIONS.remove(handler.player.getUUID());
            TabletopPackets.disconnect(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SESSIONS.clear();
            TabletopPackets.clear();
            LIMITS.clear();
        });
        ServerPlayerEvents.COPY_FROM.register((previous, current, alive) -> session(current).invalidateSnapshot());
        ServerPlayerEvents.AFTER_RESPAWN.register((previous, current, alive) -> {
            var session = session(current);
            if (!alive) pauseRitual(session, previous.serverLevel());
            playerChangedWorld(current, previous.serverLevel(), session);
            sync(current);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, previous, destination) -> {
            playerChangedWorld(player, previous, session(player));
            sync(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 5 != 0) return;
            var players = server.getPlayerList().getPlayers();
            for (ServerPlayer player : players) {
                if (supports(player)) {
                    tickRitual(player);
                    TabletopPackets.tick(player);
                    sync(player);
                }
            }
        });
    }

    private static void checkCampaignTurn(ServerPlayer player, PacketPolicy.TurnToken token) {
        if (TacticalCombat.isLocked(player)) {
            var current = TacticalCombat.turnToken(player);
            if (current == null || !current.equals(token)) throw new IllegalStateException("Wait for your turn");
            TacticalCombat.cancelPath(player);
        } else if (token != null) throw new IllegalStateException("The encounter has ended");
    }

    private static Consumer<ServerPlayer> decode(Action action, FriendlyByteBuf buf) {
        return switch (action) {
            case SYNC -> player -> {
                session(player).invalidateSnapshot();
                TabletopPackets.resync(player);
            };
            case TABLETOP_ROLL -> {
                var check = TabletopCodec.check(buf);
                var options = TabletopCodec.options(buf);
                boolean power = buf.readBoolean();
                yield player -> TabletopPackets.roll(player, check, options, power);
            }
            case DM_ROLL_REQUEST -> {
                UUID target = buf.readBoolean() ? buf.readUUID() : null;
                var check = TabletopCodec.check(buf);
                var mode = readEnum(buf, net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode.class);
                int dc = buf.readVarInt();
                boolean secret = buf.readBoolean();
                yield player -> TabletopPackets.request(player, target, check, mode, dc, secret);
            }
            case DM_ROLL_ANSWER -> {
                UUID prompt = buf.readUUID();
                yield player -> TabletopPackets.answer(player, prompt);
            }
            case TABLETOP_CHOOSE -> {
                UUID token = buf.readUUID();
                String id = buf.readUtf(64);
                var options = TabletopCodec.options(buf);
                yield player -> {
                    TabletopPackets.require(player);
                    if (!DialogueSessions.choose(player, token, id, options)) throw new IllegalStateException("Dialogue choice expired");
                };
            }
            case TABLETOP_REST -> TabletopPackets::startRest;
            case CAMPAIGN_PRESET -> {
                var hero = readEnum(buf, net.dnd5ecraft.rules.campaign.CampaignHero.class);
                var name = buf.readUtf(32);
                var race = readEnum(buf, RaceType.class);
                var background = readEnum(buf, BackgroundType.class);
                var scores = readList(buf, 6, FriendlyByteBuf::readVarInt);
                int hp = PacketPolicy.count(buf.readVarInt(), 100);
                var traits = new CharacterSheetData.Personality(buf.readUtf(80), buf.readUtf(80), buf.readUtf(80), buf.readUtf(256));
                yield player -> net.dnd5ecraft.server.CampaignCharacters.install(player, hero, hero.create(name, race, background, scores, hp, traits));
            }
            case CAMPAIGN_ACTION -> {
                var campaignAction = readEnum(buf, net.dnd5ecraft.server.CampaignActions.Action.class);
                UUID target = buf.readBoolean() ? buf.readUUID() : null;
                var point = buf.readBoolean() ? block(cell(buf)) : null;
                int option = buf.readInt();
                var token = buf.readBoolean() ? turn(buf) : null;
                if (option < -100 || option > 100) throw new IllegalArgumentException("Invalid campaign action");
                yield player -> {
                    if (net.dnd5ecraft.server.CampaignActions.turnBound(campaignAction)) checkCampaignTurn(player, token);
                    net.dnd5ecraft.server.CampaignActions.perform(player, campaignAction, target, point, option);
                };
            }
            case CAMPAIGN_COSMETICS -> player -> {
                var traits = (net.dnd5ecraft.server.PlayerCharacter) player;
                traits.dnd5e$setCosmetics(!traits.dnd5e$showsCosmetics());
            };
            case DIRECTOR -> {
                var tool = readEnum(buf, net.dnd5ecraft.server.DmDirector.Tool.class);
                UUID target = buf.readBoolean() ? buf.readUUID() : null;
                int value = PacketPolicy.count(buf.readVarInt(), 1000);
                yield player -> net.dnd5ecraft.server.DmDirector.run(player, tool, target, value);
            }
            case CAMPAIGN_SPELL -> {
                var id = buf.readUtf(64);
                UUID target = buf.readBoolean() ? buf.readUUID() : null;
                var spellPoint = buf.readBoolean() ? block(cell(buf)) : null;
                String phrase = buf.readUtf(160);
                var token = buf.readBoolean() ? turn(buf) : null;
                yield player -> {
                    checkCampaignTurn(player, token);
                    net.dnd5ecraft.server.CampaignMagic.cast(player, id, target, spellPoint, phrase);
                };
            }
            case CAMPAIGN_POSE -> {
                boolean kneel = buf.readBoolean();
                yield player -> net.dnd5ecraft.server.CampaignPoses.toggle(player, kneel);
            }
            case ROLL -> PhysicalD20Item::roll;
            case REQUEST_ROLL -> {
                boolean save = buf.readBoolean();
                String id = buf.readUtf(32);
                yield player -> net.dnd5ecraft.server.PlayerRules.requestRoll(player, save, id, 0);
            }
            case REST -> {
                boolean longRest = buf.readBoolean();
                yield player -> net.dnd5ecraft.server.PlayerRules.rest(player, longRest);
            }
            case PARTY -> ModPackets::sendParty;
            case JOURNAL -> ModPackets::sendJournal;
            case JOURNAL_NOTES -> {
                var notes = buf.readUtf(Protocol.JOURNAL_NOTES_LIMIT);
                if (notes.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid journal notes");
                yield player -> {
                    if (PlayerSheets.get(player).isEmpty()) throw new IllegalStateException("A character is required");
                    ((net.dnd5ecraft.server.PlayerCharacter) player).dnd5e$journalNotes(notes);
                    sendJournal(player);
                };
            }
            case SPLIT_GOLD -> {
                int gold = PacketPolicy.count(buf.readVarInt(), 10_000);
                yield player -> net.dnd5ecraft.server.DmDirector.splitGold(player, gold);
            }
            case MANEUVER -> {
                var maneuver = readEnum(buf, CombatManager.Maneuver.class);
                var target = buf.readBoolean() ? buf.readUUID() : null;
                int option = PacketPolicy.count(buf.readVarInt(), 100);
                Cell aim = buf.readBoolean() ? cell(buf) : null;
                yield player -> {
                    var control = TacticalCombat.control(player);
                    control.manager().maneuver(control.actor(), maneuver, target, option, aim, TacticalCombat.now());
                };
            }
            case C2S_TACTICAL_PING -> {
                var point = new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                int entity = buf.readVarInt();
                yield player -> {
                    if (session(player).pings.take(System.nanoTime())) StudioEffects.ping(player, point, entity);
                };
            }
            case INSPECT_ATTACK -> {
                UUID target = buf.readUUID();
                int index = PacketPolicy.count(buf.readVarInt(), 63);
                var point = new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                yield player -> {
                    var control = TacticalCombat.control(player);
                    if (!control.actor().equals(player.getUUID())
                            || !(player.serverLevel().getEntity(target) instanceof net.minecraft.world.entity.LivingEntity living)
                            || StudioEffects.verify(player, point, living.getId(), 32) == null) return;
                    var preview = TacticalCombat.inspect(player, living, index);
                    if (preview == null) return;
                    send(player, STUDIO, Update.S2C_INSPECT, out -> {
                        out.writeUUID(target);
                        out.writeVarInt(index);
                        out.writeEnum(preview.cover());
                        out.writeEnum(preview.mode());
                        out.writeInt(preview.bonus());
                        out.writeVarInt(preview.distance());
                        out.writeBoolean(preview.inRange());
                        out.writeVarInt(preview.armor()); out.writeVarInt(preview.hp()); out.writeVarInt(preview.maxHp());
                    });
                };
            }
            case PING -> {
                Cell position = cell(buf);
                yield player -> ping(player, position);
            }
            case DRAFT_ROLL -> ModPackets::draftRoll;
            case CREATE -> {
                CharacterDraft draft = readDraft(buf);
                yield player -> create(player, draft);
            }
            case TALK -> {
                UUID npc = buf.readUUID();
                yield player -> {
                    if (!DialogueSessions.open(player, npc(player, npc, 6))) throw new IllegalStateException("Dialogue unavailable");
                };
            }
            case CHOOSE -> {
                UUID token = buf.readUUID();
                String id = buf.readUtf(64);
                yield player -> {
                    if (!DialogueSessions.choose(player, token, id)) throw new IllegalStateException("Dialogue choice expired");
                };
            }
            case CLOSE_DIALOGUE -> {
                UUID token = buf.readUUID();
                yield player -> DialogueSessions.close(player, token);
            }
            case MOVE -> {
                Cell destination = cell(buf);
                yield player -> TacticalCombat.movePath(player, destination);
            }
            case ATTACK -> {
                UUID target = buf.readUUID();
                int index = PacketPolicy.count(buf.readVarInt(), 15);
                yield player -> {
                    var control = TacticalCombat.control(player);
                    control.manager().attack(control.actor(), target, index, TacticalCombat.now());
                };
            }
            case END_TURN, DASH, DISENGAGE, STAND, SURGE -> player -> turnAction(player, action);
            case INTERACT -> {
                Cell position = cell(buf);
                yield player -> interact(player, position);
            }
            case CAST -> {
                var request = new CombatManager.CastRequest(buf.readUtf(64), PacketPolicy.count(buf.readVarInt(), 9),
                        readEnum(buf, Pool.class), readList(buf, Protocol.MAX_SPELL_TARGETS, FriendlyByteBuf::readUUID),
                        buf.readBoolean() ? Optional.of(point(buf)) : Optional.empty(),
                        buf.readBoolean() ? Optional.of(buf.readUtf(32)) : Optional.empty());
                yield player -> {
                    var control = TacticalCombat.control(player);
                    control.manager().cast(control.actor(), request, TacticalCombat.now());
                };
            }
            case RITUAL_START -> {
                String spell = buf.readUtf(64);
                yield player -> beginRitual(player, spell);
            }
            case REACT -> {
                UUID encounter = buf.readUUID();
                long window = buf.readVarLong();
                var response = buf.readBoolean() ? Optional.of(new CombatManager.Reaction(readEnum(buf, Kind.class),
                        PacketPolicy.count(buf.readVarInt(), 9), readEnum(buf, Pool.class))) : Optional.<CombatManager.Reaction>empty();
                yield player -> react(player, encounter, window, response);
            }
            case DM_MODE -> player -> DmMode.toggle(player);
            case START_COMBAT -> TacticalCombat::startNearby;
            case STOP_COMBAT -> player -> {
                if (!TacticalCombat.stop(player.serverLevel())) throw new IllegalStateException("No active encounter");
            };
            case POSSESS -> {
                UUID id = buf.readUUID();
                yield player -> DmMode.viewThrough(player, npc(player, id, 32));
            }
            case RELEASE -> player -> {
                if (!DmMode.active(player)) throw new IllegalStateException("DM mode is inactive");
                DmMode.release(player);
            };
            case PILOT -> {
                Cell destination = cell(buf);
                yield player -> pilot(player, destination);
            }
            case NPC_INSPECT -> {
                UUID id = buf.readUUID();
                yield player -> sendNpc(player, npc(player, id, 24));
            }
            case NPC_SAVE -> {
                UUID id = buf.readUUID();
                long revision = buf.readLong();
                String name = buf.readUtf(80);
                BestiaryPreset preset = readEnum(buf, BestiaryPreset.class);
                NpcAttitude attitude = readEnum(buf, NpcAttitude.class);
                boolean lair = buf.readBoolean();
                String greeting = buf.readUtf(1024);
                var opening = readList(buf, 12, PacketCodec::dialogueOption);
                String skin = buf.readUtf(180);
                yield player -> saveNpc(player, new Npc(id, revision, name, preset, attitude, lair, greeting, opening, skin));
            }
            case NPC_SPAWN -> {
                Cell cell = cell(buf);
                BestiaryPreset preset = readEnum(buf, BestiaryPreset.class);
                NpcAttitude attitude = readEnum(buf, NpcAttitude.class);
                yield player -> {
                    requireNear(player, cell, 24);
                    var npc = NpcSpawns.spawn(player.serverLevel(), block(cell), preset, attitude)
                            .orElseThrow(() -> new IllegalStateException("No space for this NPC"));
                    sendNpc(player, npc);
                };
            }
            case ZONE_INSPECT -> {
                Cell marker = cell(buf);
                yield player -> sendZone(player, marker, zone(player, marker));
            }
            case ZONE_SAVE -> {
                Zone edit = new Zone(cell(buf), buf.readLong(), zoneSpec(buf), buf.readBoolean());
                yield player -> saveZone(player, edit);
            }
        };
    }

    private static void turnAction(ServerPlayer player, Action action) {
        var control = TacticalCombat.control(player);
        long time = TacticalCombat.now();
        switch (action) {
            case END_TURN -> control.manager().endTurn(control.actor(), time);
            case DASH -> control.manager().dash(control.actor(), false, time);
            case DISENGAGE -> control.manager().disengage(control.actor(), false, time);
            case STAND -> control.manager().stand(control.actor(), time);
            case SURGE -> control.manager().actionSurge(control.actor(), time);
            default -> throw new IllegalArgumentException("Unknown turn action");
        }
    }

    private static void react(ServerPlayer player, UUID encounter, long window, Optional<CombatManager.Reaction> response) {
        TacticalCombat.requireUnfrozen(player);
        if (!encounter.equals(TacticalCombat.encounterId(player))) throw new IllegalStateException("Encounter changed");
        var control = TacticalCombat.control(player);
        if (!control.manager().answerReaction(window, control.actor(), response, TacticalCombat.now())) {
            throw new IllegalStateException("Reaction expired or unavailable");
        }
    }

    private static void interact(ServerPlayer player, Cell cell) {
        var control = TacticalCombat.control(player);
        if (!control.actor().equals(player.getUUID())) throw new IllegalStateException("This action requires a player");
        if (!(player.pick(4.5, 1, false) instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(block(cell)) || !player.serverLevel().isLoaded(hit.getBlockPos())
                || TacticalCombat.cell(player.blockPosition()).distanceFeet(cell) > 5) {
            throw new IllegalArgumentException("The block is out of reach");
        }
        var level = player.serverLevel();
        if (!level.mayInteract(player, hit.getBlockPos())) throw new IllegalStateException("That block is protected");
        control.manager().interact(player.getUUID(), TacticalCombat.now());
        level.getBlockState(hit.getBlockPos()).use(level, player, InteractionHand.MAIN_HAND, hit);
    }

    private static void pilot(ServerPlayer player, Cell destination) {
        if (!DmMode.active(player) || !(player.getCamera() instanceof DndNpcEntity npc)
                || npc.level() != player.level() || TacticalCombat.isLocked(npc)
                || npc.deathSaves().state() != net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.CONSCIOUS) {
            throw new IllegalStateException("Possess a conscious NPC outside combat first");
        }
        requireNear(player, destination, 16);
        if (npc.distanceToSqr(destination.x() + .5, destination.y(), destination.z() + .5) > 16 * 16) {
            throw new IllegalArgumentException("Destination is out of reach");
        }
        npc.setTarget(null);
        if (!npc.getNavigation().moveTo(destination.x() + .5, destination.y(), destination.z() + .5, 1.0)) {
            throw new IllegalArgumentException("NPC cannot reach this destination");
        }
    }

    private static void ping(ServerPlayer player, Cell cell) {
        if (!session(player).pings.take(System.nanoTime())) return;
        if (!(player.getCamera().pick(48, 1, false) instanceof BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(block(cell))) {
            throw new IllegalArgumentException("Ping requires a visible block");
        }
        for (ServerPlayer recipient : player.serverLevel().players()) {
            if (recipient.distanceToSqr(hit.getLocation()) <= 96 * 96) send(recipient, Update.PING, out -> {
                cell(out, cell);
                out.writeUtf(trim(player.getGameProfile().getName(), 80), 80);
            });
        }
    }

    private static CharacterDraft readDraft(FriendlyByteBuf buf) {
        String name = buf.readUtf(32);
        RaceType race = readEnum(buf, RaceType.class);
        BackgroundType background = readEnum(buf, BackgroundType.class);
        ClassType type = readEnum(buf, ClassType.class);
        AbilityMethod method = readEnum(buf, AbilityMethod.class);
        List<Integer> scores = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) scores.add(PacketPolicy.count(buf.readVarInt(), 18));
        return new CharacterDraft(name, race, background, type, method, scores,
                new CharacterSheetData.Personality(buf.readUtf(80), buf.readUtf(80), buf.readUtf(80)));
    }

    private static void draftRoll(ServerPlayer player) {
        requireCreation(player);
        var session = session(player);
        if (session.rolls.isEmpty()) {
            session.rolls = java.util.stream.IntStream.range(0, 6).map(i ->
                    PointBuyCalculator.rollStartingScore(sides -> 1 + player.getRandom().nextInt(sides))).boxed().toList();
        }
        send(player, Update.DRAFT_ROLL, out -> writeList(out, session.rolls, FriendlyByteBuf::writeVarInt));
    }

    private static void create(ServerPlayer player, CharacterDraft draft) {
        requireCreation(player);
        var session = session(player);
        PlayerSheets.set(player, draft.create(session.rolls));
        session.rolls = List.of();
        sendSheet(player, true);
    }

    private static void requireCreation(ServerPlayer player) {
        if (PlayerSheets.get(player).isPresent() || TacticalCombat.isLocked(player) || player.isSpectator()) {
            throw new IllegalStateException("Character creation is unavailable");
        }
    }

    private static void beginRitual(ServerPlayer player, String spellId) {
        var sheet = PlayerSheets.get(player).orElseThrow(() -> new IllegalStateException("Create a character first"));
        var session = session(player);
        if (session.ritual != null || TacticalCombat.isLocked(player) || player.isSpectator()
                || !SpellAccess.rituals(sheet, SpellAccess.available(sheet)).contains(spellId)) {
            throw new IllegalStateException("Ritual unavailable");
        }
        int duration = SpellRegistry5e.require(spellId).ritualCastingMinutes();
        session.ritual = new Ritual(spellId, player.level().dimension().location(), player.position(), player.getHealth(),
                player.serverLevel().getGameTime() + duration * 1200L);
        notice(player, Component.translatable("dnd-5e-craft.net.ritual_begin", duration));
    }

    private static void playerChangedWorld(ServerPlayer player, ServerLevel origin, Session session) {
        TacticalCombat.playerChanged(player, origin);
        if (origin != player.serverLevel()) pauseRitual(session, origin);
        TabletopPackets.worldChanged(player);
        session.invalidateSnapshot();
    }

    private static void pauseRitual(Session session, ServerLevel origin) {
        if (session.ritual != null && session.pausedRitualTicks == null
                && session.ritual.dimension().equals(origin.dimension().location())) {
            session.pausedRitualTicks = Math.max(0, session.ritual.ends() - origin.getGameTime());
        }
    }

    private static void tickRitual(ServerPlayer player) {
        var session = session(player);
        var ritual = session.ritual;
        if (ritual == null || !player.level().dimension().location().equals(ritual.dimension)) return;
        if (!player.isAlive()) {
            pauseRitual(session, player.serverLevel());
            return;
        }
        if (session.pausedRitualTicks != null) {
            ritual = new Ritual(ritual.spell, ritual.dimension, ritual.origin, ritual.health,
                    player.serverLevel().getGameTime() + session.pausedRitualTicks);
            session.ritual = ritual;
            session.pausedRitualTicks = null;
        }
        if (player.isSpectator() || TacticalCombat.isLocked(player) || player.getHealth() < ritual.health
                || player.position().distanceToSqr(ritual.origin) > .25) {
            session.ritual = null;
            notice(player, Component.translatable("dnd-5e-craft.net.ritual_interrupted"));
        } else if (player.serverLevel().getGameTime() >= ritual.ends) {
            session.ritual = null;
            try {
                var auras = TacticalCombat.performRitual(player, ritual.spell);
                notice(player, auras.isEmpty() ? Component.translatable("dnd-5e-craft.net.no_auras")
                        : Component.translatable("dnd-5e-craft.net.auras", String.join(", ", auras)));
            } catch (IllegalArgumentException | IllegalStateException interrupted) {
                notice(player, Component.translatable("dnd-5e-craft.net.ritual_interrupted"));
            }
        }
    }

    private static DndNpcEntity npc(ServerPlayer player, UUID id, int range) {
        if (player.serverLevel().getEntity(id) instanceof DndNpcEntity npc && npc.isAlive()
                && player.distanceToSqr(npc) <= range * range) return npc;
        throw new IllegalArgumentException("NPC is unavailable or too far away");
    }

    private static void requireNear(ServerPlayer player, Cell cell, int range) {
        var level = player.serverLevel();
        BlockPos pos = block(cell);
        if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || player.distanceToSqr(cell.x() + .5, cell.y() + .5, cell.z() + .5) > range * range) {
            throw new IllegalArgumentException("Position is unavailable or too far away");
        }
    }

    private static TriggerZoneBlockEntity zone(ServerPlayer player, Cell marker) {
        requireNear(player, marker, 24);
        if (player.serverLevel().getBlockEntity(block(marker)) instanceof TriggerZoneBlockEntity zone && zone.specification() != null) return zone;
        throw new IllegalArgumentException("No trigger marker at that position");
    }

    private static BlockPos block(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }

    private static Npc npcView(DndNpcEntity npc) {
        var tree = npc.dialogue();
        var node = tree.node(tree.start());
        var opening = node.choices().stream().map(choice -> option(choice, NpcAttitude.INDIFFERENT)).toList();
        String name = trim(npc.getDisplayName().getString(), 80);
        String skin = npc.skin().map(Object::toString).orElse("");
        long revision = java.util.Objects.hash(npc.getUUID(), name, npc.preset(), npc.attitude(), skin,
                npc.bestiary().isInLair(), System.identityHashCode(tree));
        return new Npc(npc.getUUID(), revision, name, npc.preset(), npc.attitude(), npc.bestiary().isInLair(), node.text(), opening, skin);
    }

    private static void saveNpc(ServerPlayer player, Npc edit) {
        var npc = npc(player, edit.id(), 24);
        var view = npcView(npc);
        if (view.revision() != edit.revision() || TacticalCombat.isLocked(npc)) {
            throw new IllegalStateException("NPC changed or is participating in combat");
        }
        if (edit.name().isBlank() || edit.name().codePoints().anyMatch(Character::isISOControl)
                || (edit.lair() && !edit.preset().features().contains(BestiaryPreset.Feature.LAIR_ACTION))) {
            throw new IllegalArgumentException("Invalid NPC configuration");
        }
        DndNpcEntity.validatedSkin(edit.skin());
        var tree = npc.dialogue();
        var original = tree.node(tree.start());
        var options = new ArrayList<DialogueChoice>();
        if (edit.opening().size() != original.choices().size()) throw new IllegalArgumentException("Dialogue options changed");
        for (int i = 0; i < original.choices().size(); i++) {
            var old = original.choices().get(i);
            var input = edit.opening().get(i);
            if (!old.id().equals(input.id())) throw new IllegalArgumentException("Unknown dialogue option");
            options.add(new DialogueChoice(old.id(), input.label(), Optional.ofNullable(input.skill())
                    .map(skill -> new DialogueChoice.Check(skill, input.dc())), old.success(), old.failure()));
        }
        var node = new DialogueNode(original.id(), edit.greeting(), options);
        var changed = new DialogueTree(tree.start(), tree.nodes().stream().map(n -> n.id().equals(node.id()) ? node : n).toList());
        var priorPreset = npc.preset();
        var priorName = npc.getCustomName();
        boolean defaultName = priorName != null && priorName.getContents() instanceof TranslatableContents translation
                && translation.getKey().equals(DialogueText.NPC_NAME_PREFIX + priorPreset.id());
        if (priorPreset != edit.preset()) {
            npc.initializePreset(edit.preset(), edit.attitude());
            if (!defaultName) npc.setCustomName(priorName);
        } else npc.setAttitude(edit.attitude());
        if (!edit.name().equals(view.name())) npc.setCustomName(Component.literal(edit.name().strip()));
        npc.setDialogue(BestiaryRegistry5e.dialogueAfterPresetChange(priorPreset, edit.preset(), changed));
        if (!view.skin().equals(edit.skin())) npc.setAppearance(edit.skin(), npc.physicalScale());
        npc.bestiary().setLairEnabled(edit.lair());
        notice(player, Component.translatable("dnd-5e-craft.net.saved"));
        sendNpc(player, npc);
    }

    private static long zoneRevision(TriggerZoneBlockEntity zone) {
        return java.util.Objects.hash(System.identityHashCode(zone), zone.specification(), zone.armed());
    }

    private static void saveZone(ServerPlayer player, Zone edit) {
        var zone = zone(player, edit.marker());
        if (zoneRevision(zone) != edit.revision()) throw new IllegalStateException("Trigger zone changed");
        var level = player.serverLevel();
        for (Cell corner : List.of(edit.spec().min(), edit.spec().max())) {
            if (level.isOutsideBuildHeight(block(corner)) || !level.getWorldBorder().isWithinBounds(block(corner))) {
                throw new IllegalArgumentException("Trigger bounds are outside the world");
            }
        }
        zone.configure(edit.spec());
        if (!edit.armed()) zone.toggleArmed();
        notice(player, Component.translatable("dnd-5e-craft.net.saved"));
        sendZone(player, edit.marker(), zone);
    }

    public static void inspectNpc(ServerPlayer player, DndNpcEntity npc) {
        if (supports(player) && player.hasPermissions(2) && player.serverLevel() == npc.level()
                && player.distanceToSqr(npc) <= 24 * 24) sendNpc(player, npc);
    }

    public static void inspectZone(ServerPlayer player, BlockPos pos) {
        if (supports(player) && player.hasPermissions(2)) {
            Cell marker = TacticalCombat.cell(pos);
            var found = zone(player, marker);
            sendZone(player, marker, found);
        }
    }

    private static void sendNpc(ServerPlayer player, DndNpcEntity npc) {
        var view = npcView(npc);
        send(player, Update.NPC, out -> {
            out.writeUUID(view.id());
            out.writeLong(view.revision());
            out.writeUtf(view.name(), 80);
            writeEnum(out, view.preset());
            writeEnum(out, view.attitude());
            out.writeBoolean(view.lair());
            out.writeUtf(view.greeting(), 1024);
            writeList(out, view.opening(), PacketCodec::dialogueOption);
            out.writeUtf(view.skin(), 180);
        });
    }

    private static void sendZone(ServerPlayer player, Cell marker, TriggerZoneBlockEntity zone) {
        send(player, Update.ZONE, out -> {
            cell(out, marker);
            out.writeLong(zoneRevision(zone));
            zoneSpec(out, zone.specification());
            out.writeBoolean(zone.armed());
        });
    }

    public static DialogueOption option(DialogueChoice choice, NpcAttitude attitude) {
        return new DialogueOption(choice.id(), choice.label(), choice.check().map(DialogueChoice.Check::skill).orElse(null),
                choice.check().map(check -> attitude.adjustDifficulty(check.difficulty())).orElse(0));
    }

    public static void sendDialogue(ServerPlayer player, Dialogue dialogue) {
        sendDialogue(player, dialogue, Component.literal(dialogue.title()));
    }

    public static void sendDialogue(ServerPlayer player, Dialogue dialogue, Component name) {
        String title = player.connection != null && ServerPlayNetworking.canSend(player, TIMED_UPDATE)
                && name.getContents() instanceof TranslatableContents translation
                && translation.getKey().startsWith(DialogueText.NPC_NAME_PREFIX) ? translation.getKey() : dialogue.title();
        send(player, Update.DIALOGUE, out -> {
            out.writeUUID(dialogue.token());
            out.writeUtf(trim(title, 80), 80);
            out.writeUtf(dialogue.text(), 1024);
            writeEnum(out, dialogue.attitude());
            writeList(out, dialogue.options(), PacketCodec::dialogueOption);
        });
    }

    public static void endDialogue(ServerPlayer player, UUID token) {
        send(player, Update.DIALOGUE_END, out -> out.writeUUID(token));
    }

    public static void sendParty(ServerPlayer operator) {
        if (!operator.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        var party = operator.serverLevel().players().stream().filter(player -> !player.isSpectator())
                .limit(Protocol.MAX_COMBATANTS).flatMap(player -> PlayerSheets.get(player).stream().map(sheet ->
                        new Protocol.PartyMember(player.getUUID(), trim(sheet.name(), 80), sheet.level(), sheet.hitPoints().current(),
                                sheet.hitPoints().maximum(), sheet.hitPoints().temporary(), TacticalCombat.armorClass(player, sheet),
                                sheet.deathSaves().state()))).toList();
        send(operator, Update.PARTY, buf -> writeList(buf, party, (out, member) -> {
            out.writeUUID(member.id());
            out.writeUtf(member.name(), 80);
            out.writeVarInt(member.level());
            out.writeVarInt(member.hp());
            out.writeVarInt(member.maxHp());
            out.writeVarInt(member.temporary());
            out.writeVarInt(member.armor());
            writeEnum(out, member.life());
        }));
    }

    public static void recordJournal(ServerPlayer player, Component entry) {
        var journal = session(player).journal;
        if (journal.size() >= Protocol.JOURNAL_LIMIT) journal.removeLast();
        journal.addFirst(entry.copy());
    }

    private static void sendJournal(ServerPlayer player) {
        if (!supports(player)) return;
        send(player, Update.JOURNAL, out -> {
            out.writeUtf(((net.dnd5ecraft.server.PlayerCharacter) player).dnd5e$journalNotes(), Protocol.JOURNAL_NOTES_LIMIT);
            writeList(out, List.copyOf(session(player).journal), FriendlyByteBuf::writeComponent);
        });
    }

    public static void banner(ServerPlayer player, Component title) {
        recordJournal(player, Component.translatable("dnd-5e-craft.ui.journal.chapter", title));
        if (supports(player)) send(player, Update.CINEMATIC_BANNER, out -> out.writeComponent(title));
        else player.displayClientMessage(title, true);
    }

    public static void glint(ServerPlayer player, BlockPos pos, Component whisper) {
        if (supports(player)) {
            send(player, Update.PASSIVE_DETECTION_GLINT, out -> {
                cell(out, TacticalCombat.cell(pos));
                out.writeComponent(whisper);
            });
        } else {
            player.serverLevel().sendParticles(player, net.minecraft.core.particles.ParticleTypes.END_ROD, true,
                    pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 6, .2, .25, .2, .02);
            player.displayClientMessage(whisper, true);
        }
    }

    public static void sendRoll(ServerPlayer player, int result) {
        Vec3 position = new Vec3(player.getX(), player.getY() + player.getBbHeight() + .55, player.getZ());
        Roll roll = new Roll(player.getUUID(), player.getGameProfile().getName(), position, result, player.getRandom().nextLong());
        var entry = Component.translatable(result == 20 ? "dnd-5e-craft.ui.hud.roll_critical" : "dnd-5e-craft.ui.hud.roll",
                player.getDisplayName(), result);
        for (ServerPlayer recipient : player.serverLevel().players()) {
            if (player.distanceToSqr(recipient) > 40 * 40) continue;
            recordJournal(recipient, entry);
            send(recipient, Update.ROLL, out -> roll(out, roll));
        }
    }

    public static void directorSelection(ServerPlayer player, UUID npc) {
        send(player, TABLETOP, Update.DIRECTOR_SELECTION, out -> out.writeUUID(npc));
    }

    public static void sendDamage(ServerPlayer player, int damage) {
        send(player, Update.DAMAGE, out -> out.writeVarInt(Math.min(damage, 100)));
    }

    public static void sendSheet(ServerPlayer player, boolean open) {
        if (!supports(player)) return;
        var sheet = PlayerSheets.get(player).orElse(null);
        send(player, Update.SHEET, buf -> {
            buf.writeBoolean(open);
            buf.writeNbt(sheet == null ? null : sheet.toNbt());
            buf.writeVarInt(sheet == null ? 0 : TacticalCombat.armorClass(player, sheet));
        });
        var session = session(player);
        session.sheet = sheet;
        session.sheetAc = sheet == null ? 0 : TacticalCombat.armorClass(player, sheet);
        session.sentSheet = true;
    }

    private static void sync(ServerPlayer player) {
        if (!supports(player)) return;
        TabletopPackets.sync(player);
        var session = session(player);
        boolean timed = ServerPlayNetworking.canSend(player, TIMED_UPDATE);
        var combat = TacticalCombat.networkView(player);
        if (!timed && combat != null && (!combat.hasActor() || combat.activeActor() == null)) combat = null;
        var selected = new State(player.hasPermissions(2), player.hasPermissions(2) && DmMode.active(player),
                TacticalCombat.networkDarkvision(player), combat);
        if (!selected.equals(session.state) || timed != session.timed) {
            send(player, timed ? TIMED_UPDATE : UPDATE, Update.STATE, out -> state(out, selected, timed));
            session.state = selected;
            session.timed = timed;
        }
        var currentSheet = PlayerSheets.get(player).orElse(null);
        int currentAc = currentSheet == null ? 0 : TacticalCombat.armorClass(player, currentSheet);
        if (!session.sentSheet || !java.util.Objects.equals(session.sheet, currentSheet) || session.sheetAc != currentAc) {
            sendSheet(player, false);
        }
    }

    private static Session session(ServerPlayer player) { return SESSIONS.getOrCreate(player.getUUID(), Session::new); }

    public static boolean supports(ServerPlayer player) { return player.connection != null && ServerPlayNetworking.canSend(player, UPDATE); }

    public static void notice(ServerPlayer player, Component message) {
        if (supports(player)) send(player, Update.NOTICE, out -> out.writeComponent(message));
        else player.displayClientMessage(message, true);
    }

    static void send(ServerPlayer player, Update kind, Consumer<FriendlyByteBuf> write) {
        send(player, UPDATE, kind, write);
    }

    static void send(ServerPlayer player, ResourceLocation channel, Update kind, Consumer<FriendlyByteBuf> write) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, channel)) return;
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(Protocol.VERSION);
        writeEnum(buf, kind);
        write.accept(buf);
        ServerPlayNetworking.send(player, channel, buf);
    }

    public static String trim(String value, int length) {
        if (value.length() <= length) return value;
        int end = Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
        return value.substring(0, end);
    }
}
