package padej.client.portal;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import padej.client.scene.SceneSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PortalManager {
    private static final int MAX_RENDER_BLOCKS_PER_PORTAL = 12000;
    private static final double PORTAL_WIDTH = 3.0D;
    private static final double PORTAL_HEIGHT = 3.0D;
    private static final double PORTAL_FORWARD_DISTANCE = 3.0D;

    private final List<PortalInstance> activePortals = new ArrayList<>();
    private final List<DebugPlaneInstance> debugPlanes = new ArrayList<>();

    public List<PortalInstance> activePortals() {
        return List.copyOf(activePortals);
    }

    public List<DebugPlaneInstance> debugPlanes() {
        return List.copyOf(debugPlanes);
    }

    public PortalInstance createPortal(ClientPlayerEntity player, String sceneName, SceneSnapshot snapshot) {
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

        List<PortalRenderBlock> renderBlocks = prepareRenderBlocks(snapshot);
        List<PortalLightSample> lightSamples = prepareLightSamples(snapshot);
        PortalInstance portal = new PortalInstance(
                UUID.randomUUID(),
                sceneName,
                center,
                normal,
                right,
                up,
                sceneAnchor,
                snapshot.skyColor(),
                PORTAL_WIDTH,
                PORTAL_HEIGHT,
                renderBlocks,
                lightSamples
        );
        activePortals.add(portal);
        return portal;
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

    private List<PortalRenderBlock> prepareRenderBlocks(SceneSnapshot snapshot) {
        List<SceneSnapshot.SceneBlock> sorted = new ArrayList<>(snapshot.blocks());
        sorted.sort(Comparator.comparingInt(PortalManager::distanceSq));

        // World-aligned mode: no rotation applied - blocks keep their original world-relative
        // positions (relX = east offset, relY = up offset, relZ = south offset).
        // BlockState is also not rotated since the scene is shown in its original orientation.
        List<PortalRenderBlock> out = new ArrayList<>(Math.min(MAX_RENDER_BLOCKS_PER_PORTAL, sorted.size()));
        for (int i = 0; i < sorted.size() && i < MAX_RENDER_BLOCKS_PER_PORTAL; i++) {
            SceneSnapshot.SceneBlock block = sorted.get(i);
            out.add(new PortalRenderBlock(
                    new Vec3d(block.relX(), block.relY(), block.relZ()),
                    block.state(),
                    block.packedLight(),
                    block.blockEntityNbt()
            ));
        }
        return out;
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

    private static int distanceSq(SceneSnapshot.SceneBlock block) {
        // Prioritize by horizontal distance only (XZ). Y should not affect portal block selection order.
        return block.relX() * block.relX() + block.relZ() * block.relZ();
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
}

