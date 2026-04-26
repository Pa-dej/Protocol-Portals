package padej.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import padej.Main;

public final class ProtocolPortalsShaders {
    private static final ShaderProgramKey PORTAL_AREA_KEY = new ShaderProgramKey(
            Identifier.of(Main.MOD_ID, "core/portal_area"),
            VertexFormats.POSITION_COLOR,
            Defines.EMPTY
    );
    private static final ShaderProgramKey PORTAL_COMPOSITE_KEY = new ShaderProgramKey(
            Identifier.of(Main.MOD_ID, "core/portal_composite"),
            VertexFormats.POSITION_COLOR,
            Defines.EMPTY
    );
    private static boolean missingShaderLoaderLogged = false;

    private ProtocolPortalsShaders() {
    }

    public static void register() {
        // 1.21.4+: shaders are loaded by ShaderLoader via ShaderProgramKey.
        // We keep explicit keys and resolve programs lazily from the render thread.
    }

    @Nullable
    public static ShaderProgram portalAreaProgram() {
        return resolveProgram(PORTAL_AREA_KEY);
    }

    @Nullable
    public static ShaderProgram portalCompositeProgram() {
        return resolveProgram(PORTAL_COMPOSITE_KEY);
    }

    @Nullable
    private static ShaderProgram resolveProgram(ShaderProgramKey key) {
        if (!RenderSystem.isOnRenderThread()) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getShaderLoader() == null) {
            if (!missingShaderLoaderLogged) {
                System.err.println("[Protocol Portals] Shader loader is unavailable while resolving " + key.configId() + ".");
                missingShaderLoaderLogged = true;
            }
            return null;
        }

        try {
            ShaderProgram program = client.getShaderLoader().getOrCreateProgram(key);
            if (program != null && program.getGlRef() > 0) {
                missingShaderLoaderLogged = false;
                return program;
            }
            return null;
        } catch (RuntimeException exception) {
            System.err.println("[Protocol Portals] Failed to resolve shader program " + key.configId() + ": " + exception.getMessage());
            return null;
        }
    }
}
