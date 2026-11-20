package net.dnd5ecraft.server;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.CampaignCompanion;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.entity.ModEntities;
import net.dnd5ecraft.item.CampaignFocusItem.Focus;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public final class CampaignActions {
    public enum Action {
        CANNON_FIRE, CANNON_MODE, CANNON_STANCE, ECHO_MANIFEST, ECHO_MOVE, ECHO_SWAP, ECHO_ATTACK,
        UNLEASH_INCARNATION, FOLLOW_UP_FIST, BURYING_HANDS, RECKLESS_ATTACK, GORING_RUSH, HAMMERING_HORNS,
        CHAOS_QUIP, SCENE_CHANGE, AUDIENCE_LAUGHTER, CAST_OF_THE_DIE, MISE_EN_SCENE, CANNON_SUMMON, DIRECTOR_SCENE_CHANGE
    }

    private CampaignActions() {}

    public static boolean turnBound(Action action) {
        return action != Action.AUDIENCE_LAUGHTER && action != Action.DIRECTOR_SCENE_CHANGE;
    }

    public static void focus(ServerPlayer player, Focus focus, BlockPos pos) {
        switch (focus) {
            case CANNON -> {
                var cannon = companion(player, EldritchCannonEntity.class);
                if (cannon == null) perform(player, Action.CANNON_SUMMON, null, pos, 0);
                else if (player.isShiftKeyDown()) perform(player, Action.CANNON_MODE, null, null, 0);
                else perform(player, Action.CANNON_FIRE, null, null, 0);
            }
            case ECHO -> perform(player, companion(player, EchoAvatarEntity.class) == null
                    ? Action.ECHO_MANIFEST : player.isShiftKeyDown() ? Action.ECHO_SWAP : Action.ECHO_MOVE, null, pos, 0);
            case CHAOS -> {
                requireHero(player, CampaignHero.HARLEQUIN);
                CampaignMagic.visual(player.serverLevel(), player.position().add(0, 1.4, 0), ParticleTypes.WITCH,
                        SoundEvents.NOTE_BLOCK_BELL.value(), 18);
            }
        }
    }

    public static void perform(ServerPlayer player, Action action, UUID target, BlockPos point, int option) {
        if (action == Action.DIRECTOR_SCENE_CHANGE) {
            TacticalCombat.directScenePair(player, target, point);
            return;
        }
        if (player.isSpectator() || PlayerSheets.unconscious(player)) throw new IllegalStateException("A conscious character is required");
        TacticalCombat.requireUnfrozen(player);
        var sheet = PlayerSheets.get(player).orElseThrow(() -> new IllegalStateException("A character is required"));
        switch (action) {
            case CAST_OF_THE_DIE -> {
                PlayerRules.requireDowntime(player);
                if (sheet.remaining(ClassFeature.CAST_OF_DIE) == 0) throw new IllegalStateException("Cast of the Die is unavailable");
                var ability = Ability.values()[dice(player, 1, 6) - 1];
                PlayerSheets.set(player, sheet.castOfTheDie(ability));
                player.displayClientMessage(Component.translatable("dnd-5e-craft.ui.campaign.role_roll", Component.translatable(
                        "dnd-5e-craft.ui.ability." + ability.name().toLowerCase(java.util.Locale.ROOT))), true);
            }
            case CANNON_SUMMON -> {
                if (companion(player, EldritchCannonEntity.class) != null) throw new IllegalStateException("A cannon already exists");
                summonCannon(player, point);
            }
            case CANNON_FIRE -> fire(player, requireCompanion(player, EldritchCannonEntity.class, CampaignHero.FINNEAS), target);
            case CANNON_MODE -> {
                var cannon = requireCompanion(player, EldritchCannonEntity.class, CampaignHero.FINNEAS);
                if (TacticalCombat.isLocked(player)) TacticalCombat.require(player).campaignTurn(player.getUUID(), TacticalCombat.now());
                var values = EldritchCannonEntity.Mode.values();
                cannon.mode(values[(cannon.mode().ordinal() + 1) % values.length]);
                player.displayClientMessage(Component.translatable("dnd-5e-craft.cannon." + cannon.mode().name().toLowerCase(java.util.Locale.ROOT)), true);
            }
            case CANNON_STANCE -> {
                var cannon = requireCompanion(player, EldritchCannonEntity.class, CampaignHero.FINNEAS);
                if (TacticalCombat.isLocked(player)) TacticalCombat.require(player).campaignTurn(player.getUUID(), TacticalCombat.now());
                cannon.perched(!cannon.perched());
            }
            case ECHO_MANIFEST -> {
                requireHero(player, CampaignHero.MINOTAUR);
                if (companion(player, EchoAvatarEntity.class) != null) throw new IllegalStateException("An echo already exists");
                var echo = ModEntities.ECHO_AVATAR.create(player.serverLevel());
                if (echo == null) throw new IllegalStateException("Echo could not be created");
                echo.getAttribute(Attributes.ARMOR).setBaseValue(EchoAvatarEntity.armorAttribute(ProficiencyLevel.bonusAtLevel(sheet.level())));
                echo.setItemSlot(EquipmentSlot.MAINHAND, player.getMainHandItem().copy());
                echo.setDropChance(EquipmentSlot.MAINHAND, 0);
                if (!prepare(player, echo, point, 3)) return;
                spendEchoBonus(player);
                player.serverLevel().addFreshEntity(echo);
                smoke(player, echo.position());
            }
            case ECHO_MOVE -> {
                var echo = requireCompanion(player, EchoAvatarEntity.class, CampaignHero.MINOTAUR);
                var destination = point == null ? companionGround(player, echo, 3) : point;
                if (destination == null || echo.position().distanceToSqr(Vec3.atBottomCenterOf(destination)) > 36
                        || !canPlace(player, echo, destination)) {
                    groundHint(player);
                    return;
                }
                if (TacticalCombat.isLocked(player)) TacticalCombat.require(player).campaignEchoMove(player.getUUID(), TacticalCombat.now());
                place(player, echo, destination);
            }
            case ECHO_SWAP -> {
                var echo = requireCompanion(player, EchoAvatarEntity.class, CampaignHero.MINOTAUR);
                if (!CampaignRules.echoInRange(player.distanceTo(echo))) throw new IllegalStateException("Echo is out of range");
                var start = player.position();
                var end = echo.position();
                if (!player.level().noCollision(player, player.getBoundingBox().move(end.subtract(start)))) {
                    throw new IllegalArgumentException("Echo destination is blocked");
                }
                if (TacticalCombat.isLocked(player)) TacticalCombat.swapEcho(player, echo.blockPosition());
                else {
                    int speed = new net.dnd5ecraft.rules.conditions.ConditionEffectApplier(
                            sheet.conditions().stream().map(condition -> condition.type()).collect(java.util.stream.Collectors.toSet()),
                            sheet.exhaustion()).speedFeet(sheet.race().walkingFeet() - TacticalCombat.armorFormula(player, sheet)
                                    .speedPenaltyFeet(sheet.score(Ability.STRENGTH), sheet.race() == net.dnd5ecraft.rules.character.RaceType.DWARF));
                    int cost = CampaignRules.echoSwapCostFeet();
                    if (speed < cost) throw new IllegalStateException("Echo swap needs fifteen feet of movement");
                    spendEchoBonus(player);
                    CampaignPhysics.chargeEchoMovement(player, cost / (double) speed);
                    player.connection.teleport(end.x, end.y, end.z, player.getYRot(), player.getXRot());
                }
                echo.setPos(start);
                smoke(player, start);
                smoke(player, end);
            }
            case ECHO_ATTACK, UNLEASH_INCARNATION -> {
                var echo = requireCompanion(player, EchoAvatarEntity.class, CampaignHero.MINOTAUR);
                var enemy = target(player, target, echo.position(), 3);
                TacticalCombat.require(player).echoAttack(player.getUUID(), enemy.getUUID(), TacticalCombat.cell(echo.blockPosition()),
                        action == Action.UNLEASH_INCARNATION, TacticalCombat.now());
            }
            default -> {
                TacticalCombat.require(player).campaignManeuver(player.getUUID(), action, target,
                        point == null ? null : TacticalCombat.cell(point), option, TacticalCombat.now());
                if (action == Action.SCENE_CHANGE) {
                    for (var operator : player.serverLevel().players()) {
                        if (operator != player && operator.hasPermissions(2)) net.dnd5ecraft.network.ModPackets.notice(operator,
                                Component.translatable("dnd-5e-craft.ui.campaign.scene_offer", player.getDisplayName()));
                    }
                }
            }
        }
    }

    private static void summonCannon(ServerPlayer player, BlockPos point) {
        var sheet = requireHero(player, CampaignHero.FINNEAS);
        var cannon = ModEntities.ELDRITCH_CANNON.create(player.serverLevel());
        if (cannon == null) throw new IllegalStateException("Cannon could not be created");
        int hp = CampaignRules.cannonHealth(sheet.classLevels().getOrDefault(ClassType.ARTIFICER, 0));
        cannon.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
        cannon.setHealth(hp);
        if (!prepare(player, cannon, point, 3)) return;
        CharacterSheetDataPayment payment = payment(player);
        consume(player, Resource.ACTION);
        if (TacticalCombat.isLocked(player)) {
            TacticalCombat.require(player).payCannon(player.getUUID());
        } else {
            PlayerSheets.set(player, payment.sheet());
        }
        player.serverLevel().addFreshEntity(cannon);
        player.serverLevel().sendParticles(ParticleTypes.WAX_ON, cannon.getX(), cannon.getY() + .3, cannon.getZ(), 24, .3, .3, .3, .1);
    }

    private record CharacterSheetDataPayment(net.dnd5ecraft.rules.character.CharacterSheetData sheet) {}

    private static CharacterSheetDataPayment payment(ServerPlayer player) {
        var sheet = PlayerSheets.get(player).orElseThrow();
        if (TacticalCombat.isLocked(player)) {
            TacticalCombat.require(player).validateCannonPayment(player.getUUID());
            return new CharacterSheetDataPayment(sheet);
        }
        if (sheet.remaining(ClassFeature.ELDRITCH_CANNON) > 0) return new CharacterSheetDataPayment(sheet.spend(ClassFeature.ELDRITCH_CANNON, 1));
        var slots = new SpellSlotTable.Ledger(sheet.classLevels(), sheet.resources().slots());
        if (!slots.spend(Pool.REGULAR, 1)) throw new IllegalStateException("A first-level slot is required");
        return new CharacterSheetDataPayment(sheet.withResources(sheet.resources().withSlots(slots.spent())));
    }

    private static void fire(ServerPlayer player, EldritchCannonEntity cannon, UUID target) {
        var sheet = PlayerSheets.get(player).orElseThrow();
        int mod = Ability.modifier(sheet.score(Ability.INTELLIGENCE));
        int proficiency = ProficiencyLevel.bonusAtLevel(sheet.level());
        int dc = CampaignRules.saveDc(proficiency, mod);
        var center = cannon.position().add(0, .3, 0);
        var facing = player.getLookAngle();
        LivingEntity shot = cannon.mode() == EldritchCannonEntity.Mode.FORCE_BALLISTA ? target(player, target, center, 24) : null;
        if (shot != null && !cannon.hasLineOfSight(shot)) throw new IllegalArgumentException("Total cover blocks the ballista");
        if (!TacticalCombat.isLocked(player) && cannon.recoil() > 0) throw new IllegalStateException("Wait for the cannon to recover");
        consume(player, Resource.BONUS_ACTION);
        Vec3 aim = shot == null ? facing : shot.position().subtract(center);
        cannon.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        cannon.fire();
        switch (cannon.mode()) {
            case FORCE_BALLISTA -> {
                var end = shot.position().add(0, shot.getBbHeight() / 2, 0);
                beam(player, center, end, ParticleTypes.ELECTRIC_SPARK);
                var outcome = TacticalCombat.campaignAttack(player, shot, proficiency + mod);
                if (outcome != net.dnd5ecraft.rules.equipment.DamagePipeline.AttackOutcome.MISS) {
                    boolean critical = outcome == net.dnd5ecraft.rules.equipment.DamagePipeline.AttackOutcome.CRITICAL;
                    TacticalCombat.campaignDamage(player, shot, DamageType5e.FORCE, dice(player, critical ? 4 : 2, 8), critical);
                    TacticalCombat.campaignPush(player, shot, 1, center);
                }
                player.serverLevel().playSound(null, cannon.blockPosition(), SoundEvents.GUARDIAN_ATTACK, SoundSource.PLAYERS, .7f, 1.25f);
            }
            case FLAMETHROWER -> {
                var targets = nearby(player, cannon, 3).stream().filter(enemy -> enemy != player && !TacticalCombat.allied(player, enemy))
                        .filter(enemy -> enemy.position().subtract(center).normalize().dot(facing) >= .707 && cannon.hasLineOfSight(enemy)).toList();
                int damage = dice(player, 2, 8);
                for (var enemy : targets) TacticalCombat.campaignDamage(player, enemy, DamageType5e.FIRE,
                        TacticalCombat.campaignSave(player, enemy, Ability.DEXTERITY, dc) ? damage / 2 : damage);
                for (int i = -4; i <= 4; i++) {
                    for (int elevation = -2; elevation <= 2; elevation++) {
                        var end = center.add(facing.yRot(i * .13f).xRot(elevation * .13f).scale(3));
                        beam(player, center, end, (i + elevation) % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME);
                    }
                }
                player.serverLevel().playSound(null, cannon.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, .8f, .82f);
            }
            case PROTECTOR -> {
                int temporary = Math.max(1, dice(player, 1, 8) + mod);
                for (var ally : nearby(player, cannon, 2)) if (TacticalCombat.allied(player, ally)) TacticalCombat.campaignTempHp(ally, temporary);
                for (int ring = 0; ring < 4; ring++) {
                    double tilt = ring * Math.PI / 8;
                    double radius = Math.cos(tilt) * 2;
                    for (int i = 0; i < 18; i++) {
                        double angle = i * Math.PI / 9;
                        player.serverLevel().sendParticles(ParticleTypes.WAX_ON, center.x + Math.cos(angle) * radius,
                                center.y + Math.sin(tilt) * 2, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
                    }
                }
                player.serverLevel().playSound(null, cannon.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, .9f, 1);
            }
        }
    }

    private static List<LivingEntity> nearby(ServerPlayer player, CampaignCompanion source, int range) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, source.getBoundingBox().inflate(range),
                other -> other.isAlive() && !(other instanceof CampaignCompanion) && source.distanceToSqr(other) <= range * range);
    }

    private static LivingEntity target(ServerPlayer player, UUID id, Vec3 origin, double range) {
        if (id != null) {
            if (player.serverLevel().getEntity(id) instanceof LivingEntity entity && entity.isAlive()
                    && !(entity instanceof CampaignCompanion) && entity.position().distanceToSqr(origin) <= range * range && !TacticalCombat.allied(player, entity)) return entity;
            throw new IllegalArgumentException("Target is out of range");
        }
        var look = player.getViewVector(1);
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, companionTargetBounds(origin, range),
                entity -> entity.isAlive() && !(entity instanceof CampaignCompanion) && !TacticalCombat.allied(player, entity)
                        && entity.position().distanceToSqr(origin) <= range * range
                        && entity.getEyePosition().subtract(origin).normalize().dot(look) > .96)
                .stream().min(Comparator.comparingDouble(entity -> entity.distanceToSqr(origin))).orElseThrow(() -> new IllegalArgumentException("Aim at an enemy"));
    }

    static net.minecraft.world.phys.AABB companionTargetBounds(Vec3 origin, double range) {
        return new net.minecraft.world.phys.AABB(origin, origin).inflate(range);
    }

    private static void spendEchoBonus(ServerPlayer player) {
        if (TacticalCombat.isLocked(player)) {
            consume(player, Resource.BONUS_ACTION);
        } else {
            PlayerRules.requireDowntime(player);
            if (player.getCooldowns().isOnCooldown(ModItems.ECHO_SIGIL)) {
                throw new IllegalStateException("Echo bonus action is recovering");
            }
            player.getCooldowns().addCooldown(ModItems.ECHO_SIGIL, 120);
        }
    }

    private static void consume(ServerPlayer player, Resource resource) {
        if (TacticalCombat.isLocked(player)) TacticalCombat.require(player).campaignSpend(player.getUUID(), resource, TacticalCombat.now());
        else PlayerRules.requireDowntime(player);
    }

    public static <T extends CampaignCompanion> T companion(ServerPlayer player, Class<T> kind) {
        return findCompanion(player.serverLevel().getAllEntities(), player.getUUID(), kind);
    }

    static <T extends CampaignCompanion> T findCompanion(Iterable<net.minecraft.world.entity.Entity> entities, UUID owner, Class<T> kind) {
        for (var entity : entities) {
            if (kind.isInstance(entity) && entity.isAlive()) {
                var candidate = kind.cast(entity);
                if (candidate.ownerId().filter(owner::equals).isPresent()) return candidate;
            }
        }
        return null;
    }

    private static <T extends CampaignCompanion> T requireCompanion(ServerPlayer player, Class<T> kind, CampaignHero hero) {
        requireHero(player, hero);
        var found = companion(player, kind);
        if (found == null) throw new IllegalStateException("Summon a companion first");
        return found;
    }

    private static net.dnd5ecraft.rules.character.CharacterSheetData requireHero(ServerPlayer player, CampaignHero hero) {
        return PlayerSheets.get(player).filter(hero::matches).orElseThrow(() -> new IllegalStateException("This character lacks the campaign feature"));
    }

    private static boolean prepare(ServerPlayer player, CampaignCompanion entity, BlockPos point, int range) {
        if (entity == null) throw new IllegalStateException("Companion could not be created");
        var pos = point == null ? companionGround(player, entity, range) : point;
        if (pos == null || Vec3.atBottomCenterOf(pos).distanceToSqr(player.position()) > range * range
                || !canPlace(player, entity, pos)) {
            groundHint(player);
            return false;
        }
        entity.bind(player);
        place(player, entity, pos);
        return true;
    }

    public static BlockPos companionGround(ServerPlayer player, CampaignCompanion entity, int range) {
        var facing = player.getLookAngle().multiply(1, 0, 1).normalize();
        if (facing.lengthSqr() < 1e-8) facing = Vec3.atLowerCornerOf(player.getDirection().getNormal());
        for (int step = range; step >= 1; step--) {
            var column = BlockPos.containing(player.position().add(facing.scale(step)));
            for (int offset : new int[]{0, -1, 1, -2, -3}) {
                var spot = column.offset(0, offset, 0);
                if (Vec3.atBottomCenterOf(spot).distanceToSqr(player.position()) <= range * range
                        && canPlace(player, entity, spot)) return spot;
            }
        }
        return null;
    }

    private static boolean canPlace(ServerPlayer player, CampaignCompanion entity, BlockPos pos) {
        var level = player.serverLevel();
        return level.isLoaded(pos) && !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
                && level.noCollision(entity, entity.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(entity.position())));
    }

    private static void groundHint(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("dnd-5e-craft.ui.campaign.companion_ground"), true);
    }

    private static void place(ServerPlayer player, CampaignCompanion entity, BlockPos pos) {
        var center = Vec3.atBottomCenterOf(pos);
        entity.moveTo(center.x, center.y, center.z, player.getYRot(), 0);
    }

    public static int dice(ServerPlayer player, int count, int sides) {
        int sum = 0;
        for (int i = 0; i < count; i++) sum += PlayerRules.dice(player).applyAsInt(sides);
        return sum;
    }

    public static void smoke(ServerPlayer player, Vec3 pos) {
        player.serverLevel().sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + .65, pos.z, 14, .35, .4, .35, .025);
        player.serverLevel().playSound(null, BlockPos.containing(pos), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, .7f, .8f);
    }

    private static void beam(ServerPlayer player, Vec3 from, Vec3 to, net.minecraft.core.particles.ParticleOptions particle) {
        for (int i = 0; i <= 18; i++) {
            Vec3 p = from.lerp(to, i / 18.0);
            player.serverLevel().sendParticles(particle, p.x, p.y, p.z, 1, .025, .025, .025, 0);
        }
    }
}
