package net.dnd5ecraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.client.gui.screen.DmMasterConsoleScreen;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.ModEntities;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

public final class ClientActions {
    private static final class Targeting {
        final Spell spell;
        final int slot;
        final Pool pool;
        final String choice;
        final UUID encounter;
        final List<UUID> targets = new ArrayList<>();
        AoEGeometry.Vec3 origin;

        Targeting(Spell spell, int slot, Pool pool, String choice, UUID encounter) {
            this.spell = spell;
            this.slot = slot;
            this.pool = pool;
            this.choice = choice;
            this.encounter = encounter;
        }
    }

    private static Targeting aiming;
    private static AoEGeometry.Template preview;
    private static int attackProfile;

    private ClientActions() {}

    public static void startSpell(Spell spell, int slot, Pool pool, String choice) {
        cancelAim();
        var combat = ClientDndState.combat();
        if (combat == null || !combat.ownsTurn()) return;
        var target = new Targeting(spell, slot, pool, choice, combat.id());
        if (spell.targeting() == Spell.Targeting.SELF) sendSpell(target);
        else aiming = target;
    }

    public static boolean aiming() { return aiming != null; }
    public static int selectedTargets() { return aiming == null ? 0 : aiming.targets.size(); }
    public static int maximumTargets() {
        var sheet = ClientDndState.sheet();
        return aiming == null ? 0 : aiming.spell.targetsAt(aiming.slot, sheet == null ? 1 : sheet.level());
    }
    public static void cancelAim() { aiming = null; preview = null; }

    public static void preview(AoEGeometry geometry) {
        preview = switch (geometry) {
            case LINE -> new AoEGeometry.Template(geometry, 30, 5);
            case CYLINDER -> new AoEGeometry.Template(geometry, 10, 20);
            default -> new AoEGeometry.Template(geometry, 15);
        };
    }

    public static int attackProfile() {
        var combat = ClientDndState.combat();
        return combat == null || combat.attacks().isEmpty() ? 0 : attackProfile % combat.attacks().size();
    }

    public static void cycleAttack() {
        var combat = ClientDndState.combat();
        if (combat == null || combat.attacks().size() < 2) return;
        attackProfile = (attackProfile() + 1) % combat.attacks().size();
        ClientDndState.notify(text("hud.attack", combat.attacks().get(attackProfile)));
    }

    public static void tick() {
        if (aiming != null && (ClientDndState.combat() == null || !ClientDndState.combat().id().equals(aiming.encounter)
                || !ClientDndState.combat().ownsTurn())) cancelAim();
        if (!ClientDndState.state().operator()) preview = null;
    }

    public static boolean attack() {
        var client = Minecraft.getInstance();
        if (client.player == null) return false;
        if (ClientCampaign.pick()) return true;
        if (aiming != null) {
            if (aiming.spell.area().isPresent() && (aiming.origin == null || aiming.spell.effect().kind() != Spell.EffectKind.HEAL)) {
                aiming.origin = aimPoint(aiming.spell);
                return true;
            }
            var target = aimedCreature(aiming.spell.rangeFeet() / 5.0);
            if (target != null && aiming.targets.size() < maximumTargets()) {
                if (permitsRepeat(aiming.spell) || !aiming.targets.contains(target.getEntity().getUUID())) aiming.targets.add(target.getEntity().getUUID());
            } else ClientDndState.notify(text("aim.no_target"));
            return true;
        }
        var combat = ClientDndState.combat();
        if (combat == null || !combat.hasActor()) return false;
        var hit = aimedCreature(64);
        if (hit != null && combat.creature(hit.getEntity().getUUID()) != null) {
            ClientPacketHandlers.send(Action.ATTACK, out -> {
                out.writeUUID(hit.getEntity().getUUID());
                out.writeVarInt(attackProfile());
            });
        }
        return true;
    }

    public static boolean use() {
        var client = Minecraft.getInstance();
        if (client.player == null) return false;
        if (ClientCampaign.pick()) return true;
        if (aiming != null) {
            if (aiming.spell.area().isPresent() && aiming.origin == null) aiming.origin = aimPoint(aiming.spell);
            boolean targetsRequired = aiming.spell.targeting() == Spell.Targeting.CREATURES || aiming.spell.effect().kind() == Spell.EffectKind.HEAL;
            if (aiming.spell.area().isPresent() && aiming.origin == null
                    || targetsRequired && aiming.targets.isEmpty()
                    || permitsRepeat(aiming.spell) && maximumTargets() != aiming.targets.size()) {
                ClientDndState.notify(text("aim.no_target"));
            } else if (sendSpell(aiming)) cancelAim();
            return true;
        }
        if (ClientDndState.controlsCombatant()) {
            var item = client.player.getMainHandItem();
            var point = hoveredCell();
            if (item.is(ModItems.ELDRITCH_CANNON_CONTROLLER)) {
                if (ClientCampaign.companion(net.dnd5ecraft.entity.EldritchCannonEntity.class) == null) {
                    ClientCampaign.act(net.dnd5ecraft.server.CampaignActions.Action.CANNON_SUMMON, null, point, 0);
                } else if (client.player.isShiftKeyDown()) {
                    ClientCampaign.act(net.dnd5ecraft.server.CampaignActions.Action.CANNON_MODE, null, null, 0);
                } else net.dnd5ecraft.client.gui.screen.CampaignActionsScreen.fireCannon();
                return true;
            }
            if (item.is(ModItems.ECHO_SIGIL)) {
                var echo = ClientCampaign.companion(net.dnd5ecraft.entity.EchoAvatarEntity.class);
                var action = echo == null ? net.dnd5ecraft.server.CampaignActions.Action.ECHO_MANIFEST
                        : client.player.isShiftKeyDown() ? net.dnd5ecraft.server.CampaignActions.Action.ECHO_SWAP
                        : net.dnd5ecraft.server.CampaignActions.Action.ECHO_MOVE;
                ClientCampaign.act(action, null, point, 0);
                return true;
            }
            if (item.is(ModItems.HARLEQUIN_DECK_WAND)) {
                client.setScreen(new net.dnd5ecraft.client.gui.screen.CampaignActionsScreen(null));
                return true;
            }
        }
        var combat = ClientDndState.combat();
        if (!ClientDndState.controlsCombatant() && ClientDndState.state().dm() && client.getCameraEntity() instanceof DndNpcEntity) {
            Cell destination = hoveredCell();
            if (destination != null) ClientPacketHandlers.send(Action.PILOT, out -> PacketCodec.cell(out, destination));
            return true;
        }
        if (combat == null || !combat.hasActor() || client.player.getMainHandItem().is(ModItems.HEALING_DRAUGHT)) return false;
        Cell goal = hoveredCell();
        if (goal != null && combat.reachable().containsKey(goal)) {
            ClientPacketHandlers.send(Action.MOVE, out -> PacketCodec.cell(out, goal));
        }
        return true;
    }

    public static void interact() {
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            ClientPacketHandlers.send(Action.INTERACT, out -> PacketCodec.cell(out, asCell(hit.getBlockPos())));
        }
    }

    public static Cell hoveredCell() {
        var client = Minecraft.getInstance();
        var actor = client.getCameraEntity();
        if (actor == null) return null;
        HitResult hit = actor.pick(22, 1, false);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            Cell adjacent = asCell(block.getBlockPos().relative(block.getDirection()));
            var combat = ClientDndState.combat();
            if (combat == null || !combat.hasActor() || combat.reachable().containsKey(adjacent)) return adjacent;
            Cell surface = asCell(block.getBlockPos().above());
            return combat.reachable().containsKey(surface) ? surface : adjacent;
        }
        return null;
    }

    public static void console() {
        var client = Minecraft.getInstance();
        if (!ClientDndState.state().operator() || client.player == null) return;
        var hit = aimedCreature(24);
        UUID npc = hit != null && hit.getEntity() instanceof DndNpcEntity ? hit.getEntity().getUUID() : null;
        BlockHitResult block = client.getCameraEntity() != null && client.getCameraEntity().pick(24, 1, false) instanceof BlockHitResult b
                && b.getType() == HitResult.Type.BLOCK ? b : null;
        Cell marker = block != null && client.level != null && client.level.getBlockState(block.getBlockPos()).is(ModEntities.TRIGGER_ZONE)
                ? asCell(block.getBlockPos()) : null;
        Cell spawn = block == null ? null : asCell(block.getBlockPos().relative(block.getDirection()));
        client.setScreen(new DmMasterConsoleScreen(npc, marker, spawn));
    }

    public static void ping() {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera != null && camera.pick(32, 1, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            ClientPacketHandlers.send(Action.PING, buf -> PacketCodec.cell(buf, asCell(hit.getBlockPos())));
        }
    }

    public static void react(Kind kind, boolean highest) {
        var combat = ClientDndState.combat();
        if (combat == null || !combat.hasActor() || combat.reaction() == null || ClientDndState.reactionRemaining() == 0
                || kind != null && !combat.reaction().choices().contains(kind)) return;
        Pool chosenPool = Pool.REGULAR;
        int chosenSlot = 0;
        if (kind == Kind.SHIELD || kind == Kind.COUNTERSPELL || kind == Kind.ABSORB_ELEMENTS) {
            int min = kind == Kind.COUNTERSPELL ? 3 : 1;
            for (int i = 0; i <= 9 - min; i++) {
                int level = highest ? 9 - i : min + i;
                for (Pool pool : Pool.values()) {
                    if (combat.slots().available(level, pool == Pool.PACT) > 0) {
                        chosenPool = pool;
                        chosenSlot = level;
                        break;
                    }
                }
                if (chosenSlot != 0) break;
            }
            if (chosenSlot == 0) return;
        }
        int slot = chosenSlot;
        Pool pool = chosenPool;
        ClientPacketHandlers.send(Action.REACT, out -> {
            out.writeUUID(combat.id());
            out.writeVarLong(combat.reaction().id());
            out.writeBoolean(kind != null);
            if (kind != null) {
                PacketCodec.writeEnum(out, kind);
                out.writeVarInt(slot);
                PacketCodec.writeEnum(out, pool);
            }
        });
    }

    public static AoEGeometry.Area areaPreview() {
        if (preview != null) return demoArea();
        var combat = ClientDndState.combat();
        if (aiming == null || combat == null || aiming.spell.area().isEmpty()) return null;
        var actor = combat.creature(combat.controlledActor());
        var target = aimPoint(aiming.spell);
        if (actor == null || target == null) return null;
        var center = actor.cell().center();
        var aim = aiming.origin == null ? target : aiming.origin;
        try {
            return aiming.spell.area().orElseThrow().place(aiming.spell.rangeFeet() == 0 ? center : aim,
                    aim.minus(center), AoEGeometry.Box.point(center), aiming.spell.rangeFeet() != 0);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static AoEGeometry.Area demoArea() {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) return null;
        double yaw = Math.toRadians(camera.getYRot());
        var facing = new AoEGeometry.Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        var hit = camera.pick(20, 1, false).getLocation();
        var origin = new AoEGeometry.Vec3(hit.x, hit.y + .08, hit.z);
        var bounds = camera.getBoundingBox();
        var source = new AoEGeometry.Box(new AoEGeometry.Vec3(bounds.minX, bounds.minY, bounds.minZ),
                new AoEGeometry.Vec3(bounds.maxX, bounds.maxY, bounds.maxZ));
        return preview.place(origin, facing, source, true);
    }

    private static boolean permitsRepeat(Spell spell) {
        return spell.effect().cantripBeams() || spell.id().equals("scorching_ray") || spell.id().equals("magic_missile");
    }

    private static boolean sendSpell(Targeting target) {
        return ClientPacketHandlers.send(Action.CAST, out -> {
            out.writeUtf(target.spell.id(), 64);
            out.writeVarInt(target.slot);
            PacketCodec.writeEnum(out, target.pool);
            PacketCodec.writeList(out, target.targets, (buffer, id) -> buffer.writeUUID(id));
            out.writeBoolean(target.origin != null);
            if (target.origin != null) PacketCodec.point(out, target.origin);
            out.writeBoolean(target.choice != null);
            if (target.choice != null) out.writeUtf(target.choice, 32);
        });
    }

    private static AoEGeometry.Vec3 aimPoint(Spell spell) {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) return null;
        double range = spell.rangeFeet() == 0 ? 16 : spell.rangeFeet() / 5.0;
        var hit = camera.pick(range, 1, false);
        var point = hit.getLocation();
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            var step = block.getDirection().getNormal();
            point = point.add(step.getX() * .051, step.getY() * .051, step.getZ() * .051);
        }
        return new AoEGeometry.Vec3(point.x, point.y, point.z);
    }

    static EntityHitResult aimedCreature(double range) {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null || range <= 0) return null;
        Vec3 from = camera.getEyePosition(1);
        Vec3 direction = camera.getViewVector(1).scale(range);
        Vec3 to = from.add(direction);
        double distance = from.distanceToSqr(camera.pick(range, 1, false).getLocation());
        return ProjectileUtil.getEntityHitResult(camera, from, to,
                camera.getBoundingBox().expandTowards(direction).inflate(1), entity -> entity.isPickable() && !entity.isSpectator(), distance);
    }

    public static Cell asCell(BlockPos position) { return new Cell(position.getX(), position.getY(), position.getZ()); }
}
