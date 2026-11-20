package net.dnd5ecraft.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.spells.SpellAccess;
import net.dnd5ecraft.rules.spells.SpellSlotTable;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class CampaignMagic {
    private record Fog(UUID caster, Vec3 center, long expiry) {}
    private static final Map<ServerLevel, List<Fog>> CLOUDS = new HashMap<>();

    private CampaignMagic() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CLOUDS.clear());
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            var clouds = CLOUDS.get(level);
            if (clouds != null) {
                clouds.removeIf(fog -> level.getGameTime() >= fog.expiry() || level.getEntity(fog.caster()) == null);
                if (level.getGameTime() % 10 == 0) clouds.forEach(cloud -> fogVisual(level, cloud.center()));
                if (clouds.isEmpty()) CLOUDS.remove(level);
            }
            for (var player : level.players()) {
                var character = (PlayerCharacter) player;
                if (character.dnd5e$disguise() != 0
                        && character.dnd5e$disguiseExpiresAt() <= level.getServer().overworld().getGameTime()) {
                    character.dnd5e$disguise(0);
                    character.dnd5e$disguiseExpiresAt(0);
                }
            }
        });
    }

    public static boolean obscures(ServerLevel level, BlockPos pos) {
        var clouds = CLOUDS.get(level);
        return clouds != null && clouds.stream().anyMatch(fog -> fog.center().distanceToSqr(pos.getCenter()) <= 16);
    }

    public static void cast(ServerPlayer player, String id, UUID target, BlockPos point, String phrase) {
        if (!player.isAlive() || player.isSpectator() || PlayerSheets.unconscious(player)) throw new IllegalStateException("A conscious caster is required");
        TacticalCombat.requireUnfrozen(player);
        var sheet = PlayerSheets.get(player).orElseThrow(() -> new IllegalStateException("A character is required"));
        if (!SpellAccess.available(sheet).containsKey(id)) throw new IllegalStateException("This spell is unavailable");
        var level = player.serverLevel();
        switch (id) {
            case "mending" -> {
                LivingEntity receiver = target == null ? player : level.getEntity(target) instanceof LivingEntity creature ? creature : null;
                if (receiver == null || receiver.distanceToSqr(player) > 6.25 || receiver != player && !player.hasLineOfSight(receiver)) {
                    throw new IllegalArgumentException("Mending requires a nearby weapon or cannon");
                }
                ItemStack stack = receiver.getMainHandItem();
                if (!net.dnd5ecraft.item.DndWeaponItem.broken(stack)) stack = receiver.getOffhandItem();
                boolean repair = net.dnd5ecraft.item.DndWeaponItem.broken(stack);
                if (!(receiver instanceof EldritchCannonEntity) && !repair) {
                    var cannon = CampaignActions.companion(player, EldritchCannonEntity.class);
                    if (cannon == null || cannon.distanceToSqr(player) > 6.25) throw new IllegalStateException("Nothing needs repair");
                    receiver = cannon;
                }
                pay(player, 0, null);
                if (repair) stack.removeTagKey("DndBroken");
                else {
                    float before = receiver.getHealth();
                    receiver.heal(CampaignActions.dice(player, 2, 6));
                    net.dnd5ecraft.network.StudioEffects.healed(receiver, Math.round(receiver.getHealth() - before));
                }
                visual(level, receiver.position().add(0, .6, 0), ParticleTypes.WAX_ON, SoundEvents.ANVIL_USE, 18);
            }
            case "message" -> {
                if (!(level.getEntity(target) instanceof ServerPlayer recipient) || recipient.distanceToSqr(player) > 576
                        || phrase.isBlank() || phrase.length() > 160 || phrase.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Choose a recipient within twenty-four blocks and write a message");
                }
                pay(player, 0, null);
                var whisper = Component.translatable("dnd-5e-craft.magic.message", player.getDisplayName(), recipient.getDisplayName(), phrase);
                player.sendSystemMessage(whisper);
                if (recipient != player) recipient.sendSystemMessage(whisper);
                recipient.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, .35f, 1.4f);
            }
            case "prestidigitation" -> {
                if (point != null && player.position().distanceToSqr(point.getCenter()) > 9) {
                    throw new IllegalArgumentException("The effect is out of reach");
                }
                pay(player, 0, null);
                visual(level, point == null ? player.getEyePosition().add(player.getLookAngle()) : point.getCenter(),
                        ParticleTypes.ENCHANT, SoundEvents.AMETHYST_BLOCK_CHIME, 24);
            }
            case "disguise_self" -> {
                pay(player, 1, null);
                var character = (PlayerCharacter) player;
                character.dnd5e$disguise(character.dnd5e$disguise() % 3 + 1);
                character.dnd5e$disguiseExpiresAt(level.getServer().overworld().getGameTime() + 12_000);
                visual(level, player.position().add(0, .85, 0), ParticleTypes.WITCH, SoundEvents.ILLUSIONER_MIRROR_MOVE, 32);
            }
            case "fog_cloud" -> {
                if (point == null || !level.isLoaded(point) || player.position().distanceToSqr(point.getCenter()) > 24 * 24) {
                    throw new IllegalArgumentException("Choose a fog origin within twenty-four blocks");
                }
                var racial = sheet.race() == RaceType.TRITON ? ClassFeature.TRITON_FOG : null;
                if (TacticalCombat.isLocked(player)) {
                    if (racial == null) throw new IllegalStateException("Use the spellbook to cast this spell");
                    TacticalCombat.require(player).castTritonFog(player.getUUID(), TacticalCombat.cell(point), TacticalCombat.now());
                    return;
                }
                pay(player, 1, racial);
                var clouds = CLOUDS.computeIfAbsent(level, ignored -> new ArrayList<>());
                clouds.removeIf(cloud -> cloud.caster().equals(player.getUUID()));
                clouds.add(new Fog(player.getUUID(), point.getCenter(), level.getGameTime() + 1200));
                fogVisual(level, point.getCenter());
            }
            default -> throw new IllegalArgumentException("Use the spellbook to cast this spell");
        }
        if (!id.equals("message")) net.dnd5ecraft.network.StudioEffects.spell(player, id);
    }

    private static void pay(ServerPlayer player, int slot, ClassFeature free) {
        if (TacticalCombat.isLocked(player)) {
            TacticalCombat.require(player).payCampaignSpell(player.getUUID(), slot, free, TacticalCombat.now());
            return;
        }
        PlayerRules.requireDowntime(player);
        if (player.getCooldowns().isOnCooldown(net.dnd5ecraft.item.ModItems.CHARACTER_FOLIO)) {
            throw new IllegalStateException("Wait before casting another spell");
        }
        var sheet = PlayerSheets.get(player).orElseThrow();
        if (slot > 0) {
            if (free != null && sheet.remaining(free) > 0) PlayerSheets.set(player, sheet.spend(free, 1));
            else {
                var slots = new SpellSlotTable.Ledger(sheet.classLevels(), sheet.resources().slots());
                if (!slots.spend(Pool.REGULAR, slot)) throw new IllegalStateException("No spell slots remain");
                PlayerSheets.set(player, sheet.withResources(sheet.resources().withSlots(slots.spent())));
            }
        }
        player.getCooldowns().addCooldown(net.dnd5ecraft.item.ModItems.CHARACTER_FOLIO, 12);
    }

    static void visual(ServerLevel level, Vec3 p, ParticleOptions particle, SoundEvent sound, int count) {
        level.sendParticles(particle, p.x, p.y, p.z, count, .38, .38, .38, .07);
        level.playSound(null, BlockPos.containing(p), sound, SoundSource.PLAYERS, .57f, 1.12f);
    }

    static void fogVisual(ServerLevel level, Vec3 p) {
        level.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 100, 1.65, 1.65, 1.65, .016);
    }
}
