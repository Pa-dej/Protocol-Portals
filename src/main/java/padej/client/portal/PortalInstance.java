package padej.client.portal;

import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record PortalInstance(
        UUID id,
        String sceneName,
        @Nullable String serverAddress,
        Vec3d center,
        Vec3d normal,
        Vec3d right,
        Vec3d up,
        Vec3d sceneAnchor,
        Vec3d skyColor,
        double width,
        double height,
        List<PortalRenderBlock> renderBlocks,
        List<PortalLightSample> lightSamples
) {
    public PortalInstance {
        if (serverAddress != null) {
            serverAddress = serverAddress.trim();
            if (serverAddress.isEmpty()) {
                serverAddress = null;
            }
        }
        renderBlocks = List.copyOf(renderBlocks);
        lightSamples = List.copyOf(lightSamples);
    }
}
