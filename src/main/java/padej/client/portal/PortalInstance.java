package padej.client.portal;

import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

public record PortalInstance(
        UUID id,
        String sceneName,
        Vec3d center,
        Vec3d normal,
        Vec3d right,
        Vec3d up,
        Vec3d sceneAnchor,
        double width,
        double height,
        List<PortalRenderBlock> renderBlocks,
        List<PortalLightSample> lightSamples
) {
    public PortalInstance {
        renderBlocks = List.copyOf(renderBlocks);
        lightSamples = List.copyOf(lightSamples);
    }
}
