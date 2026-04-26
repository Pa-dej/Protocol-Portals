package padej.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUsage;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import padej.client.portal.DebugPlaneInstance;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalManager;
import padej.client.portal.PortalRenderBlock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PortalSceneRenderer {
    private static final int SCENE_SECTION_SIZE = 16;
    private static final int OUTER_STENCIL_VALUE = 0;
    private static final int THIS_PORTAL_STENCIL_VALUE = 1;
    private static final int MAX_SCENE_SECTIONS_BUILD_PER_FRAME = 4;
    private static final double UNLIMITED_BLOCK_RENDER_DISTANCE_SQ = Double.POSITIVE_INFINITY;
    private static final double PORTAL_FRONT_CLIP_EPSILON = 1.0E-3D;
    private static final double PORTAL_VIEW_MARGIN = 0.5D;
    private static final double INNER_FRUSTUM_CULL_EPSILON = 1.0E-4D;
    private static final String[] IRIS_WORLD_RENDERER_PIPELINE_FIELD_CANDIDATES = {
            "pipeline",
            "renderingPipeline",
            "worldRenderingPipeline"
    };
    private static volatile boolean portalScissorsEnabled = false;
    @Nullable
    private static Field irisWorldRendererPipelineField;
    private static boolean irisWorldRendererPipelineFieldResolved = false;

    private final PortalManager portalManager;
    private final Map<SceneCacheKey, CachedScene> cachedScenes = new HashMap<>();
    private final ExecutorService scenePrepareExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            runnable -> {
                Thread thread = new Thread(runnable, "protocol-portals-scene-prepare");
                thread.setDaemon(true);
                return thread;
            }
    );
    private boolean stencilReadyThisFrame = false;
    private boolean irisModeThisFrame = false;
    private boolean fabulousModeThisFrame = false;
    private boolean stencilUnavailableLogged = false;
    private boolean fallbackModeLogged = false;
    private boolean irisFallbackLogged = false;
    private boolean fabulousFallbackLogged = false;
    private boolean irisPipelineBypassUnavailableLogged = false;
    private boolean irisPipelineBypassRestoreFailedLogged = false;
    private boolean irisCompositeShaderMissingLogged = false;
    private boolean irisCompositeShaderInvalidLogged = false;
    private boolean layerShaderInvalidLogged = false;
    @Nullable
    private SimpleFramebuffer irisPortalFramebuffer;
    private int irisPortalFramebufferWidth = -1;
    private int irisPortalFramebufferHeight = -1;
    private long renderFrameIndex = 0L;

    public PortalSceneRenderer(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    public static void setPortalScissorsEnabled(boolean enabled) {
        portalScissorsEnabled = enabled;
    }

    public static boolean isPortalScissorsEnabled() {
        return portalScissorsEnabled;
    }

    public void register() {
        WorldRenderEvents.START.register(this::prepareFrame);
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderPortalsAfterEntities);
        WorldRenderEvents.LAST.register(this::renderPortalsLast);
        WorldRenderEvents.END.register(this::endFrame);
    }

    private void renderPortalsAfterEntities(WorldRenderContext context) {
        if (irisModeThisFrame || fabulousModeThisFrame) {
            return;
        }
        renderPortals(context);
    }

    private void renderPortalsLast(WorldRenderContext context) {
        if (!irisModeThisFrame && !fabulousModeThisFrame) {
            return;
        }
        renderPortals(context);
    }

    private void prepareFrame(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer framebuffer = client.getFramebuffer();
        Vec3d camera = context.camera() != null ? context.camera().getPos() : null;
        Vec3d cameraForward = context.camera() != null ? cameraForwardVector(context.camera().getYaw(), context.camera().getPitch()) : null;

        if (camera == null) {
            PortalOuterFrustumCulling.clear();
            stencilReadyThisFrame = false;
            irisModeThisFrame = false;
            fabulousModeThisFrame = false;
            return;
        }

        irisModeThisFrame = isIrisShaderPackInUse();
        fabulousModeThisFrame = !irisModeThisFrame && MinecraftClient.isFabulousGraphicsOrBetter();
        List<PortalInstance> cullingPortals = sortedPortals(camera)
                .stream()
                .filter(portal -> isPortalValidForRendering(portal, camera))
                .filter(portal -> cameraForward == null || isPortalInViewHemisphere(portal, camera, cameraForward))
                .toList();
        PortalOuterFrustumCulling.update(cullingPortals, camera);

        if (irisModeThisFrame || fabulousModeThisFrame) {
            stencilReadyThisFrame = false;
            return;
        }

        stencilReadyThisFrame = ensureMainFramebufferStencil(framebuffer);
        if (!stencilReadyThisFrame) {
            PortalOuterFrustumCulling.clear();
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
        if (context.camera() == null) {
            return;
        }
        List<DebugPlaneInstance> debugPlanes = portalManager.debugPlanes();
        Vec3d camera = context.camera().getPos();
        Vec3d cameraForward = cameraForwardVector(context.camera().getYaw(), context.camera().getPitch());
        Frustum frustum = context.frustum();
        List<PortalInstance> portals = sortedPortals(camera);
        if (portals.isEmpty() && debugPlanes.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        float tickDelta = context.tickCounter().getTickDelta(false);
        renderFrameIndex++;
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = new Matrix4f(matrices.peek().getPositionMatrix());

        try {
            renderDebugPlanes(debugPlanes, matrix);

            if (portals.isEmpty()) {
                return;
            }

            if (cachedScenes.size() > 128) {
                clearCachedScenes();
            }

            if (irisModeThisFrame || fabulousModeThisFrame) {
                renderUsingIrisFramebuffer(
                        portals,
                        camera,
                        cameraForward,
                        frustum,
                        tickDelta,
                        matrix
                );
                return;
            }

            if (!stencilReadyThisFrame) {
                renderFallbackWithoutStencil(portals, camera, cameraForward, frustum, tickDelta, UNLIMITED_BLOCK_RENDER_DISTANCE_SQ, matrix);
                return;
            }

            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableCull();

            for (PortalInstance portal : portals) {
                if (!isPortalValidForRendering(portal, camera, cameraForward, frustum)) {
                    continue;
                }

                if (!renderPortalViewAreaToStencilAndDecideVisibility(portal, matrix)) {
                    continue;
                }

                clearDepthOfPortalViewArea(portal, matrix);
                renderPortalSkyBackground(portal, matrix, portal.skyColor());
                renderPortalContentInStencil(portal, camera, tickDelta, UNLIMITED_BLOCK_RENDER_DISTANCE_SQ);
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
        PortalOuterFrustumCulling.clear();
        if (stencilReadyThisFrame) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        } else if (GL11.glIsEnabled(GL11.GL_STENCIL_TEST)) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        stencilReadyThisFrame = false;
        irisModeThisFrame = false;
        fabulousModeThisFrame = false;
    }

    private List<PortalInstance> sortedPortals(Vec3d camera) {
        List<PortalInstance> portals = new ArrayList<>(portalManager.activePortals());
        portals.sort(Comparator.comparingDouble(p -> squaredHorizontalDistance(camera, p.center())));
        return portals;
    }

    private boolean isPortalValidForRendering(PortalInstance portal, Vec3d camera) {
        return true;
    }

    private boolean isPortalValidForRendering(PortalInstance portal, Vec3d camera, Vec3d cameraForward, Frustum frustum) {
        if (!isPortalValidForRendering(portal, camera)) {
            return false;
        }
        if (!isPortalInViewHemisphere(portal, camera, cameraForward)) {
            return false;
        }
        return frustum == null || isPortalVisibleInFrustum(portal, frustum);
    }

    private static boolean isPortalInViewHemisphere(PortalInstance portal, Vec3d camera, Vec3d cameraForward) {
        Vec3d toPortal = portal.center().subtract(camera);
        double toPortalLengthSq = toPortal.lengthSquared();
        if (toPortalLengthSq <= 1.0E-8D) {
            return true;
        }
        double invLength = 1.0D / Math.sqrt(toPortalLengthSq);
        Vec3d toPortalDirection = toPortal.multiply(invLength);
        return toPortalDirection.dotProduct(cameraForward) > 0.0D;
    }

    private static boolean isPortalVisibleInFrustum(PortalInstance portal, Frustum frustum) {
        return frustum.isVisible(computePortalVisibilityBox(portal));
    }

    private static Box computePortalVisibilityBox(PortalInstance portal) {
        Vec3d center = portal.center();
        Vec3d right = portal.right();
        Vec3d up = portal.up();
        double halfWidth = portal.width() * 0.5D + PORTAL_VIEW_MARGIN;
        double halfHeight = portal.height() * 0.5D + PORTAL_VIEW_MARGIN;

        double extentX = halfWidth * Math.abs(right.x) + halfHeight * Math.abs(up.x);
        double extentY = halfWidth * Math.abs(right.y) + halfHeight * Math.abs(up.y);
        double extentZ = halfWidth * Math.abs(right.z) + halfHeight * Math.abs(up.z);

        return new Box(
                center.x - extentX,
                center.y - extentY,
                center.z - extentZ,
                center.x + extentX,
                center.y + extentY,
                center.z + extentZ
        );
    }

    private boolean renderPortalViewAreaToStencilAndDecideVisibility(PortalInstance portal, Matrix4f matrix) {
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (cullWasEnabled) {
            RenderSystem.disableCull();
        }
        try {
            usePortalAreaShader();
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
        drawPortalPlaneQuadWithoutCull(portal, matrix, 255, 255, 255, 0);
        GL11.glDepthRange(0.0D, 1.0D);
    }

    private void renderPortalContentInStencil(
            PortalInstance portal,
            Vec3d camera,
            float tickDelta,
            double maxBlockRenderDistanceSq
    ) {
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        RenderSystem.enableCull();

        // World-aligned mode: blocks are placed along world axes, not along portal normal.
        // Portal-plane half-space clipping is therefore optional and exposed as a debug toggle.
        renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, maxBlockRenderDistanceSq, null);
    }

    private void renderPortalSkyBackground(PortalInstance portal, Matrix4f matrix, Vec3d skyColor) {
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDepthRange(1.0D, 1.0D);
        drawPortalPlaneQuadWithoutCull(
                portal,
                matrix,
                colorToByte(skyColor.x),
                colorToByte(skyColor.y),
                colorToByte(skyColor.z),
                255
        );
        GL11.glDepthRange(0.0D, 1.0D);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    private void restoreDepthOfPortalViewArea(PortalInstance portal, Matrix4f matrix) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        GL11.glDepthRange(0.0D, 1.0D);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, THIS_PORTAL_STENCIL_VALUE, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        drawPortalPlaneQuadWithoutCull(portal, matrix, 255, 255, 255, 0);
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
        usePortalAreaShader();
        boolean stencilWasEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        if (stencilWasEnabled) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }

        BufferBuilder lines = Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);
        for (PortalInstance portal : portals) {
            addPortalFrame(lines, matrix, portal);
        }
        BufferRenderer.drawWithGlobalProgram(lines.end());

        if (stencilWasEnabled) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        }
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
        usePortalAreaShader();

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

    private void renderUsingIrisFramebuffer(
            List<PortalInstance> portals,
            Vec3d camera,
            Vec3d cameraForward,
            Frustum frustum,
            float tickDelta,
            Matrix4f viewMatrix
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer mainFramebuffer = client.getFramebuffer();
        if (!ensureIrisPortalFramebuffer(client) || irisPortalFramebuffer == null) {
            if (irisModeThisFrame) {
                if (!irisFallbackLogged) {
                    System.err.println("[Protocol Portals] Iris fallback framebuffer is unavailable, using non-stencil fallback path.");
                    irisFallbackLogged = true;
                }
            } else if (!fabulousFallbackLogged) {
                System.err.println("[Protocol Portals] Fabulous offscreen framebuffer is unavailable, using non-stencil fallback path.");
                fabulousFallbackLogged = true;
            }
            renderFallbackWithoutStencil(
                    portals,
                    camera,
                    cameraForward,
                    frustum,
                    tickDelta,
                    UNLIMITED_BLOCK_RENDER_DISTANCE_SQ,
                    viewMatrix
            );
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        ShaderProgram compositeShader = getValidPortalCompositeShader();
        if (compositeShader == null) {
            renderFallbackWithoutStencil(
                    portals,
                    camera,
                    cameraForward,
                    frustum,
                    tickDelta,
                    UNLIMITED_BLOCK_RENDER_DISTANCE_SQ,
                    viewMatrix
            );
            return;
        }

        int framebufferWidth = client.getWindow().getFramebufferWidth();
        int framebufferHeight = client.getWindow().getFramebufferHeight();
        for (PortalInstance portal : portals) {
            if (!isPortalValidForRendering(portal, camera, cameraForward, frustum)) {
                continue;
            }

            Vec3d skyColor = portal.skyColor();
            irisPortalFramebuffer.setClearColor((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1.0F);
            irisPortalFramebuffer.beginWrite(true);
            irisPortalFramebuffer.clear();
            if (irisModeThisFrame) {
                renderPortalBlocksWithIrisPipelineBypass(portal, camera, tickDelta, UNLIMITED_BLOCK_RENDER_DISTANCE_SQ);
            } else {
                renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, UNLIMITED_BLOCK_RENDER_DISTANCE_SQ, irisPortalFramebuffer);
            }

            mainFramebuffer.beginWrite(true);
            compositeIrisPortalFramebuffer(portal, viewMatrix, framebufferWidth, framebufferHeight, compositeShader);
        }

        mainFramebuffer.beginWrite(true);
        drawPortalFrames(portals, viewMatrix);
        RenderSystem.enableCull();
    }

    private void prepareDepthForIrisComposite(PortalInstance portal, Matrix4f viewMatrix) {
        usePortalAreaShader();
        RenderSystem.enableDepthTest();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        GL11.glDepthRange(1.0D, 1.0D);
        drawPortalPlaneQuadWithoutCull(portal, viewMatrix, 0, 0, 0, 0);
        GL11.glDepthRange(0.0D, 1.0D);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    private void compositeIrisPortalFramebuffer(
            PortalInstance portal,
            Matrix4f viewMatrix,
            int framebufferWidth,
            int framebufferHeight,
            ShaderProgram compositeShader
    ) {
        if (irisPortalFramebuffer == null) {
            return;
        }
        int colorAttachment = irisPortalFramebuffer.getColorAttachment();
        if (colorAttachment <= 0) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        // Keep portal composite in the world depth chain so later fabulous passes
        // (clouds/particles/translucent block entities) cannot leak through it.
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.setShader(compositeShader);
        RenderSystem.setShaderTexture(0, colorAttachment);
        if (compositeShader.screenSize != null) {
            compositeShader.screenSize.set((float) framebufferWidth, (float) framebufferHeight);
        }
        drawPortalPlaneQuadWithoutCull(portal, viewMatrix, 255, 255, 255, 255);
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    @Nullable
    private ShaderProgram getValidPortalCompositeShader() {
        ShaderProgram compositeShader = ProtocolPortalsShaders.portalCompositeProgram();
        if (compositeShader == null) {
            if (!irisCompositeShaderMissingLogged) {
                System.err.println("[Protocol Portals] portalCompositeProgram is null under Iris (shader reload may be in progress).");
                irisCompositeShaderMissingLogged = true;
            }
            return null;
        }
        if (compositeShader.getGlRef() <= 0) {
            if (!irisCompositeShaderInvalidLogged) {
                System.err.println("[Protocol Portals] portalCompositeProgram has invalid GL handle under Iris.");
                irisCompositeShaderInvalidLogged = true;
            }
            return null;
        }
        irisCompositeShaderMissingLogged = false;
        irisCompositeShaderInvalidLogged = false;
        return compositeShader;
    }

    private boolean ensureIrisPortalFramebuffer(MinecraftClient client) {
        int framebufferWidth = client.getWindow().getFramebufferWidth();
        int framebufferHeight = client.getWindow().getFramebufferHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return false;
        }

        if (irisPortalFramebuffer == null) {
            irisPortalFramebuffer = new SimpleFramebuffer(framebufferWidth, framebufferHeight, true);
            irisPortalFramebufferWidth = framebufferWidth;
            irisPortalFramebufferHeight = framebufferHeight;
            return true;
        }

        if (irisPortalFramebufferWidth != framebufferWidth || irisPortalFramebufferHeight != framebufferHeight) {
            irisPortalFramebuffer.resize(framebufferWidth, framebufferHeight);
            irisPortalFramebufferWidth = framebufferWidth;
            irisPortalFramebufferHeight = framebufferHeight;
        }
        return true;
    }

    private void renderPortalBlocksWithIrisPipelineBypass(
            PortalInstance portal,
            Vec3d camera,
            float tickDelta,
            double maxBlockRenderDistanceSq
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        Field pipelineField = getIrisWorldRendererPipelineField(client);
        if (pipelineField == null) {
            if (!irisPipelineBypassUnavailableLogged) {
                System.err.println("[Protocol Portals] Iris pipeline bypass is unavailable; portal offscreen framebuffer may render empty.");
                irisPipelineBypassUnavailableLogged = true;
            }
            renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, maxBlockRenderDistanceSq, irisPortalFramebuffer);
            return;
        }

        Object originalPipeline;
        try {
            originalPipeline = pipelineField.get(client.worldRenderer);
        } catch (IllegalAccessException exception) {
            if (!irisPipelineBypassUnavailableLogged) {
                System.err.println("[Protocol Portals] Cannot access Iris world-render pipeline field; rendering without bypass.");
                irisPipelineBypassUnavailableLogged = true;
            }
            renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, maxBlockRenderDistanceSq, irisPortalFramebuffer);
            return;
        }

        boolean pipelineDisabled = false;
        if (originalPipeline != null) {
            try {
                pipelineField.set(client.worldRenderer, null);
                pipelineDisabled = true;
            } catch (IllegalAccessException exception) {
                if (!irisPipelineBypassUnavailableLogged) {
                    System.err.println("[Protocol Portals] Cannot disable Iris world-render pipeline; rendering without bypass.");
                    irisPipelineBypassUnavailableLogged = true;
                }
            }
        }

        try {
            renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, maxBlockRenderDistanceSq, irisPortalFramebuffer);
        } finally {
            if (pipelineDisabled) {
                try {
                    pipelineField.set(client.worldRenderer, originalPipeline);
                } catch (IllegalAccessException exception) {
                    if (!irisPipelineBypassRestoreFailedLogged) {
                        System.err.println("[Protocol Portals] Failed to restore Iris world-render pipeline after portal render.");
                        irisPipelineBypassRestoreFailedLogged = true;
                    }
                }
            }
        }
    }

    @Nullable
    private static Field getIrisWorldRendererPipelineField(MinecraftClient client) {
        if (client.worldRenderer == null) {
            return null;
        }
        if (irisWorldRendererPipelineFieldResolved) {
            return irisWorldRendererPipelineField;
        }
        irisWorldRendererPipelineFieldResolved = true;
        Class<?> worldRendererClass = client.worldRenderer.getClass();
        for (String candidateName : IRIS_WORLD_RENDERER_PIPELINE_FIELD_CANDIDATES) {
            Field candidateField = findFieldInHierarchy(worldRendererClass, candidateName);
            if (candidateField != null && isLikelyIrisPipelineFieldType(candidateField.getType())) {
                irisWorldRendererPipelineField = candidateField;
                break;
            }
        }
        return irisWorldRendererPipelineField;
    }

    private static boolean isLikelyIrisPipelineFieldType(Class<?> fieldType) {
        String typeName = fieldType.getName();
        String lowerCaseTypeName = typeName.toLowerCase(java.util.Locale.ROOT);
        return lowerCaseTypeName.contains("iris")
                || lowerCaseTypeName.contains("pipeline");
    }

    @Nullable
    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> currentClass = clazz;
        while (currentClass != null) {
            try {
                Field field = currentClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                currentClass = currentClass.getSuperclass();
            } catch (LinkageError exception) {
                return null;
            }
        }
        return null;
    }

    private static boolean isIrisShaderPackInUse() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object irisApi = irisApiClass.getMethod("getInstance").invoke(null);
            Object shaderPackInUse = irisApiClass.getMethod("isShaderPackInUse").invoke(irisApi);
            return shaderPackInUse instanceof Boolean enabled && enabled;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private void renderFallbackWithoutStencil(
            List<PortalInstance> portals,
            Vec3d camera,
            Vec3d cameraForward,
            Frustum frustum,
            float tickDelta,
            double maxBlockRenderDistanceSq,
            Matrix4f viewMatrix
    ) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();

        if (!fallbackModeLogged) {
            System.err.println("[Protocol Portals] Rendering portal content in fallback mode without stencil clipping.");
            fallbackModeLogged = true;
        }

        for (PortalInstance portal : portals) {
            if (!isPortalValidForRendering(portal, camera, cameraForward, frustum)) {
                continue;
            }
            renderPortalBlocks(portal, camera, portalScissorsEnabled, tickDelta, maxBlockRenderDistanceSq, null);
        }

        drawPortalFrames(portals, viewMatrix);
        RenderSystem.enableCull();
    }

    private void renderPortalBlocks(
            PortalInstance portal,
            Vec3d camera,
            boolean clipAgainstPortalPlane,
            float tickDelta,
            double maxBlockRenderDistanceSq,
            @Nullable Framebuffer forceFramebuffer
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        CachedScene cachedScene = getOrBuildCachedScene(client, portal);
        if (cachedScene.sections().isEmpty() && cachedScene.blockEntities().isEmpty()) {
            return;
        }

        if (squaredHorizontalDistance(camera, portal.sceneAnchor()) > maxBlockRenderDistanceSq) {
            return;
        }
        if (clipAgainstPortalPlane && !isInPortalContentHalfSpace(portal, portal.sceneAnchor(), camera)) {
            return;
        }

        InnerPortalFrustum innerFrustum = InnerPortalFrustum.fromPortal(portal, camera);
        float chunkOffsetX = (float) (portal.sceneAnchor().x - camera.x);
        float chunkOffsetY = (float) (portal.sceneAnchor().y - camera.y);
        float chunkOffsetZ = (float) (portal.sceneAnchor().z - camera.z);
        Matrix4f shiftedModelViewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        shiftedModelViewMatrix.translate(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        Matrix4f projectionMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());

        int renderedMeshes = 0;
        for (CachedSection section : cachedScene.sections()) {
            if (innerFrustum != null) {
                double sectionMinX = portal.sceneAnchor().x + section.sectionKey().minBlockX();
                double sectionMinY = portal.sceneAnchor().y + section.sectionKey().minBlockY();
                double sectionMinZ = portal.sceneAnchor().z + section.sectionKey().minBlockZ();
                double sectionMaxX = sectionMinX + SCENE_SECTION_SIZE;
                double sectionMaxY = sectionMinY + SCENE_SECTION_SIZE;
                double sectionMaxZ = sectionMinZ + SCENE_SECTION_SIZE;
                if (innerFrustum.isFullyOutside(sectionMinX, sectionMinY, sectionMinZ, sectionMaxX, sectionMaxY, sectionMaxZ)) {
                    continue;
                }
            }

            for (CachedLayerMesh mesh : section.meshes()) {
                mesh.layer().startDrawing();
                if (forceFramebuffer != null) {
                    forceFramebuffer.beginWrite(false);
                }
                try {
                    ShaderProgram shader = RenderSystem.getShader();
                    if (shader == null) {
                        continue;
                    }
                    if (shader.getGlRef() <= 0) {
                        if (!layerShaderInvalidLogged) {
                            System.err.println("[Protocol Portals] Layer shader has invalid GL program handle under Iris, skipping portal mesh draw.");
                            layerShaderInvalidLogged = true;
                        }
                        continue;
                    }

                    try {
                        mesh.vertexBuffer().bind();
                        mesh.vertexBuffer().draw(shiftedModelViewMatrix, projectionMatrix, shader);
                        renderedMeshes++;
                    } finally {
                        VertexBuffer.unbind();
                    }
                } finally {
                    mesh.layer().endDrawing();
                }
            }
        }

        int renderedBlockEntities = 0;
        if (!cachedScene.blockEntities().isEmpty()) {
            VertexConsumerProvider.Immediate vertexConsumers = client.getBufferBuilders().getEntityVertexConsumers();
            MatrixStack blockMatrices = new MatrixStack();
            blockMatrices.translate(-camera.x, -camera.y, -camera.z);

            for (CachedPortalBlockEntity cachedBlockEntity : cachedScene.blockEntities()) {
                if (clipAgainstPortalPlane && !isInPortalContentHalfSpace(
                        portal,
                        portal.sceneAnchor().add(cachedBlockEntity.localMin()),
                        camera
                )) {
                    continue;
                }

                Vec3d worldMin = sceneBlockMin(portal, cachedBlockEntity.localMin());
                if (forceFramebuffer != null && !isUnitBlockFullyInsidePortalWindow(portal, worldMin)) {
                    // In fabulous/offscreen rendering some block-entity layers can bypass our
                    // portal composite clip. Drop edge block entities that would poke outside.
                    continue;
                }
                if (innerFrustum != null && innerFrustum.isFullyOutside(
                        worldMin.x,
                        worldMin.y,
                        worldMin.z,
                        worldMin.x + 1.0D,
                        worldMin.y + 1.0D,
                        worldMin.z + 1.0D
                )) {
                    continue;
                }

                blockMatrices.push();
                blockMatrices.translate(worldMin.x, worldMin.y, worldMin.z);
                cachedBlockEntity.renderer().render(
                        cachedBlockEntity.blockEntity(),
                        tickDelta,
                        blockMatrices,
                        vertexConsumers,
                        cachedBlockEntity.light(),
                        OverlayTexture.DEFAULT_UV
                );
                blockMatrices.pop();
                renderedBlockEntities++;
            }

            if (renderedBlockEntities > 0) {
                vertexConsumers.draw();
            }
        }

    }
    private static void usePortalAreaShader() {
        ShaderProgram program = ProtocolPortalsShaders.portalAreaProgram();
        if (program != null && program.getGlRef() > 0) {
            RenderSystem.setShader(program);
            return;
        }
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
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

    private void clearCachedScenes() {
        for (CachedScene scene : cachedScenes.values()) {
            scene.close();
        }
        cachedScenes.clear();
    }

    private static Vec3d sceneBlockMin(PortalInstance portal, Vec3d localBlockPosition) {
        // World-aligned: relX = east (+X), relY = up (+Y), relZ = south (+Z).
        // sceneAnchor is the portal center, so blocks appear at their true world-relative offsets.
        return portal.sceneAnchor().add(localBlockPosition);
    }

    private static Vec3d cameraForwardVector(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double horizontal = Math.cos(pitch);
        return new Vec3d(
                -Math.sin(yaw) * horizontal,
                -Math.sin(pitch),
                Math.cos(yaw) * horizontal
        );
    }

    private static Vec3d[] portalCorners(PortalInstance portal) {
        Vec3d halfRight = portal.right().multiply(portal.width() * 0.5D);
        Vec3d halfUp = portal.up().multiply(portal.height() * 0.5D);
        Vec3d center = portal.center();
        return new Vec3d[]{
                center.subtract(halfRight).subtract(halfUp),
                center.add(halfRight).subtract(halfUp),
                center.add(halfRight).add(halfUp),
                center.subtract(halfRight).add(halfUp)
        };
    }

    private static void drawPortalPlaneQuad(PortalInstance portal, Matrix4f matrix, int red, int green, int blue, int alpha) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        addPlaneQuad(buffer, matrix, portal.center(), portal.right(), portal.up(), portal.width(), portal.height(), red, green, blue, alpha);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void drawPortalPlaneQuadWithoutCull(PortalInstance portal, Matrix4f matrix, int red, int green, int blue, int alpha) {
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (cullWasEnabled) {
            RenderSystem.disableCull();
        }
        try {
            drawPortalPlaneQuad(portal, matrix, red, green, blue, alpha);
        } finally {
            if (cullWasEnabled) {
                RenderSystem.enableCull();
            }
        }
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
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        Matrix4f identity = new Matrix4f().identity();
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        buffer.vertex(identity, -1.0F, -1.0F, 0.0F).color(255, 255, 255, 255);
        buffer.vertex(identity, 3.0F, -1.0F, 0.0F).color(255, 255, 255, 255);
        buffer.vertex(identity, -1.0F, 3.0F, 0.0F).color(255, 255, 255, 255);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static int colorToByte(double color) {
        double clamped = Math.max(0.0D, Math.min(1.0D, color));
        return (int) Math.round(clamped * 255.0D);
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

    private static boolean isUnitBlockFullyInsidePortalWindow(PortalInstance portal, Vec3d worldMin) {
        Vec3d right = portal.right().normalize();
        Vec3d up = portal.up().normalize();
        double halfWidth = portal.width() * 0.5D - 1.0E-3D;
        double halfHeight = portal.height() * 0.5D - 1.0E-3D;
        if (halfWidth <= 0.0D || halfHeight <= 0.0D) {
            return false;
        }

        Vec3d center = portal.center();
        for (int ox = 0; ox <= 1; ox++) {
            for (int oy = 0; oy <= 1; oy++) {
                for (int oz = 0; oz <= 1; oz++) {
                    Vec3d corner = worldMin.add(ox, oy, oz);
                    Vec3d relative = corner.subtract(center);
                    double rightOffset = Math.abs(relative.dotProduct(right));
                    double upOffset = Math.abs(relative.dotProduct(up));
                    if (rightOffset > halfWidth || upOffset > halfHeight) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private CachedScene getOrBuildCachedScene(MinecraftClient client, PortalInstance portal) {
        SceneCacheKey key = SceneCacheKey.fromPortal(portal);
        CachedScene cachedScene = cachedScenes.get(key);
        if (cachedScene == null) {
            cachedScene = createPendingCachedScene(client, portal);
            cachedScenes.put(key, cachedScene);
        }
        cachedScene.buildNextBatch(client, renderFrameIndex, MAX_SCENE_SECTIONS_BUILD_PER_FRAME);
        return cachedScene;
    }

    private CachedScene createPendingCachedScene(MinecraftClient client, PortalInstance portal) {
        if (client.world == null) {
            return CachedScene.completed();
        }
        ClientWorld world = client.world;
        CompletableFuture<PreparedSceneData> prepareFuture = CompletableFuture.supplyAsync(
                () -> prepareSceneData(world, portal),
                scenePrepareExecutor
        );
        return CachedScene.preparing(world, prepareFuture);
    }

    private static PreparedSceneData prepareSceneData(ClientWorld world, PortalInstance portal) {
        LinkedHashMap<SectionKey, List<PortalRenderBlock>> blocksBySection = new LinkedHashMap<>();
        for (PortalRenderBlock block : portal.renderBlocks()) {
            if (block.state().isAir()) {
                continue;
            }
            BlockPos localPos = BlockPos.ofFloored(block.localBlockPosition());
            SectionKey sectionKey = SectionKey.fromLocalPos(localPos);
            blocksBySection.computeIfAbsent(sectionKey, unused -> new ArrayList<>()).add(block);
        }

        List<SectionBuildPlan> sectionPlans = new ArrayList<>(blocksBySection.size());
        for (Map.Entry<SectionKey, List<PortalRenderBlock>> entry : blocksBySection.entrySet()) {
            sectionPlans.add(new SectionBuildPlan(entry.getKey(), entry.getValue()));
        }
        return new PreparedSceneData(
                SceneBlockRenderView.fromData(world, portal.renderBlocks(), portal.lightSamples()),
                sectionPlans
        );
    }

    private static SectionBuildResult buildSectionMeshes(
            ClientWorld world,
            SceneBlockRenderView sceneView,
            BlockRenderManager blockRenderManager,
            BlockEntityRenderDispatcher blockEntityRenderDispatcher,
            Random random,
            SectionBuildPlan sectionPlan
    ) {
        SectionKey sectionKey = sectionPlan.sectionKey();
        MatrixStack buildMatrices = new MatrixStack();
        Map<RenderLayer, MeshBuildContext> meshBuilders = new LinkedHashMap<>();
        List<MeshBuildContext> meshContexts = new ArrayList<>();
        List<CachedPortalBlockEntity> blockEntities = new ArrayList<>();

        try {
            for (PortalRenderBlock block : sectionPlan.blocks()) {
                BlockState state = block.state();
                if (state.isAir()) {
                    continue;
                }

                BlockPos localPos = BlockPos.ofFloored(block.localBlockPosition());
                Vec3d localMin = block.localBlockPosition();
                RenderLayer layer = RenderLayers.getBlockLayer(state);
                MeshBuildContext blockMeshContext = meshBuilders.computeIfAbsent(layer, key -> {
                    MeshBuildContext created = createMeshBuildContext(key);
                    meshContexts.add(created);
                    return created;
                });

                random.setSeed(state.getRenderingSeed(localPos));
                buildMatrices.push();
                buildMatrices.translate(localMin.x, localMin.y, localMin.z);
                blockRenderManager.renderBlock(
                        state,
                        localPos,
                        sceneView,
                        buildMatrices,
                        blockMeshContext.bufferBuilder(),
                        true,
                        random
                );
                buildMatrices.pop();

                FluidState fluidState = state.getFluidState();
                if (!fluidState.isEmpty()) {
                    RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);
                    MeshBuildContext fluidMeshContext = meshBuilders.computeIfAbsent(fluidLayer, key -> {
                        MeshBuildContext created = createMeshBuildContext(key);
                        meshContexts.add(created);
                        return created;
                    });
                    VertexConsumer shiftedFluidConsumer = new OffsetVertexConsumer(
                            fluidMeshContext.bufferBuilder(),
                            sectionKey.minBlockX(),
                            sectionKey.minBlockY(),
                            sectionKey.minBlockZ()
                    );
                    blockRenderManager.renderFluid(
                            localPos,
                            sceneView,
                            shiftedFluidConsumer,
                            state,
                            fluidState
                    );
                }

                BlockEntity blockEntity = null;
                if (state.getBlock() instanceof BlockEntityProvider provider) {
                    blockEntity = provider.createBlockEntity(localPos, state);
                }
                if (blockEntity == null) {
                    continue;
                }

                blockEntity.setWorld(world);
                if (block.blockEntityNbt() != null) {
                    blockEntity.read(block.blockEntityNbt().copy(), world.getRegistryManager());
                }

                BlockEntityRenderer<BlockEntity> renderer = blockEntityRenderDispatcher.get(blockEntity);
                if (renderer != null) {
                    blockEntities.add(new CachedPortalBlockEntity(
                            blockEntity,
                            renderer,
                            localMin,
                            block.packedLight()
                    ));
                }
            }

            Map<RenderLayer, MeshBuildContext> orderedBuilders = new LinkedHashMap<>(meshBuilders);
            List<CachedLayerMesh> sectionMeshes = new ArrayList<>(orderedBuilders.size());

            for (RenderLayer orderedLayer : RenderLayer.getBlockLayers()) {
                MeshBuildContext context = orderedBuilders.remove(orderedLayer);
                if (context != null) {
                    addMeshIfPresent(sectionMeshes, orderedLayer, context);
                }
            }
            for (Map.Entry<RenderLayer, MeshBuildContext> entry : orderedBuilders.entrySet()) {
                addMeshIfPresent(sectionMeshes, entry.getKey(), entry.getValue());
            }

            CachedSection builtSection = sectionMeshes.isEmpty() ? null : new CachedSection(sectionKey, sectionMeshes);
            return new SectionBuildResult(builtSection, blockEntities);

        } finally {
            for (MeshBuildContext context : meshContexts) {
                context.close();
            }
        }
    }

    private static MeshBuildContext createMeshBuildContext(RenderLayer layer) {
        int allocatorSize = Math.max(layer.getExpectedBufferSize(), 1024);
        BufferAllocator allocator = new BufferAllocator(allocatorSize);
        BufferBuilder bufferBuilder = new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat());
        return new MeshBuildContext(allocator, bufferBuilder);
    }

    private static void addMeshIfPresent(List<CachedLayerMesh> meshes, RenderLayer layer, MeshBuildContext context) {
        BuiltBuffer builtBuffer = context.bufferBuilder().endNullable();
        if (builtBuffer == null) {
            return;
        }

        try (builtBuffer) {
            VertexBuffer vertexBuffer = new VertexBuffer(GlUsage.STATIC_WRITE);
            try {
                vertexBuffer.bind();
                vertexBuffer.upload(builtBuffer);
            } finally {
                VertexBuffer.unbind();
            }
            meshes.add(new CachedLayerMesh(layer, vertexBuffer));
        }
    }

    private static double squaredHorizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static final class InnerPortalFrustum {
        private final CullingPlane[] planes;

        private InnerPortalFrustum(CullingPlane[] planes) {
            this.planes = planes;
        }

        private static InnerPortalFrustum fromPortal(PortalInstance portal, Vec3d camera) {
            Vec3d insideDirection = portal.center().subtract(camera);
            if (insideDirection.lengthSquared() <= 1.0E-8D) {
                return null;
            }

            Vec3d[] corners = portalCorners(portal);
            CullingPlane[] planes = new CullingPlane[4];
            for (int i = 0; i < corners.length; i++) {
                Vec3d a = corners[i].subtract(camera);
                Vec3d b = corners[(i + 1) % corners.length].subtract(camera);
                Vec3d normal = a.crossProduct(b);
                if (normal.lengthSquared() <= 1.0E-10D) {
                    return null;
                }

                normal = normal.normalize();
                if (normal.dotProduct(insideDirection) < 0.0D) {
                    normal = normal.multiply(-1.0D);
                }
                planes[i] = CullingPlane.fromPointNormal(camera, normal);
            }

            return new InnerPortalFrustum(planes);
        }

        private boolean isFullyOutside(
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ
        ) {
            for (CullingPlane plane : planes) {
                if (plane.maxSignedDistance(minX, minY, minZ, maxX, maxY, maxZ) < -INNER_FRUSTUM_CULL_EPSILON) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CullingPlane(double x, double y, double z, double w) {
        private static CullingPlane fromPointNormal(Vec3d point, Vec3d normal) {
            return new CullingPlane(
                    normal.x,
                    normal.y,
                    normal.z,
                    -normal.dotProduct(point)
            );
        }

        private double maxSignedDistance(
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ
        ) {
            double px = x >= 0.0D ? maxX : minX;
            double py = y >= 0.0D ? maxY : minY;
            double pz = z >= 0.0D ? maxZ : minZ;
            return x * px + y * py + z * pz + w;
        }
    }

    private record SectionKey(int x, int y, int z) {
        private static SectionKey fromLocalPos(BlockPos pos) {
            return new SectionKey(
                    Math.floorDiv(pos.getX(), SCENE_SECTION_SIZE),
                    Math.floorDiv(pos.getY(), SCENE_SECTION_SIZE),
                    Math.floorDiv(pos.getZ(), SCENE_SECTION_SIZE)
            );
        }

        private int minBlockX() {
            return x * SCENE_SECTION_SIZE;
        }

        private int minBlockY() {
            return y * SCENE_SECTION_SIZE;
        }

        private int minBlockZ() {
            return z * SCENE_SECTION_SIZE;
        }
    }

    private record SceneCacheKey(
            String sceneName,
            int blockCount,
            int lightSampleCount,
            int firstBlockHash,
            int lastBlockHash
    ) {
        private static SceneCacheKey fromPortal(PortalInstance portal) {
            List<PortalRenderBlock> blocks = portal.renderBlocks();
            int firstHash = blocks.isEmpty() ? 0 : blocks.get(0).hashCode();
            int lastHash = blocks.isEmpty() ? 0 : blocks.get(blocks.size() - 1).hashCode();
            return new SceneCacheKey(
                    portal.sceneName(),
                    blocks.size(),
                    portal.lightSamples().size(),
                    firstHash,
                    lastHash
            );
        }
    }

    private record SectionBuildPlan(
            SectionKey sectionKey,
            List<PortalRenderBlock> blocks
    ) {
    }

    private record PreparedSceneData(
            SceneBlockRenderView sceneView,
            List<SectionBuildPlan> sectionPlans
    ) {
    }

    private record SectionBuildResult(
            @Nullable CachedSection section,
            List<CachedPortalBlockEntity> blockEntities
    ) {
        private SectionBuildResult {
            blockEntities = List.copyOf(blockEntities);
        }
    }

    private static final class CachedScene implements AutoCloseable {
        @Nullable
        private final ClientWorld world;
        @Nullable
        private SceneBlockRenderView sceneView;
        @Nullable
        private CompletableFuture<PreparedSceneData> prepareFuture;
        private final Deque<SectionBuildPlan> pendingSections = new ArrayDeque<>();
        private final List<CachedSection> sections = new ArrayList<>();
        private final List<CachedPortalBlockEntity> blockEntities = new ArrayList<>();
        private long lastBuildFrame = Long.MIN_VALUE;

        private CachedScene(
                @Nullable ClientWorld world,
                @Nullable SceneBlockRenderView sceneView,
                @Nullable CompletableFuture<PreparedSceneData> prepareFuture
        ) {
            this.world = world;
            this.sceneView = sceneView;
            this.prepareFuture = prepareFuture;
        }

        private static CachedScene completed() {
            return new CachedScene(null, null, null);
        }

        private static CachedScene preparing(ClientWorld world, CompletableFuture<PreparedSceneData> prepareFuture) {
            return new CachedScene(world, null, prepareFuture);
        }

        private List<CachedSection> sections() {
            return sections;
        }

        private List<CachedPortalBlockEntity> blockEntities() {
            return blockEntities;
        }

        private void buildNextBatch(MinecraftClient client, long frameIndex, int maxSectionsPerFrame) {
            if (maxSectionsPerFrame <= 0) {
                return;
            }
            if (lastBuildFrame == frameIndex) {
                return;
            }
            lastBuildFrame = frameIndex;
            if (world == null || client.world != world) {
                pendingSections.clear();
                sceneView = null;
                if (prepareFuture != null) {
                    prepareFuture.cancel(true);
                    prepareFuture = null;
                }
                return;
            }
            if (prepareFuture != null) {
                if (!prepareFuture.isDone()) {
                    return;
                }
                try {
                    PreparedSceneData preparedSceneData = prepareFuture.join();
                    sceneView = preparedSceneData.sceneView();
                    pendingSections.addAll(preparedSceneData.sectionPlans());
                } catch (CompletionException ignored) {
                    sceneView = null;
                    pendingSections.clear();
                } finally {
                    prepareFuture = null;
                }
            }
            if (sceneView == null || pendingSections.isEmpty()) {
                return;
            }

            BlockRenderManager blockRenderManager = client.getBlockRenderManager();
            BlockEntityRenderDispatcher blockEntityRenderDispatcher = client.getBlockEntityRenderDispatcher();
            Random random = Random.create();

            int builtSections = 0;
            while (builtSections < maxSectionsPerFrame && !pendingSections.isEmpty()) {
                SectionBuildPlan sectionPlan = pendingSections.removeFirst();
                SectionBuildResult sectionResult = buildSectionMeshes(
                        world,
                        sceneView,
                        blockRenderManager,
                        blockEntityRenderDispatcher,
                        random,
                        sectionPlan
                );

                if (sectionResult.section() != null) {
                    sections.add(sectionResult.section());
                }
                if (!sectionResult.blockEntities().isEmpty()) {
                    blockEntities.addAll(sectionResult.blockEntities());
                }
                builtSections++;
            }
        }

        @Override
        public void close() {
            if (prepareFuture != null) {
                prepareFuture.cancel(true);
                prepareFuture = null;
            }
            for (CachedSection section : sections) {
                section.close();
            }
            sections.clear();
            blockEntities.clear();
            pendingSections.clear();
            sceneView = null;
        }
    }

    private record CachedSection(
            SectionKey sectionKey,
            List<CachedLayerMesh> meshes
    ) implements AutoCloseable {
        private CachedSection {
            meshes = List.copyOf(meshes);
        }

        @Override
        public void close() {
            for (CachedLayerMesh mesh : meshes) {
                mesh.close();
            }
        }
    }

    private record CachedLayerMesh(
            RenderLayer layer,
            VertexBuffer vertexBuffer
    ) implements AutoCloseable {
        @Override
        public void close() {
            if (!vertexBuffer.isClosed()) {
                vertexBuffer.close();
            }
        }
    }

    private record CachedPortalBlockEntity(
            BlockEntity blockEntity,
            BlockEntityRenderer<BlockEntity> renderer,
            Vec3d localMin,
            int light
    ) {
    }

    private static final class MeshBuildContext implements AutoCloseable {
        private final BufferAllocator allocator;
        private final BufferBuilder bufferBuilder;

        private MeshBuildContext(BufferAllocator allocator, BufferBuilder bufferBuilder) {
            this.allocator = allocator;
            this.bufferBuilder = bufferBuilder;
        }

        private BufferBuilder bufferBuilder() {
            return bufferBuilder;
        }

        @Override
        public void close() {
            allocator.close();
        }
    }

    private static final class OffsetVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;

        private OffsetVertexConsumer(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ) {
            this.delegate = delegate;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            delegate.vertex(x + offsetX, y + offsetY, z + offsetZ);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            delegate.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            delegate.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }
    }
}

