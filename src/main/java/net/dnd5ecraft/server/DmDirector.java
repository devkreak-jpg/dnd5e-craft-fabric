package net.dnd5ecraft.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.NpcBehaviorMode;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.rules.equipment.CoinPurse;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public final class DmDirector {
    public enum Tool {
        DRAGON_ROAR, SWORD_CLASH, DOOR_SLAM, THUNDERCLAP, EERIE_WHISPER, TAVERN_AMBIENCE, COMBAT_HORN,
        DAWN, NOON, DUSK, MIDNIGHT, CLEAR, RAIN, THUNDERSTORM, FOG, DARKNESS, SHAKE,
        COINS, LOOT, TELEPORT, SUMMON_PARTY, FREEZE, SPEAK, STATIONARY, WANDER, PATROL, DM_COMMANDED,
        NPC_ATTACK, NPC_EMOTE, NPC_END, SELECT_NPC
    }

    private record Selection(UUID npc, boolean speaking) {}
    private record Bubble(Display.TextDisplay display, DndNpcEntity speaker, long expires) {}

    private static final Map<UUID, Selection> SELECTED = new HashMap<>();
    private static final Map<ServerLevel, List<Bubble>> BUBBLES = new HashMap<>();

    private DmDirector() {}

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> SELECTED.remove(listener.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { SELECTED.clear(); BUBBLES.clear(); });
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            var bubbles = BUBBLES.get(level);
            if (bubbles == null) return;
            bubbles.removeIf(bubble -> {
                if (!bubble.speaker().isAlive() || !bubble.display().isAlive() || level.getGameTime() >= bubble.expires()) {
                    bubble.display().discard();
                    return true;
                }
                var npc = bubble.speaker();
                bubble.display().setPos(npc.getX(), npc.getY() + npc.getBbHeight() + .35, npc.getZ());
                return false;
            });
            if (bubbles.isEmpty()) BUBBLES.remove(level);
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, parameters) -> {
            var selection = SELECTED.get(player.getUUID());
            if (selection == null || !selection.speaking() || !player.hasPermissions(2)) return true;
            var npc = selected(player);
            if (npc == null) {
                SELECTED.remove(player.getUUID());
                return true;
            }
            String rendered = ModPackets.trim(message.signedContent(), 256);
            player.serverLevel().players().forEach(recipient -> recipient.sendSystemMessage(
                    Component.translatable("dnd-5e-craft.director.speech", npc.getDisplayName(), rendered)));
            bubble(player.serverLevel(), npc, Component.literal(rendered));
            return false;
        });
    }

    public static DndNpcEntity selected(ServerPlayer player) {
        var selection = SELECTED.get(player.getUUID());
        if (selection != null && player.serverLevel().getEntity(selection.npc()) instanceof DndNpcEntity npc
                && npc.isAlive() && player.distanceToSqr(npc) <= 4096) return npc;
        return null;
    }

    public static void select(ServerPlayer player, DndNpcEntity npc, boolean speak) {
        requireOperator(player);
        if (!npc.isAlive() || player.level() != npc.level() || player.distanceToSqr(npc) > 4096) {
            throw new IllegalArgumentException("NPC selection is out of range");
        }
        var previous = SELECTED.get(player.getUUID());
        boolean next = speak && !(previous != null && previous.npc().equals(npc.getUUID()) && previous.speaking());
        SELECTED.put(player.getUUID(), new Selection(npc.getUUID(), next));
        ModPackets.directorSelection(player, npc.getUUID());
        ModPackets.notice(player, Component.translatable(next ? "dnd-5e-craft.director.speaking" : "dnd-5e-craft.director.selected", npc.getDisplayName()));
    }

    public static void direct(ServerPlayer player, BlockPos pos, boolean waypoint) {
        requireOperator(player);
        var npc = requireNpc(player);
        if (waypoint && npc.behavior() == NpcBehaviorMode.PATROL) npc.addWaypoint(pos);
        else TacticalCombat.directNpc(player, npc, pos);
    }

    public static void run(ServerPlayer player, Tool tool, UUID target, int value) {
        requireOperator(player);
        var level = player.serverLevel();
        switch (tool) {
            case DRAGON_ROAR -> sound(player, SoundEvents.ENDER_DRAGON_GROWL);
            case SWORD_CLASH -> sound(player, SoundEvents.PLAYER_ATTACK_SWEEP);
            case DOOR_SLAM -> sound(player, SoundEvents.IRON_DOOR_CLOSE);
            case THUNDERCLAP -> sound(player, SoundEvents.LIGHTNING_BOLT_THUNDER);
            case EERIE_WHISPER -> sound(player, SoundEvents.WARDEN_AMBIENT);
            case TAVERN_AMBIENCE -> sound(player, SoundEvents.VILLAGER_CELEBRATE);
            case COMBAT_HORN -> sound(player, SoundEvents.RAID_HORN.value());
            case DAWN -> level.setDayTime(0);
            case NOON -> level.setDayTime(6000);
            case DUSK -> level.setDayTime(12000);
            case MIDNIGHT -> level.setDayTime(18000);
            case CLEAR -> level.setWeatherParameters(6000, 0, false, false);
            case RAIN -> level.setWeatherParameters(0, 6000, true, false);
            case THUNDERSTORM -> level.setWeatherParameters(0, 6000, true, true);
            case FOG -> level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.25, player.getZ(),
                    160, 4.0, 1.15, 4.0, .025);
            case DARKNESS -> party(player, target).forEach(member -> member.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100)));
            case SHAKE -> party(player, target).forEach(member -> ModPackets.sendDamage(member, 8));
            case COINS -> {
                if (value < 1 || value > 1000) throw new IllegalArgumentException("Choose 1 to 1000 gold");
                var members = party(player, target);
                members.forEach(member -> {
                    var sheet = PlayerSheets.getOrCreate(member);
                    var old = sheet.coins();
                    PlayerSheets.set(member, sheet.withCoins(new CoinPurse(old.copper(), old.silver(), old.electrum(),
                            Math.addExact(old.gold(), value), old.platinum())));
                    ModPackets.recordJournal(member, Component.translatable("dnd-5e-craft.ui.journal.gold", value));
                });
            }
            case LOOT -> {
                var items = ModItems.all();
                if (value < 0 || value >= items.size() || value < 7) throw new IllegalArgumentException("Choose campaign loot");
                party(player, target).forEach(member -> {
                    var loot = items.get(value).getDefaultInstance();
                    var name = loot.getHoverName().copy();
                    if (!member.addItem(loot)) member.drop(loot, false);
                    ModPackets.recordJournal(member, Component.translatable("dnd-5e-craft.ui.journal.loot", name));
                });
            }
            case TELEPORT -> {
                if (target == null) throw new IllegalArgumentException("Choose a player");
                var member = party(player, target).get(0);
                if (TacticalCombat.isLocked(player)) throw new IllegalStateException("Leave combat before teleporting");
                player.connection.teleport(member.getX(), member.getY(), member.getZ(), member.getYRot(), member.getXRot());
            }
            case SUMMON_PARTY -> {
                var members = party(player, target);
                for (var member : members) if (TacticalCombat.isLocked(member)) throw new IllegalStateException("Leave combat before teleporting");
                for (var member : members) if (member != player) {
                    member.connection.teleport(player.getX(), player.getY(), player.getZ(), member.getYRot(), member.getXRot());
                }
            }
            case FREEZE -> CinematicFreeze.toggle(player);
            case SELECT_NPC -> {
                if (target == null || !(level.getEntity(target) instanceof DndNpcEntity npc)) {
                    throw new IllegalArgumentException("Choose a campaign NPC");
                }
                select(player, npc, false);
            }
            case SPEAK -> select(player, requireNpc(player), true);
            case STATIONARY, WANDER, PATROL, DM_COMMANDED -> requireNpc(player).setBehavior(NpcBehaviorMode.valueOf(tool.name()), value);
            case NPC_EMOTE -> {
                var npc = requireNpc(player);
                npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                bubble(level, npc, Component.translatable("dnd-5e-craft.director.emote"));
            }
            case NPC_ATTACK, NPC_END -> {
                var npc = requireNpc(player);
                TacticalCombat.orderNpc(player, npc, target, tool == Tool.NPC_END);
            }
        }
    }

    private static DndNpcEntity requireNpc(ServerPlayer player) {
        var npc = selected(player);
        if (npc == null) throw new IllegalStateException("Select an NPC with the DM wand first");
        return npc;
    }

    private static List<ServerPlayer> party(ServerPlayer dm, UUID target) {
        var party = dm.serverLevel().players().stream().filter(player -> !player.isSpectator()
                && (target == null || player.getUUID().equals(target))).toList();
        if (party.isEmpty()) throw new IllegalArgumentException("No eligible party member in this dimension");
        return party;
    }

    public static void splitGold(ServerPlayer operator, int totalGold) {
        requireOperator(operator);
        if (totalGold < 1 || totalGold > 10_000) throw new IllegalArgumentException("Choose 1 to 10000 gold");
        var members = party(operator, null).stream().filter(player -> PlayerSheets.get(player).isPresent()).toList();
        if (members.isEmpty()) throw new IllegalStateException("No party members with character sheets");
        int each = totalGold / members.size();
        int remainder = totalGold % members.size();
        var credit = new CoinPurse(0, 0, 0, each, 0);
        // Compute every deposit before changing any character balance.
        List<net.dnd5ecraft.rules.character.CharacterSheetData> updated;
        try {
            updated = members.stream().map(member -> {
                var sheet = PlayerSheets.get(member).orElseThrow();
                return sheet.withCoins(sheet.coins().deposit(credit));
            }).toList();
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Gold would exceed a character's balance limit", overflow);
        }
        for (int i = 0; i < members.size(); i++) {
            if (each == 0) break;
            PlayerSheets.set(members.get(i), updated.get(i));
            ModPackets.recordJournal(members.get(i), Component.translatable("dnd-5e-craft.ui.journal.gold", each));
        }
        ModPackets.notice(operator, Component.translatable("dnd-5e-craft.ui.journal.split_result", each, members.size(), remainder));
    }

    private static void requireOperator(ServerPlayer player) {
        if (!player.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
    }

    private static void sound(ServerPlayer player, SoundEvent effect) {
        player.serverLevel().playSound(null, player.blockPosition(), effect, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    private static void bubble(ServerLevel level, DndNpcEntity npc, Component text) {
        var display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) return;
        var tag = new CompoundTag();
        tag.putString("text", Component.Serializer.toJson(text));
        tag.putString("billboard", "center");
        tag.putInt("line_width", 160);
        var point = npc.position().add(0, npc.getBbHeight() + .35, 0);
        display.load(tag);
        display.setPos(point.x, point.y, point.z);
        var bubbles = BUBBLES.computeIfAbsent(level, ignored -> new ArrayList<>());
        if (bubbles.size() >= 32) bubbles.remove(0).display().discard();
        if (level.addFreshEntity(display)) bubbles.add(new Bubble(display, npc, level.getGameTime() + 80));
    }
}
