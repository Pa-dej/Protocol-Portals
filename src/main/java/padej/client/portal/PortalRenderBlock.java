package padej.client.portal;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.Vec3d;

public record PortalRenderBlock(Vec3d localBlockPosition, BlockState state) {
}
