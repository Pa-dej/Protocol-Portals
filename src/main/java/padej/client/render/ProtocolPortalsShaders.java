package padej.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import padej.Main;

import java.io.IOException;

public final class ProtocolPortalsShaders {
    private static ShaderProgram portalAreaProgram;

    private ProtocolPortalsShaders() {
    }

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(ProtocolPortalsShaders::registerShaders);
    }

    private static void registerShaders(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        context.register(
                Identifier.of(Main.MOD_ID, "portal_area"),
                VertexFormats.POSITION_COLOR,
                shaderProgram -> portalAreaProgram = shaderProgram
        );
    }

    public static ShaderProgram portalAreaProgram() {
        return portalAreaProgram;
    }
}
