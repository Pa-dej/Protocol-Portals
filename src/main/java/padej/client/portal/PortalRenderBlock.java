package padej.client.portal;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

public record PortalRenderBlock(
        Vec3d localBlockPosition,
        BlockState state,
        int packedLight,
        @Nullable NbtCompound blockEntityNbt
) {
    public PortalRenderBlock {
        blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.copy();
    }
}
