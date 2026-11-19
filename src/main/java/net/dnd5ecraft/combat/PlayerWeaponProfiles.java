package net.dnd5ecraft.combat;

import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.item.DndWeaponItem;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.TabletopRolls.Weapon;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.WeaponProperty;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class PlayerWeaponProfiles {
    private PlayerWeaponProfiles() {}

    public static ItemStack equipped(Player player, Weapon slot) {
        return switch (slot) {
            case MAIN_HAND -> player.getMainHandItem();
            case OFF_HAND -> player.getOffhandItem();
            case UNARMED -> ItemStack.EMPTY;
        };
    }

    public static boolean available(Player player, Weapon slot) {
        if (slot == Weapon.UNARMED) return true;
        var stack = equipped(player, slot);
        return !DndWeaponItem.broken(stack) && stats(stack)
                .filter(stats -> !stats.has(WeaponProperty.TWO_HANDED)
                        || slot == Weapon.MAIN_HAND && player.getOffhandItem().isEmpty()).isPresent();
    }

    public static CombatantEntry.Attack tabletop(Player player, CharacterSheetData sheet, Weapon slot) {
        var attack = attack(equipped(player, slot), sheet, slot == Weapon.MAIN_HAND && player.getOffhandItem().isEmpty());
        if (slot != Weapon.OFF_HAND) return attack;
        var damage = attack.damage().get(0);
        int ability = attack.attackBonus() - net.dnd5ecraft.rules.ability.ProficiencyLevel.bonusAtLevel(sheet.level());
        var offhand = new DamageRoll(damage.type(), damage.dice(), damage.modifier() - Math.max(0, ability));
        return new CombatantEntry.Attack(attack.name(), attack.attackBonus(), attack.reachFeet(), attack.normalRangeFeet(),
                attack.longRangeFeet(), List.of(offhand), attack.finesse(), attack.light(), attack.heavy(), attack.strengthBased(), attack.weaponKey());
    }

    public static boolean canPower(CharacterSheetData sheet, CombatantEntry.Attack profile) {
        return profile.melee() ? profile.heavy() && sheet.feats().contains(FeatType.GREAT_WEAPON_MASTER)
                : sheet.feats().contains(FeatType.SHARPSHOOTER);
    }

    public static Component label(Player player, Weapon slot) {
        return slot == Weapon.UNARMED ? Component.translatable("dnd-5e-craft.ui.tabletop.weapon.unarmed")
                : equipped(player, slot).getHoverName();
    }

    public static Optional<WeaponStatsRegistry.Stats> stats(ItemStack stack) {
        if (stack.getItem() instanceof DndWeaponItem dnd) return Optional.of(dnd.stats());
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return WeaponStatsRegistry.findItem(id.getNamespace(), id.getPath());
    }

    public static boolean shield(ItemStack stack) { return stack.getItem() instanceof net.minecraft.world.item.ShieldItem; }

    public static CombatantEntry.Attack attack(ItemStack stack, CharacterSheetData sheet, boolean twoHanded) {
        return attack(stack, sheet, twoHanded, false);
    }

    public static Optional<CombatantEntry.Attack> thrown(ItemStack stack, CharacterSheetData sheet) {
        return stats(stack).filter(item -> item.has(WeaponProperty.THROWN) && !item.has(WeaponProperty.RANGED))
                .map(item -> attack(stack, sheet, false, true));
    }

    private static CombatantEntry.Attack attack(ItemStack stack, CharacterSheetData sheet, boolean twoHanded, boolean throwWeapon) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var weapon = stats(stack);
        boolean worldbreaker = CampaignHero.ALEXANDER.matches(sheet);
        int strength = sheet.score(Ability.STRENGTH);
        Ability ability = weapon.map(stats -> stats.preferredAttackAbility(strength, sheet.score(Ability.DEXTERITY))).orElse(Ability.STRENGTH);
        int mod = Ability.modifier(sheet.score(ability));
        int enchant = stack.getItem() instanceof DndWeaponItem item ? item.enhancement() : 0;
        var dice = weapon.map(stats -> stats.damageDice(twoHanded)).orElseGet(() -> worldbreaker
                ? new Dice(1, CampaignRules.worldbreakerDie(sheet.classLevels().getOrDefault(net.dnd5ecraft.rules.character.ClassType.BARBARIAN, 0)))
                : sheet.race() == net.dnd5ecraft.rules.character.RaceType.MINOTAUR ? new Dice(1, 6) : new Dice(0, 0));
        var type = weapon.flatMap(WeaponStatsRegistry.Stats::damageType).orElse(weapon.isEmpty()
                && sheet.race() == net.dnd5ecraft.rules.character.RaceType.MINOTAUR ? DamageType5e.PIERCING : DamageType5e.BLUDGEONING);
        int flat = weapon.isEmpty() ? mod + (dice.count() == 0 ? 1 : 0)
                : weapon.orElseThrow().dealsDamage() ? mod + weapon.orElseThrow().flatDamage() + enchant : 0;
        boolean ranged = throwWeapon || weapon.filter(stats -> stats.has(WeaponProperty.RANGED)).isPresent();
        boolean mightyThrow = worldbreaker && ranged && weapon.filter(stats -> stats.has(WeaponProperty.THROWN)).isPresent()
                && !id.getPath().equals("net");
        if (mightyThrow) {
            dice = new Dice(1, CampaignRules.worldbreakerDie(sheet.classLevels().getOrDefault(net.dnd5ecraft.rules.character.ClassType.BARBARIAN, 0)));
            flat = Ability.modifier(strength) + enchant;
            mod = Ability.modifier(strength);
            ability = Ability.STRENGTH;
        }
        String key = weapon.isPresent() ? id.toString() : worldbreaker ? "fist"
                : sheet.race() == net.dnd5ecraft.rules.character.RaceType.MINOTAUR ? "horns" : "unarmed";
        String label = weapon.isPresent() ? stack.getHoverName().getString() : key.equals("horns") ? "Horns" : "Unarmed strike";
        if (throwWeapon) label = Component.translatable("dnd-5e-craft.weapon.thrown_profile", stack.getHoverName()).getString();
        return new CombatantEntry.Attack(label,
                mod + enchant + net.dnd5ecraft.rules.ability.ProficiencyLevel.bonusAtLevel(sheet.level()),
                ranged ? 0 : weapon.map(WeaponStatsRegistry.Stats::meleeReachFeet).orElse(5),
                ranged ? weapon.orElseThrow().normalRangeFeet() * (mightyThrow ? 2 : 1) : 0,
                ranged ? weapon.orElseThrow().longRangeFeet() * (mightyThrow ? 2 : 1) : 0,
                List.of(new DamageRoll(type, dice, flat)), weapon.filter(stats -> stats.has(WeaponProperty.FINESSE)).isPresent(),
                weapon.filter(stats -> stats.has(WeaponProperty.LIGHT)).isPresent(),
                weapon.filter(stats -> stats.has(WeaponProperty.HEAVY)).isPresent(), ability == Ability.STRENGTH, key);
    }

    public static CombatantEntry.Attack npc(ItemStack stack, CombatantEntry.Stats base) {
        var item = stats(stack).orElseThrow();
        Ability ability = item.preferredAttackAbility(base.abilityScores().getOrDefault(Ability.STRENGTH, 10),
                base.abilityScores().getOrDefault(Ability.DEXTERITY, 10));
        int mod = base.abilityModifier(ability);
        boolean range = item.has(WeaponProperty.RANGED);
        return new CombatantEntry.Attack(stack.getHoverName().getString(), mod + base.proficiencyBonus(),
                range ? 0 : item.meleeReachFeet(), range ? item.normalRangeFeet() : 0, range ? item.longRangeFeet() : 0,
                List.of(new DamageRoll(item.damageType().orElse(DamageType5e.BLUDGEONING), item.damage(),
                        item.dealsDamage() ? mod + item.flatDamage() : 0)), item.has(WeaponProperty.FINESSE),
                item.has(WeaponProperty.LIGHT), item.has(WeaponProperty.HEAVY), ability == Ability.STRENGTH,
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }
}
