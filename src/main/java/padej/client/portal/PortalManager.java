package padej.client.portal;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
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
        // Snapshot origin is anchored to the lower portal edge with a tiny forward bias
        // so scene cubes do not z-fight with the portal plane.
        Vec3d sceneAnchor = center
                .subtract(up.multiply(PORTAL_HEIGHT * 0.5D))
                .add(normal.multiply(0.01D));

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
        int quarterTurns = Math.floorMod(Math.round(snapshot.captureYaw() / 90.0F), 4);

        List<PortalRenderBlock> out = new ArrayList<>(Math.min(MAX_RENDER_BLOCKS_PER_PORTAL, sorted.size()));
        for (int i = 0; i < sorted.size() && i < MAX_RENDER_BLOCKS_PER_PORTAL; i++) {
            SceneSnapshot.SceneBlock block = sorted.get(i);
            int rgb = colorFor(block.state());
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;

            int x = block.relX();
            int z = block.relZ();
            int rotatedX;
            int rotatedZ;
            switch (quarterTurns) {
                case 1 -> {
                    rotatedX = -z;
                    rotatedZ = x;
                }
                case 2 -> {
                    rotatedX = -x;
                    rotatedZ = -z;
                }
                case 3 -> {
                    rotatedX = z;
                    rotatedZ = -x;
                }
                default -> {
                    rotatedX = x;
                    rotatedZ = z;
                }
            }

            out.add(new PortalRenderBlock(new Vec3d(rotatedX, block.relY(), rotatedZ), red, green, blue));
        }
        return out;
    }

    private static int distanceSq(SceneSnapshot.SceneBlock block) {
        return block.relX() * block.relX() + block.relY() * block.relY() + block.relZ() * block.relZ();
    }

    private static int colorFor(BlockState state) {
        MapColor mapColor = state.getBlock().getDefaultMapColor();
        if (mapColor != null && mapColor != MapColor.CLEAR) {
            return mapColor.color;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        int hash = blockId.hashCode();
        int red = 96 + Math.floorMod(hash, 96);
        int green = 96 + Math.floorMod(hash / 17, 96);
        int blue = 96 + Math.floorMod(hash / 31, 96);
        return (red << 16) | (green << 8) | blue;
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
