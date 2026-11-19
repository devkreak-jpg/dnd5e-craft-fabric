package net.dnd5ecraft.client;

import java.util.function.Consumer;
import net.dnd5ecraft.client.gui.screen.CharacterCreationScreen;
import net.dnd5ecraft.client.gui.screen.CharacterSheetScreen;
import net.dnd5ecraft.client.gui.screen.DialogueScreen;
import net.dnd5ecraft.client.gui.screen.NpcStudioScreen;
import net.dnd5ecraft.client.gui.screen.ReactionPromptScreen;
import net.dnd5ecraft.client.gui.screen.TriggerZoneEditScreen;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.network.TabletopCodec;
import net.dnd5ecraft.network.Protocol.Update;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.network.PacketCodec.*;

public final class ClientPacketHandlers {
    private static LocalPlayer synchronizedPlayer;
    private static ClientLevel synchronizedLevel;

    private ClientPacketHandlers() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(ClientPacketHandlers::reset));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientPacketHandlers::reset));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || !ClientPlayNetworking.canSend(ModPackets.REQUEST)) return;
            if (client.player != synchronizedPlayer || client.level != synchronizedLevel) {
                if (client.level != synchronizedLevel) ClientDndState.clearWorldEffects();
                synchronizedPlayer = client.player;
                synchronizedLevel = client.level;
                send(Action.SYNC);
            }
        });
        receive(ModPackets.UPDATE, false);
        receive(ModPackets.TIMED_UPDATE, true);
        receive(ModPackets.TABLETOP, false);
    }

    private static void reset() {
        synchronizedPlayer = null;
        synchronizedLevel = null;
        ClientDndState.reset();
    }

    private static void receive(net.minecraft.resources.ResourceLocation channel, boolean timed) {
        ClientPlayNetworking.registerGlobalReceiver(channel, (client, handler, buf, response) -> {
            if (buf.readableBytes() > Protocol.MAX_UPDATE) return;
            try {
                if (buf.readVarInt() != Protocol.VERSION) return;
                Consumer<Minecraft> update = decode(readEnum(buf, Update.class), buf, timed);
                if (buf.isReadable()) return;
                client.execute(() -> {
                    if (client.getConnection() == handler && client.player != null && client.level != null) update.accept(client);
                });
            } catch (RuntimeException malformed) {
                // Ignore protocol or payloads the client cannot decode.
            }
        });
    }

    public static boolean send(Action action) { return send(action, buf -> {}); }

    public static boolean send(Action action, Consumer<FriendlyByteBuf> write) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return false;
        if (!ClientPlayNetworking.canSend(ModPackets.REQUEST)) {
            ClientDndState.notify(Component.translatable("dnd-5e-craft.net.unavailable"));
            return false;
        }
        var combat = ClientDndState.combat();
        if (action.turn() && (combat == null || !combat.ownsTurn())) {
            ClientDndState.notify(Component.translatable("dnd-5e-craft.net.wait_turn"));
            return false;
        }
        if (action.operator() && !ClientDndState.state().operator()) return false;
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(Protocol.VERSION);
        writeEnum(buf, action);
        if (action.turn()) turn(buf, combat.turn());
        try {
            write.accept(buf);
            if (buf.readableBytes() > Protocol.MAX_REQUEST) throw new IllegalArgumentException("Payload is too large");
        } catch (RuntimeException invalid) {
            buf.release();
            ClientDndState.notify(Component.translatable("dnd-5e-craft.net.payload"));
            return false;
        }
        ClientPlayNetworking.send(ModPackets.REQUEST, buf);
        return true;
    }

    private static Consumer<Minecraft> decode(Update kind, FriendlyByteBuf buf, boolean timed) {
        return switch (kind) {
            case STATE -> {
                var snapshot = state(buf, timed);
                yield client -> {
                    ClientDndState.state(snapshot);
                    var combat = snapshot.combat();
                    if (client.screen != null && combat != null && combat.reaction() != null
                            && ClientDndState.reactionRemaining() > 0) {
                        if (client.screen instanceof ReactionPromptScreen prompt) {
                            if (!prompt.matches(combat.id(), combat.reaction().id())) {
                                client.setScreen(new ReactionPromptScreen(prompt.previous(), combat.id(), combat.reaction()));
                            }
                        } else client.setScreen(new ReactionPromptScreen(client.screen, combat.id(), combat.reaction()));
                    }
                };
            }
            case SHEET -> {
                boolean open = buf.readBoolean();
                var nbt = buf.readNbt();
                var sheet = nbt == null ? null : CharacterSheetData.fromNbt(nbt);
                int armor = buf.readVarInt();
                yield client -> {
                    ClientDndState.sheet(sheet, armor);
                    if (open) openSheet();
                };
            }
            case TABLETOP_READY -> ignored -> ClientDndState.tabletopReady();
            case TABLETOP_RESULT -> {
                var card = TabletopCodec.card(buf);
                yield ignored -> ClientDndState.tabletopResult(card);
            }
            case ROLL_PROMPT -> {
                var prompt = TabletopCodec.prompt(buf);
                yield ignored -> ClientDndState.prompt(prompt);
            }
            case PROMPT_END -> {
                var id = buf.readUUID();
                yield ignored -> ClientDndState.endPrompt(id);
            }
            case SHORT_REST -> {
                boolean active = buf.readBoolean();
                yield ignored -> ClientDndState.shortRest(active);
            }
            case CONCENTRATION -> {
                var active = new java.util.HashMap<java.util.UUID, String>();
                int count = net.dnd5ecraft.network.PacketPolicy.count(buf.readVarInt(), Protocol.MAX_COMBATANTS);
                for (int i = 0; i < count; i++) active.put(buf.readUUID(), buf.readUtf(64));
                yield ignored -> ClientDndState.concentration(active);
            }
            case DIALOGUE_ROLL -> {
                var token = buf.readUUID();
                var card = TabletopCodec.card(buf);
                yield client -> {
                    if (client.screen instanceof DialogueScreen screen && screen.token().equals(token)) screen.showRoll(card);
                };
            }
            case DRAFT_ROLL -> {
                var rolled = readList(buf, 6, FriendlyByteBuf::readVarInt);
                yield client -> {
                    ClientDndState.draft(rolled);
                    if (client.screen instanceof CharacterCreationScreen creation) creation.receiveRoll(rolled);
                };
            }
            case ROLL -> {
                var roll = roll(buf);
                yield ignored -> ClientDndState.roll(roll);
            }
            case PING -> {
                var cell = cell(buf);
                var player = buf.readUtf(80);
                yield ignored -> ClientDndState.ping(cell, player);
            }
            case DIALOGUE -> {
                var dialogue = new Protocol.Dialogue(buf.readUUID(), buf.readUtf(80), buf.readUtf(1024),
                        readEnum(buf, NpcAttitude.class), readList(buf, 12, PacketCodec::dialogueOption));
                yield client -> {
                    if (!(client.screen instanceof DialogueScreen screen && screen.queueBranch(dialogue))) {
                        client.setScreen(new DialogueScreen(dialogue));
                    }
                };
            }
            case DIALOGUE_END -> {
                var token = buf.readUUID();
                yield client -> {
                    if (client.screen instanceof DialogueScreen screen && screen.token().equals(token) && !screen.queueBranch(null)) client.setScreen(null);
                };
            }
            case NPC -> {
                var npc = new Protocol.Npc(buf.readUUID(), buf.readLong(), buf.readUtf(80), readEnum(buf, BestiaryPreset.class),
                        readEnum(buf, NpcAttitude.class), buf.readBoolean(), buf.readUtf(1024),
                        readList(buf, 12, PacketCodec::dialogueOption));
                yield client -> client.setScreen(new NpcStudioScreen(npc));
            }
            case ZONE -> {
                var zone = new Protocol.Zone(cell(buf), buf.readLong(), zoneSpec(buf), buf.readBoolean());
                yield client -> client.setScreen(new TriggerZoneEditScreen(zone));
            }
            case NOTICE -> {
                var message = buf.readComponent();
                yield ignored -> ClientDndState.notify(message);
            }
            case PARTY -> {
                var party = readList(buf, Protocol.MAX_COMBATANTS, input -> new Protocol.PartyMember(input.readUUID(),
                        input.readUtf(80), input.readVarInt(), input.readVarInt(), input.readVarInt(), input.readVarInt(),
                        input.readVarInt(), readEnum(input, net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.class)));
                yield ignored -> ClientDndState.party(party);
            }
            case CINEMATIC_BANNER -> {
                var title = buf.readComponent();
                yield ignored -> ClientDndState.banner(title);
            }
            case PASSIVE_DETECTION_GLINT -> {
                var position = cell(buf);
                var whisper = buf.readComponent();
                yield client -> {
                    if (client.level != null) {
                        for (int i = 0; i < 10; i++) {
                            client.level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                                    position.x() + .25 + client.level.random.nextDouble() * .5, position.y() + .65,
                                    position.z() + .25 + client.level.random.nextDouble() * .5, 0, .04, 0);
                        }
                    }
                    ClientDndState.notify(whisper);
                };
            }
            case DAMAGE -> {
                int amount = buf.readVarInt();
                yield ignored -> ClientDndState.damage(amount);
            }
        };
    }

    public static void openSheet() {
        var client = Minecraft.getInstance();
        ClientActions.cancelAim();
        client.setScreen(ClientDndState.sheet() == null ? new CharacterCreationScreen() : new CharacterSheetScreen());
    }
}
