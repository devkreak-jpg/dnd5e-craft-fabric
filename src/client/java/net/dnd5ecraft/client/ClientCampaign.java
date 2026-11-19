package net.dnd5ecraft.client;

import java.util.UUID;
import java.util.function.BiConsumer;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.entity.CampaignCompanion;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy;
import net.dnd5ecraft.server.CampaignActions.Action;
import net.dnd5ecraft.server.DmDirector.Tool;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.FriendlyByteBuf;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

public final class ClientCampaign {
    private enum Pick { CREATURE, POINT }
    private record Selection(Pick kind, double range, BiConsumer<UUID, Cell> callback) {}
    private static Selection selection;
    private static UUID directedNpc;

    private ClientCampaign() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (directedNpc == null || !ClientDndState.state().operator() || context.consumers() == null) return;
            for (var entity : context.world().entitiesForRendering()) {
                if (!entity.getUUID().equals(directedNpc)) continue;
                var camera = context.camera().getPosition();
                LevelRenderer.renderLineBox(context.matrixStack(), context.consumers().getBuffer(RenderType.lines()),
                        entity.getBoundingBox().inflate(.055).move(camera.reverse()), 1f, .76f, .18f, 1f);
                break;
            }
        });
    }

    static boolean isCampaignReaction(net.dnd5ecraft.combat.ReactionWindowManager.Kind kind) {
        return switch (kind) {
            case IMPROMPTU_ADD, IMPROMPTU_SUBTRACT, PROMPTERS_EDIT, FINAL_ACT_20, FINAL_ACT_1, IMPROVISATION, ABSORB_ELEMENTS -> true;
            default -> false;
        };
    }

    static void directedNpc(UUID id) { directedNpc = id; }
    public static UUID directedNpc() { return directedNpc; }
    public static void cancelPick() { selection = null; }
    static void reset() { cancelPick(); directedNpc = null; }

    public static <T extends CampaignCompanion> T companion(Class<T> kind) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return null;
        return client.level.getEntitiesOfClass(kind, client.player.getBoundingBox().inflate(64),
                companion -> companion.isAlive() && companion.ownerId().filter(client.player.getUUID()::equals).isPresent())
                .stream().findFirst().orElse(null);
    }

    public static void act(Action action, UUID target, Cell point, int option) {
        ClientPacketHandlers.send(PacketPolicy.Action.CAMPAIGN_ACTION, out -> {
            PacketCodec.writeEnum(out, action);
            target(out, target);
            point(out, point);
            out.writeInt(option);
            turn(out, net.dnd5ecraft.server.CampaignActions.turnBound(action));
        });
    }

    public static void spell(String id, UUID target, Cell point, String phrase) {
        ClientPacketHandlers.send(PacketPolicy.Action.CAMPAIGN_SPELL, out -> {
            out.writeUtf(id, 64);
            target(out, target);
            point(out, point);
            out.writeUtf(phrase, 160);
            turn(out, true);
        });
    }

    public static void director(Tool tool, UUID target, int value) {
        ClientPacketHandlers.send(PacketPolicy.Action.DIRECTOR, out -> {
            PacketCodec.writeEnum(out, tool);
            target(out, target);
            out.writeVarInt(value);
        });
    }

    public static void scenePair(boolean director) {
        double range = director ? 48 : 4;
        creature(range, first -> creature(range, second -> {
            var combat = ClientDndState.combat();
            var target = combat == null ? null : combat.creature(second);
            if (target != null) {
                act(director ? Action.DIRECTOR_SCENE_CHANGE : Action.SCENE_CHANGE, first, target.cell(), 0);
                return;
            }
            var world = Minecraft.getInstance().level;
            if (director && world != null) for (var entity : world.entitiesForRendering()) {
                if (entity.getUUID().equals(second)) {
                    act(Action.DIRECTOR_SCENE_CHANGE, first, ClientActions.asCell(entity.blockPosition()), 0);
                    break;
                }
            }
        }));
    }

    public static void creature(double range, java.util.function.Consumer<UUID> task) {
        select(Pick.CREATURE, range, (id, point) -> task.accept(id));
    }

    public static void point(java.util.function.Consumer<Cell> task) {
        select(Pick.POINT, 0, (id, point) -> task.accept(point));
    }

    private static void select(Pick kind, double range, BiConsumer<UUID, Cell> callback) {
        selection = new Selection(kind, range, callback);
        Minecraft.getInstance().setScreen(null);
        ClientDndState.notify(text(kind == Pick.CREATURE ? "campaign.pick_creature" : "campaign.pick_point"));
    }

    static boolean pick() {
        if (selection == null) return false;
        var player = Minecraft.getInstance().player;
        if (player != null && player.isShiftKeyDown()) {
            cancelPick();
            return true;
        }
        var pending = selection;
        var hit = pending.kind() == Pick.CREATURE ? ClientActions.aimedCreature(pending.range()) : null;
        var point = pending.kind() == Pick.POINT ? ClientActions.hoveredCell() : null;
        if (hit == null && point == null) {
            ClientDndState.notify(text("aim.no_target"));
            return true;
        }
        selection = null;
        pending.callback().accept(hit == null ? null : hit.getEntity().getUUID(), point);
        return true;
    }

    private static void turn(FriendlyByteBuf out, boolean requiredInCombat) {
        var combat = ClientDndState.combat();
        boolean present = requiredInCombat && ClientDndState.controlsCombatant() && combat.ownsTurn();
        out.writeBoolean(present);
        if (present) PacketCodec.turn(out, combat.turn());
    }

    private static void target(FriendlyByteBuf out, UUID target) {
        out.writeBoolean(target != null);
        if (target != null) out.writeUUID(target);
    }

    private static void point(FriendlyByteBuf out, Cell point) {
        out.writeBoolean(point != null);
        if (point != null) PacketCodec.cell(out, point);
    }
}
