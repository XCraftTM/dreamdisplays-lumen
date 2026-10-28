package dev.xcrafttm.ddlumen.test;

import com.dreamdisplays.api.display.model.property.DisplayFacing;
import com.dreamdisplays.api.server.DreamDisplaysServer;
import com.dreamdisplays.api.server.ServerDisplay;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a dark room with a display on its back wall when the test player joins, and plays the test video
 * (served by scripts/autotest.sh) on it.
 */
public final class TestScene implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("ddlumen-test");
    static final String VIDEO = System.getProperty("ddlumen.test.video", "http://127.0.0.1:8899/test.mp4");

    private static ServerPlayer pending;
    private static int pendingTicks;

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> build(server, handler.player)));
        // the join moves the player once more after the event: place them again a bit later
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pending != null && ++pendingTicks == 40) {
                run(server, "tp " + pending.getScoreboardName() + " 0.5 -60 17.5 180 12");
                LOG.info("Player at {}", pending.position());
                ServerPlayer player = pending;
                pending = null;
                // the video starts only now, so its first part is not over before the player stands in the room
                DreamDisplaysServer.onReady(() -> server.execute(() -> createDisplay(player)));
            }
        });
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack()
                .withSource(new net.minecraft.commands.CommandSource() {
                    @Override
                    public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                        LOG.info("/{}: {}", command, message.getString());
                    }

                    @Override
                    public boolean acceptsSuccess() {
                        return true;
                    }

                    @Override
                    public boolean acceptsFailure() {
                        return true;
                    }

                    @Override
                    public boolean shouldInformAdmins() {
                        return false;
                    }
                }), command);
    }

    private static void build(MinecraftServer server, ServerPlayer player) {
        String[] commands = {
                // a closed room, so no daylight gets in: back wall at z = -1, the viewer at the other end
                "fill -10 -61 -2 10 -50 20 minecraft:smooth_stone hollow",
                "fill -9 -61 -1 9 -61 19 minecraft:white_concrete",
                "fill -9 -51 -1 9 -51 19 minecraft:smooth_stone",
                // something to cast shadows and show the colors on
                "fill -6 -60 6 -5 -57 7 minecraft:quartz_block",
                "fill 5 -60 6 6 -60 9 minecraft:oak_planks",
                "setblock 0 -60 10 minecraft:white_wool",
                "fill -9 -60 12 -9 -55 16 minecraft:white_terracotta",
                "fill 9 -60 12 9 -55 16 minecraft:white_terracotta",
                "fill -3 -60 4 3 -60 4 minecraft:grass_block",
                "time set midnight",
                "gamemode creative " + player.getScoreboardName(),
                "tp " + player.getScoreboardName() + " 0.5 -60 17.5 180 12",
        };
        for (String command : commands) run(server, command);
        pending = player;
        pendingTicks = 0;
    }

    private static void createDisplay(ServerPlayer player) {
        for (ServerDisplay display : DreamDisplaysServer.getDisplays()) {
            LOG.info("Display {} already there, url {}", display.getId(), display.getUrl());
            display.setUrl(VIDEO, "");
            return;
        }
        // 8 x 4 blocks of the back wall, facing into the room
        ServerDisplay display = DreamDisplaysServer.getDisplays().create(player.getUUID(), "minecraft:overworld",
                -4, -59, -1, 3, -56, -1, DisplayFacing.SOUTH);
        if (display == null) {
            LOG.error("Could not create the test display");
            return;
        }
        LOG.info("Created display {} ({} x {}), playing {}: {}", display.getId(), display.getWidth(), display.getHeight(),
                VIDEO, display.setUrl(VIDEO, ""));
    }
}
