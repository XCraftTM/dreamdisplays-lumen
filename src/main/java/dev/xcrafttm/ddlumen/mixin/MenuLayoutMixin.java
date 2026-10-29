package dev.xcrafttm.ddlumen.mixin;

import com.dreamdisplays.platform.client.ui.kit.UiRect;
import com.dreamdisplays.platform.client.ui.menu.MenuLayout;
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsPanel;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Gives the menu's settings panel room for the two extra rows ("Room light", "Light color"; a row is 22 + 4 pixels). */
@Mixin(targets = "com.dreamdisplays.platform.client.ui.menu.MenuLayout$Companion", remap = false)
public abstract class MenuLayoutMixin {
    private static final int ROW = 26 * 2;
    /** Title, seven rows, the gap before the sync row and the delete / report buttons below them. */
    private static final int SETTINGS_NEEDED = 10 + 9 + 6 + 7 * 26 + 6 + 4 + 22 + 10;

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

    /** Suggestions keep at least this card height (Dream Displays' own minimum is 75; smaller cards still work). */
    private static final int MIN_CARDS = 40;

    /** When the settings are still short, the suggestions below give up what is missing, as far as they can. */
    @ModifyReturnValue(method = "compute", at = @At("RETURN"), require = 0)
    private MenuLayout ddlumen$roomInOtherLayouts(MenuLayout layout) {
        UiRect suggestions = layout.getSuggestions();
        UiRect preview = layout.getPreview(), settings = layout.getSettings();
        if (layout.getSuggestionsVertical() || suggestions == null) return layout;
        int take = Math.min(SETTINGS_NEEDED - settings.getH(),
                suggestions.getH() - SuggestionsPanel.STRIP_CHROME_H - MIN_CARDS);
        if (take <= 0) return layout;
        // side by side: both grow; stacked (compact): only the settings below the preview
        boolean sideBySide = preview.getY() == settings.getY();
        return MenuLayoutAccessor.ddlumen$create(
                sideBySide ? new UiRect(preview.getX(), preview.getY(), preview.getW(), preview.getH() + take) : preview,
                new UiRect(settings.getX(), settings.getY(), settings.getW(), settings.getH() + take),
                new UiRect(suggestions.getX(), suggestions.getY() + take, suggestions.getW(), suggestions.getH() - take),
                false);
    }
}
