package dev.xcrafttm.ddlumen;

import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
import dev.xcrafttm.ddlumen.render.LightRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Dream Displays Lumen: displays light up the room in the colors of their video. */
public final class DdLumen implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ddlumen");

    @Override
    public void onInitializeClient() {
        LumenConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(ScreenLights::tick);
        LevelRenderEvents.COLLECT_SUBMITS.register(LightRenderer::submit);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ScreenLights.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ScreenLights.clear());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("ddlumen")
                        .then(literal("reload").executes(ctx -> {
                            LumenConfig.load();
                            ScreenLights.reset();
                            ctx.getSource().sendFeedback(Component.translatable("ddlumen.command.reloaded"));
                            return 1;
                        }))
                        .then(literal("intensity").then(argument("value", FloatArgumentType.floatArg(0, 16)).executes(ctx -> {
                            // the strength of the mode in use: with or without a shader pack
                            float value = FloatArgumentType.getFloat(ctx, "value");
                            if (IrisCompat.isShaderPackInUse()) LumenConfig.get().intensityWithShaders = value;
                            else LumenConfig.get().intensity = value;
                            LumenConfig.save();
                            ctx.getSource().sendFeedback(Component.translatable("ddlumen.command.intensity", value));
                            return 1;
                        })))
                        .then(literal("toggle").executes(ctx -> {
                            LumenConfig.get().enabled = !LumenConfig.get().enabled;
                            LumenConfig.save();
                            ctx.getSource().sendFeedback(Component.translatable(
                                    LumenConfig.get().enabled ? "ddlumen.command.on" : "ddlumen.command.off"));
                            return 1;
                        }))
                        .then(literal("status").executes(ctx -> {
                            int displays = 0, faces = 0;
                            for (ScreenLight light : ScreenLights.all()) {
                                displays++;
                                faces += light.surface().quads;
                            }
                            ctx.getSource().sendFeedback(Component.translatable("ddlumen.command.status", displays, faces));
                            return 1;
                        }))));
    }
}
