package net.dnd5ecraft.server;

import java.util.Optional;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.minecraft.network.syncher.EntityDataAccessor;

public interface PlayerCharacter {
    Optional<CharacterSheetData> dnd5e$sheet();
    void dnd5e$setSheet(CharacterSheetData sheet);
    float dnd5e$physicalScale();
    net.dnd5ecraft.rules.character.RaceType dnd5e$race();
    boolean dnd5e$showsCosmetics();
    int dnd5e$campaignFlags();
    void dnd5e$campaignFlags(int flags);
    int dnd5e$disguise();
    void dnd5e$disguise(int appearance);
    long dnd5e$disguiseExpiresAt();
    void dnd5e$disguiseExpiresAt(long expiresAt);
    void dnd5e$setCosmetics(boolean enabled);
    String dnd5e$journalNotes();
    void dnd5e$journalNotes(String notes);
    boolean dnd5e$isScaleKey(EntityDataAccessor<?> key);
}
