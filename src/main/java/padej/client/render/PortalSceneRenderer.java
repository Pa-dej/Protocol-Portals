package padej.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PortalSceneRenderer {
    private static final int OUTER_STENCIL_VALUE = 0;
    private static final int THIS_PORTAL_STENCIL_VALUE = 1;
    private static final double MAX_PORTAL_RENDER_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double BASE_MAX_BLOCK_RENDER_DISTANCE = 256.0D;
    private static final double PORTAL_FRONT_CLIP_EPSILON = 1.0E-3D;

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
        float tickDelta = context.tickCounter().getTickDelta(false);
        int currentFps = MinecraftClient.getInstance().getCurrentFps();
        double dynamicBlockRenderDistanceSq = computeDynamicPortalBlockDistanceSq(portals.size(), currentFps);
        Map<String, SceneBlockRenderView> sceneViewCache = new HashMap<>();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());

        try {
            renderDebugPlanes(debugPlanes, matrix);

            if (portals.isEmpty()) {
                return;
            }

            if (!stencilReadyThisFrame) {
                renderFallbackWithoutStencil(portals, camera, tickDelta, sceneViewCache, dynamicBlockRenderDistanceSq);
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
                renderPortalContentInStencil(portal, camera, tickDelta, sceneViewCache, dynamicBlockRenderDistanceSq);
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
        portals.sort(Comparator.comparingDouble(p -> squaredHorizontalDistance(camera, p.center())));
        return portals;
    }

    private boolean isPortalValidForRendering(PortalInstance portal, Vec3d camera) {
        return squaredHorizontalDistance(camera, portal.center()) <= MAX_PORTAL_RENDER_DISTANCE_SQ;
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

    private void renderPortalContentInStencil(
            PortalInstance portal,
            Vec3d camera,
            float tickDelta,
            Map<String, SceneBlockRenderView> sceneViewCache,
            double maxBlockRenderDistanceSq
    ) {
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        // World-aligned mode: blocks are placed along world axes, not along portal normal,
        // so portal-plane half-space clipping would incorrectly discard large parts of the scene.
        // The stencil + depth buffer together handle correct visibility - no plane clip needed.
        renderPortalBlocks(portal, camera, false, tickDelta, sceneViewCache, maxBlockRenderDistanceSq);
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
        // Reset any nested/non-zero stencil values back to the outer layer.
        // With multiple portals per frame this prevents one portal's mask from leaking into the next.
        GL11.glStencilFunc(GL11.GL_GREATER, maxStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        RenderSystem.disableDepthTest();
        drawScreenTriangle();
        RenderSystem.enableDepthTest();
        GL11.glStencilFunc(GL11.GL_EQUAL, maxStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
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
            addPlaneQuad(
                    fills,
                    matrix,
                    plane.center(),
                    plane.right(),
                    plane.up(),
                    plane.width(),
                    plane.height(),
                    255,
                    96,
                    64,
                    96
            );
        }
        BufferRenderer.drawWithGlobalProgram(fills.end());

        BufferBuilder frame = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (DebugPlaneInstance plane : debugPlanes) {
            addPlaneFrame(
                    frame,
                    matrix,
                    plane.center(),
                    plane.right(),
                    plane.up(),
                    plane.width(),
                    plane.height(),
                    255,
                    200,
                    48,
                    255
            );
        }
        BufferRenderer.drawWithGlobalProgram(frame.end());

        RenderSystem.disableDepthTest();
        BufferBuilder xrayFrame = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (DebugPlaneInstance plane : debugPlanes) {
            addPlaneFrame(
                    xrayFrame,
                    matrix,
                    plane.center(),
                    plane.right(),
                    plane.up(),
                    plane.width(),
                    plane.height(),
                    255,
                    255,
                    255,
                    255
            );
        }
        BufferRenderer.drawWithGlobalProgram(xrayFrame.end());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private void renderFallbackWithoutStencil(
            List<PortalInstance> portals,
            Vec3d camera,
            float tickDelta,
            Map<String, SceneBlockRenderView> sceneViewCache,
            double maxBlockRenderDistanceSq
    ) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();

        if (!fallbackModeLogged) {
            System.err.println("[Protocol Portals] Rendering portal content in fallback mode without stencil clipping.");
            fallbackModeLogged = true;
        }

        for (PortalInstance portal : portals) {
            if (!isPortalValidForRendering(portal, camera)) {
                continue;
            }
            renderPortalBlocks(portal, camera, false, tickDelta, sceneViewCache, maxBlockRenderDistanceSq);
        }

        MatrixStack overlayStack = new MatrixStack();
        overlayStack.translate(-camera.x, -camera.y, -camera.z);
        drawPortalFrames(portals, overlayStack.peek().getPositionMatrix());
        RenderSystem.enableCull();
    }

    private void renderPortalBlocks(
            PortalInstance portal,
            Vec3d camera,
            boolean clipAgainstPortalPlane,
            float tickDelta,
            Map<String, SceneBlockRenderView> sceneViewCache,
            double maxBlockRenderDistanceSq
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        BlockRenderManager blockRenderManager = client.getBlockRenderManager();
        var blockEntityRenderDispatcher = client.getBlockEntityRenderDispatcher();
        VertexConsumerProvider.Immediate vertexConsumers = client.getBufferBuilders().getEntityVertexConsumers();
        MatrixStack blockMatrices = new MatrixStack();
        blockMatrices.translate(-camera.x, -camera.y, -camera.z);
        SceneBlockRenderView sceneView = sceneViewCache.computeIfAbsent(
                portal.sceneName(),
                sceneName -> SceneBlockRenderView.fromPortal(client, portal)
        );
        Random random = Random.create();
        Map<RenderLayer, List<QueuedPortalBlock>> batchedBlocks = new HashMap<>();
        List<QueuedPortalBlock> blockEntitiesToRender = new ArrayList<>();

        for (PortalRenderBlock block : portal.renderBlocks()) {
            BlockState state = block.state();
            if (state.isAir()) {
                continue;
            }

            Vec3d localBlockPosition = block.localBlockPosition();
            Vec3d worldMin = sceneBlockMin(portal, localBlockPosition);
            // Block center = corner + (0.5, 0.5, 0.5) in world axes (no portal-axis dependency).
            Vec3d worldCenter = worldMin.add(0.5D, 0.5D, 0.5D);

            if (squaredHorizontalDistance(camera, worldCenter) > maxBlockRenderDistanceSq) {
                continue;
            }

            if (clipAgainstPortalPlane && !isInPortalContentHalfSpace(portal, worldCenter, camera)) {
                continue;
            }

            BlockPos localPos = BlockPos.ofFloored(localBlockPosition);
            QueuedPortalBlock queuedBlock = new QueuedPortalBlock(block, state, localPos, worldMin, block.packedLight());
            batchedBlocks.computeIfAbsent(RenderLayers.getBlockLayer(state), unused -> new ArrayList<>()).add(queuedBlock);
            if (state.hasBlockEntity()) {
                blockEntitiesToRender.add(queuedBlock);
            }
        }

        int rendered = 0;
        for (Map.Entry<RenderLayer, List<QueuedPortalBlock>> layerEntry : batchedBlocks.entrySet()) {
            VertexConsumer layerConsumer = vertexConsumers.getBuffer(layerEntry.getKey());
            for (QueuedPortalBlock queuedBlock : layerEntry.getValue()) {
                random.setSeed(queuedBlock.state().getRenderingSeed(queuedBlock.localPos()));
                blockMatrices.push();
                Vec3d worldMin = queuedBlock.worldMin();
                blockMatrices.translate(worldMin.x, worldMin.y, worldMin.z);
                blockRenderManager.renderBlock(
                        queuedBlock.state(),
                        queuedBlock.localPos(),
                        sceneView,
                        blockMatrices,
                        layerConsumer,
                        true,
                        random
                );
                blockMatrices.pop();
                rendered++;
            }
        }

        for (QueuedPortalBlock queuedBlock : blockEntitiesToRender) {
            BlockState state = queuedBlock.state();
            BlockEntity blockEntity = null;
            if (state.getBlock() instanceof BlockEntityProvider provider) {
                blockEntity = provider.createBlockEntity(queuedBlock.localPos(), state);
            }
            if (blockEntity == null) {
                continue;
            }

            blockEntity.setWorld(client.world);
            if (queuedBlock.source().blockEntityNbt() != null) {
                blockEntity.read(queuedBlock.source().blockEntityNbt().copy(), client.world.getRegistryManager());
            }

            BlockEntityRenderer<BlockEntity> renderer = blockEntityRenderDispatcher.get(blockEntity);
            if (renderer == null) {
                continue;
            }

            blockMatrices.push();
            Vec3d worldMin = queuedBlock.worldMin();
            blockMatrices.translate(worldMin.x, worldMin.y, worldMin.z);
            renderer.render(
                    blockEntity,
                    tickDelta,
                    blockMatrices,
                    vertexConsumers,
                    queuedBlock.light(),
                    OverlayTexture.DEFAULT_UV
            );
            blockMatrices.pop();
        }

        if (rendered > 0 || !blockEntitiesToRender.isEmpty()) {
            vertexConsumers.draw();
        }
    }

    private static ShaderProgram getPortalAreaShader() {
        ShaderProgram program = ProtocolPortalsShaders.portalAreaProgram();
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
        // World-aligned: relX = east (+X), relY = up (+Y), relZ = south (+Z).
        // sceneAnchor is the portal center, so blocks appear at their true world-relative offsets.
        return portal.sceneAnchor().add(localBlockPosition);
    }

    private static void drawPortalPlaneQuad(PortalInstance portal, Matrix4f matrix, int red, int green, int blue, int alpha) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        addPlaneQuad(buffer, matrix, portal.center(), portal.right(), portal.up(), portal.width(), portal.height(), red, green, blue, alpha);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void addPortalFrame(BufferBuilder lines, Matrix4f matrix, PortalInstance portal) {
        addPlaneFrame(lines, matrix, portal.center(), portal.right(), portal.up(), portal.width(), portal.height(), 35, 235, 255, 255);
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

    private static boolean isInPortalContentHalfSpace(PortalInstance portal, Vec3d point, Vec3d camera) {
        // Choose clipping half-space from viewer side so portal remains visible from both sides.
        double viewerSide = camera.subtract(portal.center()).dotProduct(portal.normal());
        double signedDistance = point.subtract(portal.center()).dotProduct(portal.normal());
        if (Math.abs(viewerSide) <= PORTAL_FRONT_CLIP_EPSILON) {
            return true;
        }
        return viewerSide * signedDistance <= PORTAL_FRONT_CLIP_EPSILON;
    }

    private record QueuedPortalBlock(
            PortalRenderBlock source,
            BlockState state,
            BlockPos localPos,
            Vec3d worldMin,
            int light
    ) {
    }

    private static double computeDynamicPortalBlockDistanceSq(int portalCount, int currentFps) {
        double maxDistance = BASE_MAX_BLOCK_RENDER_DISTANCE;

        if (portalCount >= 4) {
            maxDistance = Math.min(maxDistance, 192.0D);
        }
        if (portalCount >= 8) {
            maxDistance = Math.min(maxDistance, 128.0D);
        }

        if (currentFps > 0) {
            if (currentFps < 45) {
                maxDistance = Math.min(maxDistance, 160.0D);
            }
            if (currentFps < 30) {
                maxDistance = Math.min(maxDistance, 112.0D);
            }
            if (currentFps < 20) {
                maxDistance = Math.min(maxDistance, 72.0D);
            }
        }

        return maxDistance * maxDistance;
    }

    private static double squaredHorizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}

