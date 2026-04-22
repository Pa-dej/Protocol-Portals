package padej.client.render;

public interface StencilFramebufferAccess {
    boolean protocolPortals$isStencilBufferEnabled();

    void protocolPortals$setStencilBufferEnabledAndReload(boolean enabled);
}
