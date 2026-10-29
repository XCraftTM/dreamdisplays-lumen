package dev.xcrafttm.ddlumen.mixin;

import dev.xcrafttm.ddlumen.Texts;
import com.dreamdisplays.libs.kotlin.Unit;
import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.ui.DisplayMenu;
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase;
import com.dreamdisplays.platform.client.ui.menu.SettingsSection;
import com.dreamdisplays.platform.client.ui.widgets.IconButton;
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.xcrafttm.ddlumen.LumenConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Adds "Room light" and "Light color" rows to Dream Displays' display menu, right below its brightness: this
 * display's light strength (0 % turns it off) and how colorful its light is (0 % = white light), built from Dream
 * Displays' own slider and reset button so they look like the rest.
 */
@Mixin(value = DisplayMenu.class, remap = false)
public abstract class DisplayMenuMixin extends UiScreenBase {
    /** The slider shows 0–100 % of its track for 0–200 % light. */
    private static final double SCALE = 2.0;

    @Shadow
    @Final
    private DisplayScreen displayScreen;

    private DisplayMenuMixin() {
        super(Texts.literal(""));
    }

    @ModifyReturnValue(method = "settingsRows", at = @At("RETURN"), require = 0)
    private List<SettingsSection.Row> ddlumen$addLightRows(List<SettingsSection.Row> rows) {
        UUID id = displayScreen.getUuid();
        LumenConfig cfg = LumenConfig.get();
        SettingsSection.Row light = ddlumen$row("ddlumen.menu.light", cfg.displayLight(id),
                value -> LumenConfig.get().setDisplayLight(id, value));
        SettingsSection.Row color = ddlumen$row("ddlumen.menu.color", cfg.displayColor(id),
                value -> LumenConfig.get().setDisplayColor(id, value));

        List<SettingsSection.Row> out = new ArrayList<>(rows);
        int at = out.size();
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).getLabelKey().endsWith("brightness")) at = i + 1;
        }
        at = Math.min(at, out.size());
        out.add(at, light);
        out.add(at + 1, color);
        return out;
    }

    /** A row with Dream Displays' slider (0–200 %, 100 % in the middle) and reset button, under [key]. */
    private SettingsSection.Row ddlumen$row(String key, float initial, Consumer<Float> set) {
        ValueSlider slider = addUi(new ValueSlider(
                initial / SCALE,
                value -> percent(value),
                true,
                0.025,
                value -> {
                    set.accept((float) (value * SCALE));
                    return Unit.INSTANCE;
                }));
        IconButton reset = addUi(new IconButton("refresh", () -> {
            set.accept(1f);
            slider.setValue(1 / SCALE);
            return Unit.INSTANCE;
        }));
        reset.setEnabledWhen(() -> Math.abs(slider.getValue() * SCALE - 1) > 0.01);
        reset.setVisibleWhen(() -> !displayScreen.getErrored());
        slider.setVisibleWhen(() -> !displayScreen.getErrored());
        return new SettingsSection.Row(key, slider, reset, 0, () -> List.of(
                Texts.translatable(key + ".tooltip.title").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                Texts.translatable(key + ".tooltip.body").withStyle(ChatFormatting.GRAY),
                Texts.literal(""),
                Texts.translatable(key + ".tooltip.value", percent(slider.getValue())).withStyle(ChatFormatting.GOLD)));
    }

    private static Component percent(double sliderValue) {
        int pct = (int) Math.round(sliderValue * SCALE * 100);
        return pct == 0 ? Texts.translatable("ddlumen.menu.light.off") : Texts.literal(pct + "%");
    }
}
