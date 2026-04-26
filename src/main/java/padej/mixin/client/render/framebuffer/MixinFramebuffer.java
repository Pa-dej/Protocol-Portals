package padej.mixin.client.render.framebuffer;

import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import padej.client.render.StencilFramebufferAccess;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;

@Mixin(Framebuffer.class)
public abstract class MixinFramebuffer implements StencilFramebufferAccess {
    @Unique
    private boolean protocolPortals$stencilBufferEnabled;

    @Shadow
    public int textureWidth;

    @Shadow
    public int textureHeight;

    @Shadow
    public abstract void resize(int width, int height);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void protocolPortals$onInit(boolean useDepthAttachment, CallbackInfo ci) {
        protocolPortals$stencilBufferEnabled = false;
    }

    @ModifyArgs(
            method = "initFbo",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V",
                    remap = false
            )
    )
    private void protocolPortals$modifyTexImage2D(Args args) {
        if (Objects.equals(args.get(2), GL_DEPTH_COMPONENT) && protocolPortals$stencilBufferEnabled) {
            args.set(2, GL_DEPTH24_STENCIL8);
            args.set(6, ARBFramebufferObject.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_UNSIGNED_INT_24_8);
        }
    }

    @ModifyArgs(
            method = "initFbo",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glFramebufferTexture2D(IIIII)V",
                    remap = false
            )
    )
    private void protocolPortals$modifyFramebufferTexture2D(Args args) {
        if (Objects.equals(args.get(1), GL30C.GL_DEPTH_ATTACHMENT) && protocolPortals$stencilBufferEnabled) {
            args.set(1, GL30.GL_DEPTH_STENCIL_ATTACHMENT);
        }
    }

    @Override
    public boolean protocolPortals$isStencilBufferEnabled() {
        return protocolPortals$stencilBufferEnabled;
    }

    @Override
    public void protocolPortals$setStencilBufferEnabledAndReload(boolean enabled) {
        if (protocolPortals$stencilBufferEnabled == enabled) {
            return;
        }

        protocolPortals$stencilBufferEnabled = enabled;
        if (textureWidth > 0 && textureHeight > 0) {
            resize(textureWidth, textureHeight);
        }
    }
}
