package dev.xcrafttm.ddlumen;

import dev.xcrafttm.ddlumen.Texts;
import net.minecraft.network.chat.MutableComponent;
//#if MC < 1.19
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
//#else
import net.minecraft.network.chat.Component;
//#endif

/** Chat components; before 1.19 they are created through their classes. */
public final class Texts {
    private Texts() {
    }

    public static MutableComponent translatable(String key, Object... args) {
        //#if MC >= 1.19
        return Component.translatable(key, args);
        //#else
        return new TranslatableComponent(key, args);
        //#endif
    }

    public static MutableComponent literal(String text) {
        //#if MC >= 1.19
        return Component.literal(text);
        //#else
        return new TextComponent(text);
        //#endif
    }
}
