package dev.xcrafttm.ddlumen.mixin;

import com.dreamdisplays.platform.client.ui.kit.UiRect;
import com.dreamdisplays.platform.client.ui.menu.MenuLayout;
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsPanel;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Gives the menu's settings panel room for the extra "Room light" row (one row: 22 + 4 pixels). */
@Mixin(targets = "com.dreamdisplays.platform.client.ui.menu.MenuLayout$Companion", remap = false)
public abstract class MenuLayoutMixin {
    private static final int ROW = 26;
    /** Title, six rows, the gap before the sync row and the delete / report buttons below them. */
    private static final int SETTINGS_NEEDED = 10 + 9 + 6 + 6 * ROW + 6 + 4 + 22 + 10;

    /** Wide layout: the settings panel below the preview has a minimum height (the first 220 in compute). */
    @ModifyConstant(method = "compute", constant = @Constant(intValue = 220, ordinal = 0), require = 0)
    private int ddlumen$roomInWideLayout(int settingsMinHeight) {
        return settingsMinHeight + ROW;
    }

    /** Other layouts: the top row keeps this much before the suggestions shrink (the second 200 in compute). */
    @ModifyConstant(method = "compute", constant = @Constant(intValue = 200, ordinal = 1), require = 0)
    private int ddlumen$roomInTopRow(int topRowFloor) {
        return topRowFloor + ROW;
    }

    /** When the suggestions have height to spare and the settings are still short, they give up one row. */
    @ModifyReturnValue(method = "compute", at = @At("RETURN"), require = 0)
    private MenuLayout ddlumen$roomInOtherLayouts(MenuLayout layout) {
        UiRect suggestions = layout.getSuggestions();
        UiRect preview = layout.getPreview(), settings = layout.getSettings();
        if (layout.getSuggestionsVertical() || suggestions == null || settings.getH() >= SETTINGS_NEEDED
                || suggestions.getH() - ROW < SuggestionsPanel.STRIP_CHROME_H + SuggestionsPanel.MIN_CARD_VIEWPORT_H) {
            return layout;
        }
        // side by side: both grow; stacked (compact): only the settings below the preview
        boolean sideBySide = preview.getY() == settings.getY();
        return MenuLayoutAccessor.ddlumen$create(
                sideBySide ? new UiRect(preview.getX(), preview.getY(), preview.getW(), preview.getH() + ROW) : preview,
                new UiRect(settings.getX(), settings.getY(), settings.getW(), settings.getH() + ROW),
                new UiRect(suggestions.getX(), suggestions.getY() + ROW, suggestions.getW(), suggestions.getH() - ROW),
                false);
    }
}
