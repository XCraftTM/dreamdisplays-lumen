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

/**
 * Adds a "Room light" row to Dream Displays' display menu, right below its brightness: this display's light
 * strength (0 % turns it off), built from Dream Displays' own slider and reset button so it looks like the rest.
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
    private List<SettingsSection.Row> ddlumen$addLightRow(List<SettingsSection.Row> rows) {
        UUID id = displayScreen.getUuid();
        LumenConfig cfg = LumenConfig.get();
        ValueSlider slider = addUi(new ValueSlider(
                cfg.displayLight(id) / SCALE,
                value -> percent(value),
                true,
                0.025,
                value -> {
                    LumenConfig.get().setDisplayLight(id, (float) (value * SCALE));
                    return Unit.INSTANCE;
                }));
        IconButton reset = addUi(new IconButton("refresh", () -> {
            LumenConfig.get().setDisplayLight(id, 1f);
            slider.setValue(1 / SCALE);
            return Unit.INSTANCE;
        }));
        reset.setEnabledWhen(() -> Math.abs(slider.getValue() * SCALE - 1) > 0.01);
        reset.setVisibleWhen(() -> !displayScreen.getErrored());
        slider.setVisibleWhen(() -> !displayScreen.getErrored());

        SettingsSection.Row row = new SettingsSection.Row("ddlumen.menu.light", slider, reset, 0, () -> List.of(
                Texts.translatable("ddlumen.menu.light.tooltip.title").withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                Texts.translatable("ddlumen.menu.light.tooltip.body").withStyle(ChatFormatting.GRAY),
                Texts.literal(""),
                Texts.translatable("ddlumen.menu.light.tooltip.value", percent(slider.getValue()))
                        .withStyle(ChatFormatting.GOLD)));

        List<SettingsSection.Row> out = new ArrayList<>(rows);
        int at = out.size();
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).getLabelKey().endsWith("brightness")) at = i + 1;
        }
        out.add(Math.min(at, out.size()), row);
        return out;
    }

    private static Component percent(double sliderValue) {
        int pct = (int) Math.round(sliderValue * SCALE * 100);
        return pct == 0 ? Texts.translatable("ddlumen.menu.light.off") : Texts.literal(pct + "%");
    }
}
