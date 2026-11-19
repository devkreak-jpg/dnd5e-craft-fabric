package net.dnd5ecraft.server;

import java.util.Optional;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.minecraft.network.syncher.EntityDataAccessor;

public interface PlayerCharacter {
    Optional<CharacterSheetData> dnd5e$sheet();
    void dnd5e$setSheet(CharacterSheetData sheet);
    float dnd5e$physicalScale();
    boolean dnd5e$isScaleKey(EntityDataAccessor<?> key);
}
