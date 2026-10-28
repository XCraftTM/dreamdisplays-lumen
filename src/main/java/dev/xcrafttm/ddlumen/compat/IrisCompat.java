package dev.xcrafttm.ddlumen.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.xcrafttm.ddlumen.DdLumen;
import dev.xcrafttm.ddlumen.LumenConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;

/** Iris, when installed. Only the nested class touches Iris' classes. */
public final class IrisCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat() {
    }

    public static boolean isShaderPackInUse() {
        return LOADED && Iris.shaderPackInUse();
    }

    public static boolean isRenderingShadowPass() {
        return LOADED && Iris.shadowPass();
    }

    /** Tells Iris which shader-pack program draws [pipeline] (Iris swaps the program out while a pack is active). */
    public static void assignPipeline(RenderPipeline pipeline) {
        if (LOADED) Iris.assign(pipeline, LumenConfig.get().irisProgram);
    }

    private static final class Iris {
        static boolean shaderPackInUse() {
            return IrisApi.getInstance().isShaderPackInUse();
        }

        static boolean shadowPass() {
            return IrisApi.getInstance().isRenderingShadowPass();
        }

        static void assign(RenderPipeline pipeline, String programName) {
            IrisProgram program;
            try {
                program = IrisProgram.valueOf(programName);
            } catch (IllegalArgumentException e) {
                DdLumen.LOGGER.warn("Unknown Iris program {}, using BEACON_BEAM", programName);
                program = IrisProgram.BEACON_BEAM;
            }
            IrisApi.getInstance().assignPipeline(pipeline, program);
        }
    }
}
