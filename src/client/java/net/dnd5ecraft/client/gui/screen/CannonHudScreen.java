package net.dnd5ecraft.client.gui.screen;

import net.dnd5ecraft.client.ClientCampaign;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.gui.hud.CombatHudOverlay;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.client.gui.theme.TabletopButton;
import net.minecraft.client.gui.screens.Screen;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

public final class CannonHudScreen extends Screen {
    private TabletopButton fire;

    public CannonHudScreen() { super(text("campaign.action.cannon_fire")); }

    @Override protected void init() {
        var control = CombatHudOverlay.cannonControl(width, height, font);
        int x = control == null ? (width - 112) / 2 : control.x();
        int y = control == null ? height - 67 : control.y();
        int buttonWidth = control == null ? 112 : control.width();
        fire = addRenderableWidget(new TabletopButton(x, y, buttonWidth, 14, getTitle(), false, ignored -> {
            onClose();
            CampaignActionsScreen.fireCannon();
        }));
    }

    @Override public void tick() {
        if (minecraft.player == null || ClientCampaign.companion(EldritchCannonEntity.class) == null) {
            onClose();
            return;
        }
        var combat = ClientDndState.combat();
        fire.active = !ClientDndState.controlsCombatant()
                || combat != null && combat.ownsTurn() && combat.resources().contains(Resource.BONUS_ACTION);
    }

    @Override public boolean isPauseScreen() { return false; }
}
