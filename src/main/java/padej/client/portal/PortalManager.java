package padej.client.portal;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import padej.client.scene.SceneSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PortalManager {
    private static final double PORTAL_WIDTH = 3.0D;
    private static final double PORTAL_HEIGHT = 3.0D;
    private static final double PORTAL_FORWARD_DISTANCE = 3.0D;
    private static final int PORTAL_PREPARE_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();
    private final List<PortalInstance> activePortals = new ArrayList<>();
    private final List<DebugPlaneInstance> debugPlanes = new ArrayList<>();
    private final ExecutorService portalPrepareExecutor = Executors.newFixedThreadPool(PORTAL_PREPARE_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "protocol-portals-portal-prepare");
        thread.setDaemon(true);
        return thread;
    });

    public List<PortalInstance> activePortals() {
        return List.copyOf(activePortals);
    }

    public List<DebugPlaneInstance> debugPlanes() {
        return List.copyOf(debugPlanes);
    }

    public PortalInstance createPortal(ClientPlayerEntity player, String sceneName, SceneSnapshot snapshot) {
        return createPortal(player, sceneName, snapshot, null);
    }

    public PortalInstance createPortal(
            ClientPlayerEntity player,
            String sceneName,
            SceneSnapshot snapshot,
            @Nullable String serverAddress
    ) {
        String normalizedServerAddress = normalizeServerAddress(serverAddress);
        PortalPlacement placement = computePortalPlacement(player, sceneName, snapshot.skyColor(), normalizedServerAddress);
        List<PortalRenderBlock> renderBlocks = prepareRenderBlocks(snapshot);
        List<PortalLightSample> lightSamples = prepareLightSamples(snapshot);
        PortalInstance portal = new PortalInstance(
                UUID.randomUUID(),
                placement.sceneName(),
                placement.serverAddress(),
                placement.center(),
                placement.normal(),
                placement.right(),
                placement.up(),
                placement.sceneAnchor(),
                placement.skyColor(),
                PORTAL_WIDTH,
                PORTAL_HEIGHT,
                renderBlocks,
                lightSamples
        );
        activePortals.add(portal);
        return portal;
    }

    public CompletableFuture<PortalInstance> createPortalAsync(
            MinecraftClient client,
            ClientPlayerEntity player,
            String sceneName,
            SceneSnapshot snapshot
    ) {
        return createPortalAsync(client, player, sceneName, snapshot, null);
    }

    public CompletableFuture<PortalInstance> createPortalAsync(
            MinecraftClient client,
            ClientPlayerEntity player,
            String sceneName,
            SceneSnapshot snapshot,
            @Nullable String serverAddress
    ) {
        String normalizedServerAddress = normalizeServerAddress(serverAddress);
        PortalPlacement placement = computePortalPlacement(player, sceneName, snapshot.skyColor(), normalizedServerAddress);

        CompletableFuture<List<PortalRenderBlock>> renderBlocksFuture =
                CompletableFuture.supplyAsync(() -> prepareRenderBlocks(snapshot), portalPrepareExecutor);
        CompletableFuture<List<PortalLightSample>> lightSamplesFuture =
                CompletableFuture.supplyAsync(() -> prepareLightSamples(snapshot), portalPrepareExecutor);

        return renderBlocksFuture.thenCombine(lightSamplesFuture, (renderBlocks, lightSamples) ->
                new PreparedPortalData(
                        placement.sceneName(),
                        placement.serverAddress(),
                        placement.center(),
                        placement.normal(),
                        placement.right(),
                        placement.up(),
                        placement.sceneAnchor(),
                        placement.skyColor(),
                        renderBlocks,
                        lightSamples
                )).thenCompose(prepared -> enqueuePortalCreation(client, prepared));
    }

    private static PortalPlacement computePortalPlacement(
            ClientPlayerEntity player,
            String sceneName,
            Vec3d skyColor,
            @Nullable String serverAddress
    ) {
        PlaneBasis basis = buildVerticalPlaneBasis(player.getYaw());
        Vec3d normal = basis.normal();
        Vec3d right = basis.right();
        Vec3d up = basis.up();

        Vec3d desiredCenter = player.getEyePos().add(normal.multiply(PORTAL_FORWARD_DISTANCE));
        Vec3d center = alignPortalCenterToBlockGrid(desiredCenter, normal);
        double eyeOffsetY = player.getEyeY() - player.getY();
        // Capture center is conceptually at block center (x/z + 0.5). Stored scene blocks are integer minima,
        // so we shift scene anchor by -0.5 on x/z to match that center convention at render time.
        double anchorX = Math.floor(center.x);
        double anchorZ = Math.floor(center.z);

        // Snapshot relY is captured from block-position Y (feet-level), so shift down by eye offset.
        // Tiny bias along portal normal avoids z-fighting with the portal plane itself.
        Vec3d sceneAnchor = new Vec3d(anchorX, center.y - eyeOffsetY, anchorZ)
                .add(normal.multiply(0.01D));

        return new PortalPlacement(sceneName, serverAddress, center, normal, right, up, sceneAnchor, skyColor);
    }

    private CompletableFuture<PortalInstance> enqueuePortalCreation(MinecraftClient client, PreparedPortalData prepared) {
        CompletableFuture<PortalInstance> result = new CompletableFuture<>();
        client.execute(() -> {
            if (client.player == null || client.world == null) {
                result.completeExceptionally(new IllegalStateException("Client world is unavailable."));
                return;
            }
            PortalInstance portal = new PortalInstance(
                UUID.randomUUID(),
                prepared.sceneName(),
                prepared.serverAddress(),
                prepared.center(),
                prepared.normal(),
                prepared.right(),
                prepared.up(),
                prepared.sceneAnchor(),
                prepared.skyColor(),
                PORTAL_WIDTH,
                PORTAL_HEIGHT,
                prepared.renderBlocks(),
                prepared.lightSamples()
            );
            activePortals.add(portal);
            result.complete(portal);
        });
        return result;
    }

    private record PreparedPortalData(
            String sceneName,
            @Nullable String serverAddress,
            Vec3d center,
            Vec3d normal,
            Vec3d right,
            Vec3d up,
            Vec3d sceneAnchor,
            Vec3d skyColor,
            List<PortalRenderBlock> renderBlocks,
            List<PortalLightSample> lightSamples
    ) {
    }

    private record PortalPlacement(
            String sceneName,
            @Nullable String serverAddress,
            Vec3d center,
            Vec3d normal,
            Vec3d right,
            Vec3d up,
            Vec3d sceneAnchor,
            Vec3d skyColor
    ) {
    }

    public Optional<PortalInstance> removeNearestPortal(Vec3d position) {
        int index = findNearestPortalIndex(position, null);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(activePortals.remove(index));
    }

    public Optional<PortalInstance> removePortalBySceneName(String sceneName, Vec3d position) {
        int index = findNearestPortalIndex(position, sceneName);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(activePortals.remove(index));
    }

    public DebugPlaneInstance createDebugPlane(ClientPlayerEntity player) {
        PlaneBasis basis = buildVerticalPlaneBasis(player.getYaw());
        Vec3d center = player.getEyePos().add(basis.normal().multiply(2.0D));

        DebugPlaneInstance plane = new DebugPlaneInstance(
                UUID.randomUUID(),
                center,
                basis.normal(),
                basis.right(),
                basis.up(),
                PORTAL_WIDTH,
                PORTAL_HEIGHT
        );
        debugPlanes.add(plane);
        return plane;
    }

    public Optional<DebugPlaneInstance> removeNearestDebugPlane(Vec3d position) {
        int index = findNearestDebugPlaneIndex(position);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(debugPlanes.remove(index));
    }

    private List<PortalRenderBlock> prepareRenderBlocks(SceneSnapshot snapshot) {
        // World-aligned mode: no rotation applied - blocks keep their original world-relative
        // positions (relX = east offset, relY = up offset, relZ = south offset).
        // BlockState is also not rotated since the scene is shown in its original orientation.
        Long2ObjectOpenHashMap<SceneSnapshot.SceneBlock> blocksByPos =
                new Long2ObjectOpenHashMap<>(Math.max(16, snapshot.blocks().size()));
        for (SceneSnapshot.SceneBlock block : snapshot.blocks()) {
            long key = BlockPos.asLong(block.relX(), block.relY(), block.relZ());
            blocksByPos.put(key, block);
        }

        List<PortalRenderBlock> out = new ArrayList<>(snapshot.blocks().size());
        for (SceneSnapshot.SceneBlock block : snapshot.blocks()) {
            if (!shouldKeepSceneBlock(blocksByPos, block)) {
                continue;
            }
            out.add(new PortalRenderBlock(
                    new Vec3d(block.relX(), block.relY(), block.relZ()),
                    block.state(),
                    block.packedLight(),
                    block.blockEntityNbt()
            ));
        }
        return out;
    }

    private static boolean shouldKeepSceneBlock(
            Long2ObjectOpenHashMap<SceneSnapshot.SceneBlock> blocksByPos,
            SceneSnapshot.SceneBlock block
    ) {
        if (block.blockEntityNbt() != null) {
            return true;
        }
        if (!isDenseSolidCube(block.state())) {
            return true;
        }

        int x = block.relX();
        int y = block.relY();
        int z = block.relZ();
        for (Direction direction : NEIGHBOR_DIRECTIONS) {
            SceneSnapshot.SceneBlock neighbor = blocksByPos.get(BlockPos.asLong(
                    x + direction.getOffsetX(),
                    y + direction.getOffsetY(),
                    z + direction.getOffsetZ()
            ));
            if (neighbor == null || !isDenseSolidCube(neighbor.state())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDenseSolidCube(BlockState state) {
        return state.isOpaqueFullCube();
    }

    private List<PortalLightSample> prepareLightSamples(SceneSnapshot snapshot) {
        List<PortalLightSample> out = new ArrayList<>(snapshot.lightSamples().size());
        for (SceneSnapshot.LightSample sample : snapshot.lightSamples()) {
            out.add(new PortalLightSample(
                    sample.relX(),
                    sample.relY(),
                    sample.relZ(),
                    sample.packedLight()
            ));
        }
        return out;
    }

    private static Vec3d alignPortalCenterToBlockGrid(Vec3d desiredCenter, Vec3d normal) {
        // In-plane coordinates snap to block centers for clean grid alignment.
        double x = Math.floor(desiredCenter.x) + 0.5D;
        double y = Math.floor(desiredCenter.y) + 0.5D;
        double z = Math.floor(desiredCenter.z) + 0.5D;

        // Depth axis snaps to block-center offset in the facing direction (+/-0.5), like a nether portal plane.
        if (Math.abs(normal.x) > 0.5D) {
            x = Math.floor(desiredCenter.x) + 0.5D * Math.signum(normal.x);
        } else if (Math.abs(normal.z) > 0.5D) {
            z = Math.floor(desiredCenter.z) + 0.5D * Math.signum(normal.z);
        }

        return new Vec3d(x, y, z);
    }

    private int findNearestPortalIndex(Vec3d position, String requiredSceneName) {
        int bestIndex = -1;
        double bestDistanceSq = Double.POSITIVE_INFINITY;

        for (int i = 0; i < activePortals.size(); i++) {
            PortalInstance portal = activePortals.get(i);
            if (requiredSceneName != null && !portal.sceneName().equals(requiredSceneName)) {
                continue;
            }

            double distanceSq = squaredHorizontalDistance(position, portal.center());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private int findNearestDebugPlaneIndex(Vec3d position) {
        int bestIndex = -1;
        double bestDistanceSq = Double.POSITIVE_INFINITY;

        for (int i = 0; i < debugPlanes.size(); i++) {
            DebugPlaneInstance plane = debugPlanes.get(i);
            double distanceSq = squaredHorizontalDistance(position, plane.center());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private static double squaredHorizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static PlaneBasis buildVerticalPlaneBasis(float yawDegrees) {
        Vec3d normal = snapYawToCardinal(yawDegrees);
        Vec3d worldUp = new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d right = worldUp.crossProduct(normal).normalize();
        if (right.lengthSquared() < 1.0E-6D) {
            right = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        Vec3d up = normal.crossProduct(right).normalize();
        return new PlaneBasis(normal, right, up);
    }

    private static Vec3d snapYawToCardinal(float yawDegrees) {
        int quarterTurns = Math.floorMod(Math.round(yawDegrees / 90.0F), 4);
        return switch (quarterTurns) {
            case 0 -> new Vec3d(0.0D, 0.0D, 1.0D);   // south
            case 1 -> new Vec3d(-1.0D, 0.0D, 0.0D);  // west
            case 2 -> new Vec3d(0.0D, 0.0D, -1.0D);  // north
            default -> new Vec3d(1.0D, 0.0D, 0.0D);  // east
        };
    }

    private record PlaneBasis(Vec3d normal, Vec3d right, Vec3d up) {
    }

    @Nullable
    private static String normalizeServerAddress(@Nullable String serverAddress) {
        if (serverAddress == null) {
            return null;
        }
        String trimmed = serverAddress.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

