package dev.xcrafttm.ddlumen.mixin;

import com.dreamdisplays.platform.client.ui.kit.UiRect;
import com.dreamdisplays.platform.client.ui.menu.MenuLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** MenuLayout's private constructor. */
@Mixin(value = MenuLayout.class, remap = false)
public interface MenuLayoutAccessor {
    @Invoker("<init>")
    static MenuLayout ddlumen$create(UiRect preview, UiRect settings, UiRect suggestions, boolean suggestionsVertical) {
        throw new AssertionError();
    }
}
