package dev.xcrafttm.ddlumen.compat;

//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//#else
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
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

    /** Whether depth is reversed while the pack renders (Minecraft reverses it; Iris may not). */
    public static boolean isReverseZ() {
        return !LOADED || !Iris.shaderPackInUse() || Iris.reverseZ();
    }

    /** True when a shader-pack program draws the light (not our own shader). */
    public static boolean programAssigned() {
        return LOADED && !"NONE".equals(LumenConfig.get().irisProgram);
    }

    /** Tells Iris which shader-pack program draws [pipeline] (Iris swaps the program out while a pack is active). */
    public static void assignPipeline(RenderPipeline pipeline) {
        if (LOADED) Iris.assign(pipeline, LumenConfig.get().irisProgram);
    }

    private static final class Iris {
        static boolean shaderPackInUse() {
            return IrisApi.getInstance().isShaderPackInUse();
        }

        static boolean reverseZ() {
            return IrisApi.getInstance().isReverseZDuringShaders();
        }

        static boolean shadowPass() {
            return IrisApi.getInstance().isRenderingShadowPass();
        }

        static void assign(RenderPipeline pipeline, String programName) {
            if (programName.equals("NONE")) return;
            IrisProgram program;
            try {
                program = IrisProgram.valueOf(programName);
            } catch (IllegalArgumentException e) {
                DdLumen.LOGGER.warn("Unknown Iris program {}, using TEXTURED", programName);
                program = IrisProgram.TEXTURED;
            }
            IrisApi.getInstance().assignPipeline(pipeline, program);
        }
    }
}
