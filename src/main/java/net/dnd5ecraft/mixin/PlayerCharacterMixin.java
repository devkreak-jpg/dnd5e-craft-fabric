package net.dnd5ecraft.mixin;

import java.util.Optional;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.server.PlayerCharacter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerCharacterMixin implements PlayerCharacter {
    @Unique private static final EntityDataAccessor<Float> dnd5e$scaleData =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);
    @Unique private CharacterSheetData dnd5e$character;

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    @Unique
    private void dnd5e$defineScale(CallbackInfo callback) {
        dnd5e$player().getEntityData().define(dnd5e$scaleData, 1f);
    }

    @Override
    @Unique
    public Optional<CharacterSheetData> dnd5e$sheet() {
        return Optional.ofNullable(dnd5e$character);
    }

    @Override
    @Unique
    public void dnd5e$setSheet(CharacterSheetData sheet) {
        if (dnd5e$player().level().isClientSide) throw new IllegalStateException("Character sheets are server controlled");
        dnd5e$character = sheet;
        dnd5e$player().getEntityData().set(dnd5e$scaleData, sheet == null ? 1f : (float) sheet.race().scale());
    }

    @Override
    @Unique
    public float dnd5e$physicalScale() {
        var data = dnd5e$player().getEntityData();
        return data != null && data.hasItem(dnd5e$scaleData) ? data.get(dnd5e$scaleData) : 1f;
    }

    @Override
    @Unique
    public boolean dnd5e$isScaleKey(EntityDataAccessor<?> key) {
        return dnd5e$scaleData.equals(key);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    @Unique
    private void dnd5e$writeSheet(CompoundTag tag, CallbackInfo callback) {
        if (dnd5e$character != null) tag.put("DndCharacter", dnd5e$character.toNbt());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    @Unique
    private void dnd5e$readSheet(CompoundTag tag, CallbackInfo callback) {
        if (!tag.contains("DndCharacter", Tag.TAG_COMPOUND) || dnd5e$player().level().isClientSide) return;
        try {
            dnd5e$setSheet(CharacterSheetData.fromNbt(tag.getCompound("DndCharacter")));
        } catch (IllegalArgumentException invalid) {
            LoggerFactory.getLogger(PlayerCharacterMixin.class).warn("Ignored invalid character sheet for {}",
                    dnd5e$player().getUUID(), invalid);
        }
    }

    @Unique
    private Player dnd5e$player() { return (Player) (Object) this; }
}
