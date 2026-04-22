package padej.client.portal;

import net.minecraft.util.math.Vec3d;

import java.util.UUID;

public record DebugPlaneInstance(
        UUID id,
        Vec3d center,
        Vec3d normal,
        Vec3d right,
        Vec3d up,
        double width,
        double height
) {
}
