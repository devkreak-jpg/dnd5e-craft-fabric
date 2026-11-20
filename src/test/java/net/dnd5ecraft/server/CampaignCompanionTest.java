package net.dnd5ecraft.server;

import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CampaignCompanionTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void cannonSearchVolumeIncludesTargetsOnlyNearTheCannon() {
        var player = Vec3.ZERO;
        var cannon = new Vec3(85, 4, 0);
        var bounds = CampaignActions.companionTargetBounds(cannon, 24);
        assertTrue(bounds.contains(new Vec3(105, 5, 0)));
        assertFalse(bounds.contains(player));
        assertFalse(bounds.contains(new Vec3(115, 5, 0)));
    }

    @Test void echoArmorTracksItsOwnersProficiency() {
        for (int proficiency = 2; proficiency <= 6; proficiency++) {
            assertEquals(14 + proficiency, 10 + EchoAvatarEntity.armorAttribute(proficiency));
        }
    }
}
