package dev.xcrafttm.ddlumen;

import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.xcrafttm.ddlumen.capture.FrameCapture;
import dev.xcrafttm.ddlumen.compat.IrisCompat;
import dev.xcrafttm.ddlumen.light.ScreenLight;
import dev.xcrafttm.ddlumen.light.ScreenLights;
import dev.xcrafttm.ddlumen.render.LightRenderer;
import dev.xcrafttm.ddlumen.render.PostShaderLight;
import net.fabricmc.api.ClientModInitializer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
//#if MC >= 1.19
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//#else
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
//#endif
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//#if MC >= 26.1
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//#elif MC >= 1.21.11
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//#if MC >= 26.1
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
//#elif MC >= 1.19
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
//#else
import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.literal;
//#endif

/** Dream Displays Lumen: displays light up the room in the colors of their video. */
public final class DdLumen implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ddlumen");

    @Override
    public void onInitializeClient() {
        LumenConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(ScreenLights::tick);
        //#if MC >= 26.1
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                LightRenderer.submit(ctx.submitNodeCollector(), ctx.poseStack(), ctx.levelState().cameraRenderState.pos));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ScreenLights.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
        //#elif MC >= 1.21.11
        WorldRenderEvents.AFTER_ENTITIES.register(ctx ->
                LightRenderer.submit(ctx.commandQueue(), ctx.matrices(), ctx.worldState().cameraRenderState.pos));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ScreenLights.onChunkLoaded(chunk.getPos().x, chunk.getPos().z));
        //#else
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx ->
                LightRenderer.submit(ctx.consumers(), ctx.matrixStack(), ctx.camera().getPosition()));
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ScreenLights.onChunkLoaded(chunk.getPos().x, chunk.getPos().z));
        //#endif
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ScreenLights.clear());
        registerCommands();
    }

    private static void registerCommands() {
        //#if MC >= 1.19
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commands()));
        //#else
        ClientCommandManager.DISPATCHER.register(commands());
        //#endif
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commands() {
        return literal("ddlumen")
                .then(literal("reload").executes(ctx -> {
                    LumenConfig.load();
                    ScreenLights.reset();
                    ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.reloaded"));
                    return 1;
                }))
                .then(literal("intensity").then(argument("value", FloatArgumentType.floatArg(0, 16)).executes(ctx -> {
                    // the strength of the mode in use: with or without a shader pack
                    float value = FloatArgumentType.getFloat(ctx, "value");
                    if (IrisCompat.isShaderPackInUse()) LumenConfig.get().intensityWithShaders = value;
                    else LumenConfig.get().intensity = value;
                    LumenConfig.save();
                    ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.intensity", value));
                    return 1;
                })))
                .then(literal("saturation").then(argument("value", FloatArgumentType.floatArg(0, 4)).executes(ctx -> {
                    LumenConfig.get().colorSaturation = FloatArgumentType.getFloat(ctx, "value");
                    LumenConfig.save();
                    ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.saturation", LumenConfig.get().colorSaturation));
                    return 1;
                })))
                .then(literal("toggle").executes(ctx -> {
                    LumenConfig.get().enabled = !LumenConfig.get().enabled;
                    LumenConfig.save();
                    ctx.getSource().sendFeedback(Texts.translatable(
                            LumenConfig.get().enabled ? "ddlumen.command.on" : "ddlumen.command.off"));
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    int displays = 0, faces = 0;
                    for (ScreenLight light : ScreenLights.all()) {
                        displays++;
                        faces += light.surface().quads;
                    }
                    ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.status", displays, faces));
                    long now = System.nanoTime();
                    for (ScreenLight light : ScreenLights.all()) {
                        FrameCapture.Stats st = FrameCapture.stats(light.id);
                        float sum = 0;
                        for (float v : light.radiance()) sum += v;
                        ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.status.display",
                                light.id.toString().substring(0, 8), ScreenLights.stateOf(light.id),
                                st == null ? 0 : st.frames, st == null ? "-" : st.format,
                                st == null || st.frames == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f", (now - st.lastFrameNs) / 1e9),
                                st == null ? 0 : st.skipped, st == null ? "-" : st.lastSkip,
                                String.format(java.util.Locale.ROOT, "%.3f", sum / Math.max(1, light.radiance().length)),
                                light.surface().quads));
                    }
                    ctx.getSource().sendFeedback(Texts.translatable("ddlumen.command.status.render",
                            IrisCompat.isShaderPackInUse(), LightRenderer.lastSubmits, LightRenderer.lastFaces,
                            PostShaderLight.passes, FrameCapture.foreignUploads()));
                    return 1;
                }));
    }
}
