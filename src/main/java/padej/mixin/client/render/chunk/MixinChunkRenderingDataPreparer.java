package padej.mixin.client.render.chunk;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.ChunkRenderingDataPreparer;
import net.minecraft.util.math.Box;
import padej.client.render.PortalOuterFrustumCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkRenderingDataPreparer.class)
public abstract class MixinChunkRenderingDataPreparer {
    @Redirect(
            method = "method_52829(Lnet/minecraft/client/render/Frustum;Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/Frustum;isVisible(Lnet/minecraft/util/math/Box;)Z"
            )
    )
    private boolean protocolPortals$filterVisibleChunksInOcclusionGraph(
            Frustum frustum,
            Box box
    ) {
        return frustum.isVisible(box) && !PortalOuterFrustumCulling.shouldCull(box);
    }
}
