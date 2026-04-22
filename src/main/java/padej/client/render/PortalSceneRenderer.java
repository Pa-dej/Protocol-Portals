package padej.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import padej.client.portal.DebugPlaneInstance;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalManager;
import padej.client.portal.PortalRenderBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PortalSceneRenderer {
    private static final int OUTER_STENCIL_VALUE = 0;
    private static final int THIS_PORTAL_STENCIL_VALUE = 1;
    private static final double MAX_PORTAL_RENDER_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double MAX_BLOCK_RENDER_DISTANCE_SQ = 256.0D * 256.0D;

    private final PortalManager portalManager;
    private boolean stencilReadyThisFrame = false;
    private boolean stencilUnavailableLogged = false;
    private boolean fallbackModeLogged = false;

    public PortalSceneRenderer(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    public void register() {
        WorldRenderEvents.START.register(this::prepareFrame);
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderPortals);
        WorldRenderEvents.END.register(this::endFrame);
    }

    private void prepareFrame(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer framebuffer = client.getFramebuffer();

        stencilReadyThisFrame = ensureMainFramebufferStencil(framebuffer);
        if (!stencilReadyThisFrame) {
            return;
        }

        framebuffer.beginWrite(false);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(OUTER_STENCIL_VALUE);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilFunc(GL11.GL_EQUAL, OUTER_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    private void renderPortals(WorldRenderContext context) {
        List<DebugPlaneInstance> debugPlanes = portalManager.debugPlanes();
        List<PortalInstance> portals = sortedPortals(context.camera().getPos());
        if (portals.isEmpty() && debugPlanes.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        Vec3d camera = context.camera().getPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());

        try {
            renderDebugPlanes(debugPlanes, matrix);

            if (portals.isEmpty()) {
                return;
            }

            if (!stencilReadyThisFrame) {
                renderFallbackWithoutStencil(portals, matrix, camera);
                return;
            }

            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableCull();

            for (PortalInstance portal : portals) {
                if (!isPortalValidForRendering(portal, camera)) {
                    continue;
                }

                if (!renderPortalViewAreaToStencilAndDecideVisibility(portal, matrix)) {
                    continue;
                }

                clearDepthOfPortalViewArea(portal, matrix);
                renderPortalSceneInStencil(portal, matrix, camera);
                restoreDepthOfPortalViewArea(portal, matrix);
                clampStencilValue(OUTER_STENCIL_VALUE);
            }

            GL11.glStencilFunc(GL11.GL_EQUAL, OUTER_STENCIL_VALUE, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glStencilMask(0xFF);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);

            drawPortalFrames(portals, matrix);
            RenderSystem.enableCull();
        } finally {
            matrices.pop();
        }
    }

    private void endFrame(WorldRenderContext context) {
        if (stencilReadyThisFrame) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
        stencilReadyThisFrame = false;
    }

    private List<PortalInstance> sortedPortals(Vec3d camera) {
        List<PortalInstance> portals = new ArrayList<>(portalManager.activePortals());
        portals.sort(Comparator.comparingDouble(p -> camera.squaredDistanceTo(p.center())));
        return portals;
    }

    private boolean isPortalValidForRendering(PortalInstance portal, Vec3d camera) {
        return camera.squaredDistanceTo(portal.center()) <= MAX_PORTAL_RENDER_DISTANCE_SQ;
    }

    private boolean renderPortalViewAreaToStencilAndDecideVisibility(PortalInstance portal, Matrix4f matrix) {
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (cullWasEnabled) {
            RenderSystem.disableCull();
        }
        try {
            RenderSystem.setShader(PortalSceneRenderer::getPortalAreaShader);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(false);
            GL11.glStencilMask(0xFF);
            GL11.glStencilFunc(GL11.GL_EQUAL, OUTER_STENCIL_VALUE, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);

            int queryId = GL15C.glGenQueries();
            GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, queryId);
            drawPortalPlaneQuad(portal, matrix, 255, 255, 255, 0);
            GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);

            int samplesPassed = GL15C.glGetQueryObjecti(queryId, GL15C.GL_QUERY_RESULT);
            GL15C.glDeleteQueries(queryId);
            return samplesPassed != 0;
        } finally {
            if (cullWasEnabled) {
                RenderSystem.enableCull();
            }
        }
    }

    private void clearDepthOfPortalViewArea(PortalInstance portal, Matrix4f matrix) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        GL11.glDepthRange(1.0D, 1.0D);
        drawPortalPlaneQuad(portal, matrix, 255, 255, 255, 0);
        GL11.glDepthRange(0.0D, 1.0D);
    }

    private void renderPortalSceneInStencil(PortalInstance portal, Matrix4f matrix, Vec3d camera) {
        RenderSystem.setShader(PortalSceneRenderer::getPortalSceneShader);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        Set<Long> occupied = buildOccupiedSet(portal);
        BufferBuilder quads = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int rendered = 0;
        for (PortalRenderBlock block : portal.renderBlocks()) {
            Vec3d localBlockPosition = block.localBlockPosition();
            Vec3d worldMin = sceneBlockMin(portal, localBlockPosition);
            Vec3d worldCenter = worldMin
                    .add(portal.right().multiply(0.5D))
                    .add(portal.up().multiply(0.5D))
                    .add(portal.normal().multiply(0.5D));

            if (camera.squaredDistanceTo(worldCenter) > MAX_BLOCK_RENDER_DISTANCE_SQ) {
                continue;
            }

            int bx = (int) localBlockPosition.x;
            int by = (int) localBlockPosition.y;
            int bz = (int) localBlockPosition.z;
            addExposedCubeFaces(
                    quads, matrix, worldMin, portal.right(), portal.up(), portal.normal(),
                    bx, by, bz, occupied,
                    block.red(), block.green(), block.blue(), 255
            );
            rendered++;
        }

        if (rendered > 0) {
            BufferRenderer.drawWithGlobalProgram(quads.end());
        } else {
            quads.endNullable();
        }
    }

    private void restoreDepthOfPortalViewArea(PortalInstance portal, Matrix4f matrix) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        drawPortalPlaneQuad(portal, matrix, 255, 255, 255, 0);
    }

    private void clampStencilValue(int maxStencilValue) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(false);
        GL11.glStencilMask(0xFF);
        GL11.glStencilFunc(GL11.GL_LESS, maxStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);
        RenderSystem.disableDepthTest();
        drawScreenTriangle();
        RenderSystem.enableDepthTest();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
    }

    private void drawPortalFrames(List<PortalInstance> portals, Matrix4f matrix) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(PortalSceneRenderer::getPortalAreaShader);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        BufferBuilder lines = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (PortalInstance portal : portals) {
            addPortalFrame(lines, matrix, portal);
        }
        BufferRenderer.drawWithGlobalProgram(lines.end());

        GL11.glEnable(GL11.GL_STENCIL_TEST);
    }

    private void renderDebugPlanes(List<DebugPlaneInstance> debugPlanes, Matrix4f matrix) {
        if (debugPlanes.isEmpty()) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.setShader(PortalSceneRenderer::getPortalAreaShader);

        BufferBuilder fills = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (DebugPlaneInstance plane : debugPlanes) {
            addPlaneQuad(fills, matrix, plane.center(), plane.right(), plane.up(), plane.width(), plane.height(),
                    255, 96, 64, 96);
        }
        BufferRenderer.drawWithGlobalProgram(fills.end());

        BufferBuilder frame = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (DebugPlaneInstance plane : debugPlanes) {
            addPlaneFrame(frame, matrix, plane.center(), plane.right(), plane.up(), plane.width(), plane.height(),
                    255, 200, 48, 255);
        }
        BufferRenderer.drawWithGlobalProgram(frame.end());

        RenderSystem.disableDepthTest();
        BufferBuilder xrayFrame = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (DebugPlaneInstance plane : debugPlanes) {
            addPlaneFrame(xrayFrame, matrix, plane.center(), plane.right(), plane.up(), plane.width(), plane.height(),
                    255, 255, 255, 255);
        }
        BufferRenderer.drawWithGlobalProgram(xrayFrame.end());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private void renderFallbackWithoutStencil(List<PortalInstance> portals, Matrix4f matrix, Vec3d camera) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShader(PortalSceneRenderer::getPortalSceneShader);

        if (!fallbackModeLogged) {
            System.err.println("[Protocol Portals] Rendering portal scene in fallback mode without stencil clipping.");
            fallbackModeLogged = true;
        }

        BufferBuilder quads = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int rendered = 0;
        for (PortalInstance portal : portals) {
            if (!isPortalValidForRendering(portal, camera)) {
                continue;
            }

            Set<Long> occupied = buildOccupiedSet(portal);
            for (PortalRenderBlock block : portal.renderBlocks()) {
                Vec3d localBlockPosition = block.localBlockPosition();
                Vec3d worldMin = sceneBlockMin(portal, localBlockPosition);
                Vec3d worldCenter = worldMin
                        .add(portal.right().multiply(0.5D))
                        .add(portal.up().multiply(0.5D))
                        .add(portal.normal().multiply(0.5D));

                if (camera.squaredDistanceTo(worldCenter) > MAX_BLOCK_RENDER_DISTANCE_SQ) {
                    continue;
                }

                int bx = (int) localBlockPosition.x;
                int by = (int) localBlockPosition.y;
                int bz = (int) localBlockPosition.z;
                addExposedCubeFaces(
                        quads, matrix, worldMin, portal.right(), portal.up(), portal.normal(),
                        bx, by, bz, occupied,
                        block.red(), block.green(), block.blue(), 255
                );
                rendered++;
            }
        }

        if (rendered > 0) {
            BufferRenderer.drawWithGlobalProgram(quads.end());
        } else {
            quads.endNullable();
        }
        drawPortalFrames(portals, matrix);
        RenderSystem.enableCull();
    }

    private static ShaderProgram getPortalAreaShader() {
        ShaderProgram program = ProtocolPortalsShaders.portalAreaProgram();
        if (program != null) {
            return program;
        }
        return GameRenderer.getPositionColorProgram();
    }

    private static ShaderProgram getPortalSceneShader() {
        ShaderProgram program = ProtocolPortalsShaders.portalSceneProgram();
        if (program != null) {
            return program;
        }
        return GameRenderer.getPositionColorProgram();
    }

    private boolean ensureMainFramebufferStencil(Framebuffer framebuffer) {
        if (!framebuffer.useDepthAttachment || framebuffer.fbo <= 0) {
            if (!stencilUnavailableLogged) {
                System.err.println("[Protocol Portals] Main framebuffer does not expose depth attachment for stencil.");
                stencilUnavailableLogged = true;
            }
            return false;
        }

        if (!(framebuffer instanceof StencilFramebufferAccess stencilFramebuffer)) {
            if (!stencilUnavailableLogged) {
                System.err.println("[Protocol Portals] Framebuffer does not implement stencil access mixin.");
                stencilUnavailableLogged = true;
            }
            return false;
        }

        if (!stencilFramebuffer.protocolPortals$isStencilBufferEnabled()) {
            stencilFramebuffer.protocolPortals$setStencilBufferEnabledAndReload(true);
        }

        Framebuffer current = MinecraftClient.getInstance().getFramebuffer();
        if (current instanceof StencilFramebufferAccess currentStencilFramebuffer
                && currentStencilFramebuffer.protocolPortals$isStencilBufferEnabled()) {
            fallbackModeLogged = false;
            return true;
        }

        if (!stencilUnavailableLogged) {
            System.err.println("[Protocol Portals] Failed to enable stencil-enabled framebuffer.");
            stencilUnavailableLogged = true;
        }
        return false;
    }

    private static Vec3d sceneBlockMin(PortalInstance portal, Vec3d localBlockPosition) {
        return portal.sceneAnchor()
                .add(portal.right().multiply(localBlockPosition.x))
                .add(portal.up().multiply(localBlockPosition.y))
                .add(portal.normal().multiply(localBlockPosition.z));
    }

    private static void drawPortalPlaneQuad(PortalInstance portal, Matrix4f matrix, int red, int green, int blue, int alpha) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        addPlaneQuad(buffer, matrix, portal.center(), portal.right(), portal.up(), portal.width(), portal.height(), red, green, blue, alpha);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void addPortalFrame(BufferBuilder lines, Matrix4f matrix, PortalInstance portal) {
        addPlaneFrame(lines, matrix, portal.center(), portal.right(), portal.up(), portal.width(), portal.height(),
                35, 235, 255, 255);
    }

    private static void addPlaneQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3d center,
            Vec3d right,
            Vec3d up,
            double width,
            double height,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        Vec3d halfRight = right.multiply(width * 0.5D);
        Vec3d halfUp = up.multiply(height * 0.5D);
        Vec3d bottomLeft = center.subtract(halfRight).subtract(halfUp);
        Vec3d bottomRight = center.add(halfRight).subtract(halfUp);
        Vec3d topRight = center.add(halfRight).add(halfUp);
        Vec3d topLeft = center.subtract(halfRight).add(halfUp);

        addVertex(buffer, matrix, bottomLeft, red, green, blue, alpha);
        addVertex(buffer, matrix, bottomRight, red, green, blue, alpha);
        addVertex(buffer, matrix, topRight, red, green, blue, alpha);
        addVertex(buffer, matrix, topLeft, red, green, blue, alpha);
    }

    private static void addPlaneFrame(
            BufferBuilder lines,
            Matrix4f matrix,
            Vec3d center,
            Vec3d right,
            Vec3d up,
            double width,
            double height,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        Vec3d halfRight = right.multiply(width * 0.5D);
        Vec3d halfUp = up.multiply(height * 0.5D);

        Vec3d bottomLeft = center.subtract(halfRight).subtract(halfUp);
        Vec3d bottomRight = center.add(halfRight).subtract(halfUp);
        Vec3d topLeft = center.subtract(halfRight).add(halfUp);
        Vec3d topRight = center.add(halfRight).add(halfUp);

        addLine(lines, matrix, bottomLeft, bottomRight, red, green, blue, alpha);
        addLine(lines, matrix, bottomRight, topRight, red, green, blue, alpha);
        addLine(lines, matrix, topRight, topLeft, red, green, blue, alpha);
        addLine(lines, matrix, topLeft, bottomLeft, red, green, blue, alpha);
    }

    private static void addLine(
            BufferBuilder lines,
            Matrix4f matrix,
            Vec3d a,
            Vec3d b,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        lines.vertex(matrix, (float) a.x, (float) a.y, (float) a.z).color(red, green, blue, alpha);
        lines.vertex(matrix, (float) b.x, (float) b.y, (float) b.z).color(red, green, blue, alpha);
    }

    private static void addOrientedCube(
            BufferBuilder quads,
            Matrix4f matrix,
            Vec3d min,
            Vec3d right,
            Vec3d up,
            Vec3d forward,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        Vec3d p000 = min;
        Vec3d p100 = min.add(right);
        Vec3d p010 = min.add(up);
        Vec3d p110 = min.add(right).add(up);
        Vec3d p001 = min.add(forward);
        Vec3d p101 = min.add(right).add(forward);
        Vec3d p011 = min.add(up).add(forward);
        Vec3d p111 = min.add(right).add(up).add(forward);

        addQuad(quads, matrix, p000, p010, p110, p100, red, green, blue, alpha);
        addQuad(quads, matrix, p001, p101, p111, p011, red, green, blue, alpha);
        addQuad(quads, matrix, p000, p100, p101, p001, red, green, blue, alpha);
        addQuad(quads, matrix, p010, p011, p111, p110, red, green, blue, alpha);
        addQuad(quads, matrix, p000, p001, p011, p010, red, green, blue, alpha);
        addQuad(quads, matrix, p100, p110, p111, p101, red, green, blue, alpha);
    }

    private static void addExposedCubeFaces(
            BufferBuilder quads,
            Matrix4f matrix,
            Vec3d min,
            Vec3d right,
            Vec3d up,
            Vec3d forward,
            int x,
            int y,
            int z,
            Set<Long> occupied,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        int topRed = red;
        int topGreen = green;
        int topBlue = blue;

        int bottomRed = scaleChannel(red, 0.45F);
        int bottomGreen = scaleChannel(green, 0.45F);
        int bottomBlue = scaleChannel(blue, 0.45F);

        int sideXRed = scaleChannel(red, 0.72F);
        int sideXGreen = scaleChannel(green, 0.72F);
        int sideXBlue = scaleChannel(blue, 0.72F);

        int sideZRed = scaleChannel(red, 0.62F);
        int sideZGreen = scaleChannel(green, 0.62F);
        int sideZBlue = scaleChannel(blue, 0.62F);

        Vec3d p000 = min;
        Vec3d p100 = min.add(right);
        Vec3d p010 = min.add(up);
        Vec3d p110 = min.add(right).add(up);
        Vec3d p001 = min.add(forward);
        Vec3d p101 = min.add(right).add(forward);
        Vec3d p011 = min.add(up).add(forward);
        Vec3d p111 = min.add(right).add(up).add(forward);

        if (!occupied.contains(localKey(x, y + 1, z))) {
            addQuad(quads, matrix, p010, p011, p111, p110, topRed, topGreen, topBlue, alpha);
        }
        if (!occupied.contains(localKey(x, y - 1, z))) {
            addQuad(quads, matrix, p000, p100, p101, p001, bottomRed, bottomGreen, bottomBlue, alpha);
        }
        if (!occupied.contains(localKey(x + 1, y, z))) {
            addQuad(quads, matrix, p100, p110, p111, p101, sideXRed, sideXGreen, sideXBlue, alpha);
        }
        if (!occupied.contains(localKey(x - 1, y, z))) {
            addQuad(quads, matrix, p000, p001, p011, p010, sideXRed, sideXGreen, sideXBlue, alpha);
        }
        if (!occupied.contains(localKey(x, y, z + 1))) {
            addQuad(quads, matrix, p001, p101, p111, p011, sideZRed, sideZGreen, sideZBlue, alpha);
        }
        if (!occupied.contains(localKey(x, y, z - 1))) {
            addQuad(quads, matrix, p000, p010, p110, p100, sideZRed, sideZGreen, sideZBlue, alpha);
        }
    }

    private static void addQuad(
            BufferBuilder quads,
            Matrix4f matrix,
            Vec3d a,
            Vec3d b,
            Vec3d c,
            Vec3d d,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        addVertex(quads, matrix, a, red, green, blue, alpha);
        addVertex(quads, matrix, b, red, green, blue, alpha);
        addVertex(quads, matrix, c, red, green, blue, alpha);
        addVertex(quads, matrix, d, red, green, blue, alpha);
    }

    private static void addVertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3d position,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        buffer.vertex(matrix, (float) position.x, (float) position.y, (float) position.z).color(red, green, blue, alpha);
    }

    private static void drawScreenTriangle() {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f identity = new Matrix4f().identity();
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        buffer.vertex(identity, -1.0F, -1.0F, 0.0F).color(255, 255, 255, 255);
        buffer.vertex(identity, 3.0F, -1.0F, 0.0F).color(255, 255, 255, 255);
        buffer.vertex(identity, -1.0F, 3.0F, 0.0F).color(255, 255, 255, 255);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static Set<Long> buildOccupiedSet(PortalInstance portal) {
        Set<Long> occupied = new HashSet<>(portal.renderBlocks().size() * 2);
        for (PortalRenderBlock block : portal.renderBlocks()) {
            int x = (int) block.localBlockPosition().x;
            int y = (int) block.localBlockPosition().y;
            int z = (int) block.localBlockPosition().z;
            occupied.add(localKey(x, y, z));
        }
        return occupied;
    }

    private static long localKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static int scaleChannel(int value, float factor) {
        int scaled = Math.round(value * factor);
        if (scaled < 0) {
            return 0;
        }
        return Math.min(scaled, 255);
    }
}
