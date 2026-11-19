package net.dnd5ecraft.combat;

import java.util.List;
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
        var id = BuiltInRegistries.ITEM.getKey(equipped(player, slot).getItem());
        return WeaponStatsRegistry.findItem(id.getNamespace(), id.getPath())
                .filter(WeaponStatsRegistry.Stats::dealsDamage)
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
                attack.longRangeFeet(), List.of(offhand), attack.finesse(), attack.light(), attack.heavy(), attack.strengthBased());
    }

    public static boolean canPower(CharacterSheetData sheet, CombatantEntry.Attack profile) {
        return profile.melee() ? profile.heavy() && sheet.feats().contains(FeatType.GREAT_WEAPON_MASTER)
                : sheet.feats().contains(FeatType.SHARPSHOOTER);
    }

    public static Component label(Player player, Weapon slot) {
        return slot == Weapon.UNARMED ? Component.translatable("dnd-5e-craft.ui.tabletop.weapon.unarmed")
                : equipped(player, slot).getHoverName();
    }

    public static CombatantEntry.Attack attack(ItemStack stack, CharacterSheetData sheet,
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

}
