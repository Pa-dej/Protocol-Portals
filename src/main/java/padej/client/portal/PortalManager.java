package padej.client.portal;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import padej.client.scene.SceneSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PortalManager {
    private static final int MAX_RENDER_BLOCKS_PER_PORTAL = 12000;
    private static final double PORTAL_WIDTH = 3.0D;
    private static final double PORTAL_HEIGHT = 3.0D;

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

        Vec3d center = player.getEyePos().add(normal.multiply(3.0D));
        double eyeOffsetY = player.getEyeY() - player.getY();
        // Scene anchor = portal center. Blocks are placed at (center + relX, center + relY, center + relZ)
        // using world-space axes (east/up/south), so the scene is world-aligned regardless of portal facing.
        // Snapshot relY is captured from block-position Y (feet-level), so we shift anchor down by eye offset
        // to keep the viewed scene height aligned with what the player saw at capture time.
        // Tiny bias along portal normal avoids z-fighting with the portal plane itself.
        Vec3d sceneAnchor = center
                .add(normal.multiply(0.01D))
                .add(0.0D, -eyeOffsetY, 0.0D);

        List<PortalRenderBlock> renderBlocks = prepareRenderBlocks(snapshot);
        PortalInstance portal = new PortalInstance(
                UUID.randomUUID(),
                sceneName,
                center,
                normal,
                right,
                up,
                sceneAnchor,
                PORTAL_WIDTH,
                PORTAL_HEIGHT,
                renderBlocks
        );
        activePortals.add(portal);
        return portal;
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

    private static int distanceSq(SceneSnapshot.SceneBlock block) {
        // Prioritize by horizontal distance only (XZ). Y should not affect portal block selection order.
        return block.relX() * block.relX() + block.relZ() * block.relZ();
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

