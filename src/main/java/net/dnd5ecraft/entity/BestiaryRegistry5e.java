package net.dnd5ecraft.entity;

import java.util.List;

import static net.dnd5ecraft.entity.BestiaryPreset.*;

public final class BestiaryRegistry5e {
    private static final List<BestiaryPreset> PRESETS = List.of(GOBLIN, KOBOLD, SKELETON, ZOMBIE, WOLF, DIRE_WOLF,
            ORC, BANDIT_CAPTAIN, BUGBEAR, GHOUL, OGRE, OWLBEAR, TROLL, VAMPIRE_SPAWN, YOUNG_RED_DRAGON);

    private BestiaryRegistry5e() {}

    public static List<BestiaryPreset> presets() { return PRESETS; }
}
