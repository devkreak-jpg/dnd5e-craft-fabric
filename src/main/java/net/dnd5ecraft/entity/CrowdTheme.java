package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;

public enum CrowdTheme {
    TAVERN(BestiaryPreset.TAVERN_KEEPER, Weapon.CLUB, Weapon.DAGGER, Weapon.QUARTERSTAFF),
    TOWNSFOLK(BestiaryPreset.MERCHANT, Weapon.SICKLE, Weapon.QUARTERSTAFF, Weapon.LIGHT_HAMMER),
    GUARDS(BestiaryPreset.TOWN_GUARD, Weapon.SPEAR, Weapon.LONGSWORD, Weapon.PIKE),
    BANDITS(BestiaryPreset.BANDIT_CAPTAIN, Weapon.SCIMITAR, Weapon.DAGGER, Weapon.LIGHT_CROSSBOW),
    CULTISTS(BestiaryPreset.GOBLIN, Weapon.QUARTERSTAFF, Weapon.DAGGER, Weapon.SICKLE);

    private static final List<List<String>> NAMES = List.of(
            List.of("Arlen Vale", "Tessa Reed", "Merrin Ash"), List.of("Silven Dawnsong", "Nimue Fen", "Aelar Windleaf"),
            List.of("Brunna Flint", "Dorin Forge", "Hilda Ironmark"), List.of("Grasha Stonehand", "Urzag Thorn", "Torga Redeye"));
    private final BestiaryPreset preset;
    private final List<Weapon> equipment;

    CrowdTheme(BestiaryPreset preset, Weapon... equipment) {
        this.preset = preset;
        this.equipment = List.of(equipment);
    }

    public record Appearance(String name, String texture, float scale, Weapon weapon, int radius) {}
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public BestiaryPreset preset() { return preset; }

    public Appearance random(RandomGenerator random) {
        var pool = NAMES.get(random.nextInt(NAMES.size()));
        return new Appearance(pool.get(random.nextInt(pool.size())),
                "dnd-5e-craft:textures/entity/crowd/" + id() + "_" + random.nextInt(3) + ".png",
                .75f + random.nextFloat() * .4f, equipment.get(random.nextInt(equipment.size())), 4 + random.nextInt(13));
    }
}
