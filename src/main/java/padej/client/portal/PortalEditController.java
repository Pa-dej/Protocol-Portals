package padej.client.portal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.UUID;

public final class PortalEditController {
    private static final double MOVE_HANDLE_HALF_SIZE = 0.20D;
    private static final double RESIZE_HANDLE_HALF_SIZE = 0.20D;
    private static final double HANDLE_OFFSET = 0.65D;
    private static final double GRID_STEP_BLOCK = 1.0D;
    private static final double GRID_STEP_FINE = 0.25D;

    private final PortalManager portalManager;

    @Nullable
    private UUID editingPortalId;
    @Nullable
    private String editingSceneName;
    @Nullable
    private DragState activeDrag;
    private boolean primaryDownLastTick = false;

    public PortalEditController(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    public boolean startEditing(String sceneName, Vec3d referencePosition) {
        Optional<PortalInstance> portal = portalManager.findNearestPortalBySceneName(sceneName, referencePosition);
        if (portal.isEmpty()) {
            return false;
        }

        this.editingPortalId = portal.get().id();
        this.editingSceneName = portal.get().sceneName();
        this.activeDrag = null;
        this.primaryDownLastTick = false;
        return true;
    }

    public boolean stopEditing() {
        if (editingPortalId == null && editingSceneName == null) {
            return false;
        }
        editingPortalId = null;
        editingSceneName = null;
        activeDrag = null;
        primaryDownLastTick = false;
        return true;
    }

    public boolean isEditingScene(String sceneName) {
        PortalInstance portal = editingPortal();
        return portal != null && portal.sceneName().equals(sceneName);
    }

    public boolean isEditing() {
        return editingPortalId != null || editingSceneName != null;
    }

    @Nullable
    public GizmoHandles gizmoHandles() {
        PortalInstance portal = editingPortal();
        if (portal == null) {
            return null;
        }

        Vec3d right = portal.right().normalize();
        Vec3d up = portal.up().normalize();
        Vec3d center = portal.center();
        Vec3d widthHandlePos = center.add(right.multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d widthHandleNeg = center.subtract(right.multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandlePos = center.add(up.multiply(portal.height() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandleNeg = center.subtract(up.multiply(portal.height() * 0.5D + HANDLE_OFFSET));

        return new GizmoHandles(
                portal,
                center,
                widthHandlePos,
                widthHandleNeg,
                heightHandlePos,
                heightHandleNeg,
                MOVE_HANDLE_HALF_SIZE,
                RESIZE_HANDLE_HALF_SIZE
        );
    }

    @Nullable
    public PortalInstance editingPortal() {
        if (editingPortalId != null) {
            Optional<PortalInstance> byId = portalManager.findPortalById(editingPortalId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }

        if (editingSceneName == null) {
            return null;
        }

        Optional<PortalInstance> bySceneName = portalManager.findAnyPortalBySceneName(editingSceneName);
        if (bySceneName.isPresent()) {
            editingPortalId = bySceneName.get().id();
            return bySceneName.get();
        }

        return null;
    }

    public void tick(MinecraftClient client) {
        if (editingPortalId == null && editingSceneName == null) {
            primaryDownLastTick = false;
            activeDrag = null;
            return;
        }

        if (client.world == null || client.player == null || client.currentScreen != null) {
            activeDrag = null;
            primaryDownLastTick = false;
            return;
        }

        PortalInstance currentPortal = editingPortal();
        if (currentPortal == null) {
            stopEditing();
            return;
        }

        Ray cameraRay = getCameraRay(client);
        if (cameraRay == null) {
            activeDrag = null;
            primaryDownLastTick = false;
            return;
        }

        long windowHandle = client.getWindow().getHandle();
        boolean primaryDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean fineGrid = client.player.isSneaking();
        double gridStep = fineGrid ? GRID_STEP_FINE : GRID_STEP_BLOCK;

        if (!primaryDown) {
            activeDrag = null;
            sendActionbar(client, currentPortal, gridStep);
            primaryDownLastTick = primaryDown;
            return;
        }

        PortalInstance portal = currentPortal;
        if (!primaryDownLastTick) {
            beginDrag(portal, cameraRay);
        } else {
            updateDrag(portal, cameraRay, gridStep);
            PortalInstance updatedPortal = editingPortal();
            if (updatedPortal != null) {
                portal = updatedPortal;
            }
        }

        sendActionbar(client, portal, gridStep);
        primaryDownLastTick = primaryDown;
    }

    private void beginDrag(PortalInstance portal, Ray ray) {
        DragMode mode = pickDragMode(portal, ray);
        if (mode == null) {
            activeDrag = null;
            return;
        }

        Vec3d planeIntersection = intersectRayPlane(ray, portal.center(), portal.normal());
        if (planeIntersection == null) {
            activeDrag = null;
            return;
        }

        activeDrag = new DragState(
                mode,
                planeIntersection,
                portal.center(),
                portal.sceneAnchor(),
                portal.width(),
                portal.height()
        );
    }

    private void updateDrag(PortalInstance portal, Ray ray, double gridStep) {
        DragState drag = activeDrag;
        if (drag == null) {
            return;
        }

        Vec3d planeIntersection = intersectRayPlane(ray, drag.startCenter(), portal.normal());
        if (planeIntersection == null) {
            return;
        }

        Vec3d right = portal.right().normalize();
        Vec3d up = portal.up().normalize();
        switch (drag.mode()) {
            case MOVE -> {
                Vec3d delta = planeIntersection.subtract(drag.startPlaneIntersection());
                double alongRight = snapToStep(delta.dotProduct(right), gridStep);
                double alongUp = snapToStep(delta.dotProduct(up), gridStep);
                Vec3d snappedCenter = drag.startCenter()
                        .add(right.multiply(alongRight))
                        .add(up.multiply(alongUp));
                Vec3d centerDelta = snappedCenter.subtract(drag.startCenter());
                Vec3d movedSceneAnchor = drag.startSceneAnchor().add(centerDelta);

                portalManager.updatePortalGeometry(
                        portal.id(),
                        snappedCenter,
                        movedSceneAnchor,
                        drag.startWidth(),
                        drag.startHeight()
                );
            }
            case WIDTH -> {
                Vec3d relative = planeIntersection.subtract(drag.startCenter());
                double nextWidth = Math.max(1.0D, snapToStep(Math.abs(relative.dotProduct(right)) * 2.0D, gridStep));
                portalManager.updatePortalGeometry(
                        portal.id(),
                        drag.startCenter(),
                        drag.startSceneAnchor(),
                        nextWidth,
                        drag.startHeight()
                );
            }
            case HEIGHT -> {
                Vec3d relative = planeIntersection.subtract(drag.startCenter());
                double nextHeight = Math.max(1.0D, snapToStep(Math.abs(relative.dotProduct(up)) * 2.0D, gridStep));
                portalManager.updatePortalGeometry(
                        portal.id(),
                        drag.startCenter(),
                        drag.startSceneAnchor(),
                        drag.startWidth(),
                        nextHeight
                );
            }
        }
    }

    @Nullable
    private static Ray getCameraRay(MinecraftClient client) {
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null) {
            return null;
        }

        Vec3d origin = camera.getPos();
        Vec3d direction = cameraForwardVector(camera.getYaw(), camera.getPitch());
        if (direction.lengthSquared() < 1.0E-8D) {
            return null;
        }

        return new Ray(origin, direction.normalize());
    }

    @Nullable
    private static DragMode pickDragMode(PortalInstance portal, Ray ray) {
        Vec3d right = portal.right().normalize();
        Vec3d up = portal.up().normalize();
        Vec3d center = portal.center();
        Vec3d widthHandlePos = center.add(right.multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d widthHandleNeg = center.subtract(right.multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandlePos = center.add(up.multiply(portal.height() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandleNeg = center.subtract(up.multiply(portal.height() * 0.5D + HANDLE_OFFSET));

        DragChoice best = null;
        best = chooseBest(best, DragMode.WIDTH, rayAabbDistance(ray, widthHandlePos, RESIZE_HANDLE_HALF_SIZE));
        best = chooseBest(best, DragMode.WIDTH, rayAabbDistance(ray, widthHandleNeg, RESIZE_HANDLE_HALF_SIZE));
        best = chooseBest(best, DragMode.HEIGHT, rayAabbDistance(ray, heightHandlePos, RESIZE_HANDLE_HALF_SIZE));
        best = chooseBest(best, DragMode.HEIGHT, rayAabbDistance(ray, heightHandleNeg, RESIZE_HANDLE_HALF_SIZE));
        best = chooseBest(best, DragMode.MOVE, rayAabbDistance(ray, center, MOVE_HANDLE_HALF_SIZE));
        return best == null ? null : best.mode();
    }

    @Nullable
    private static DragChoice chooseBest(@Nullable DragChoice current, DragMode mode, double distance) {
        if (!Double.isFinite(distance) || distance <= 0.0D) {
            return current;
        }
        if (current == null || distance < current.distance()) {
            return new DragChoice(mode, distance);
        }
        return current;
    }

    private static double rayAabbDistance(Ray ray, Vec3d center, double halfSize) {
        double minX = center.x - halfSize;
        double minY = center.y - halfSize;
        double minZ = center.z - halfSize;
        double maxX = center.x + halfSize;
        double maxY = center.y + halfSize;
        double maxZ = center.z + halfSize;

        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        double[] origin = {ray.origin().x, ray.origin().y, ray.origin().z};
        double[] direction = {ray.direction().x, ray.direction().y, ray.direction().z};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};

        for (int axis = 0; axis < 3; axis++) {
            double d = direction[axis];
            double o = origin[axis];
            if (Math.abs(d) < 1.0E-8D) {
                if (o < min[axis] || o > max[axis]) {
                    return Double.NaN;
                }
                continue;
            }

            double inv = 1.0D / d;
            double t1 = (min[axis] - o) * inv;
            double t2 = (max[axis] - o) * inv;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);

            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) {
                return Double.NaN;
            }
        }

        if (tMax <= 0.0D) {
            return Double.NaN;
        }
        return tMin > 0.0D ? tMin : tMax;
    }

    @Nullable
    private static Vec3d intersectRayPlane(Ray ray, Vec3d pointOnPlane, Vec3d normal) {
        double denominator = ray.direction().dotProduct(normal);
        if (Math.abs(denominator) < 1.0E-6D) {
            return null;
        }

        double t = pointOnPlane.subtract(ray.origin()).dotProduct(normal) / denominator;
        if (t <= 0.0D) {
            return null;
        }
        return ray.origin().add(ray.direction().multiply(t));
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

    private enum DragMode {
        MOVE,
        WIDTH,
        HEIGHT
    }

    private static double snapToStep(double value, double step) {
        if (step <= 1.0E-6D) {
            return value;
        }
        return Math.round(value / step) * step;
    }

    private static String formatCoordinate(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void sendActionbar(MinecraftClient client, PortalInstance portal, double gridStep) {
        if (client.player == null || client.world == null) {
            return;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        String text = String.format(
                java.util.Locale.ROOT,
                "PP Edit | dim: %s | pos: (%s, %s, %s) | size: %.2f x %.2f | grid: %.2f",
                dimension,
                formatCoordinate(portal.center().x),
                formatCoordinate(portal.center().y),
                formatCoordinate(portal.center().z),
                portal.width(),
                portal.height(),
                gridStep
        );
        client.player.sendMessage(Text.literal(text), true);
    }

    private record Ray(Vec3d origin, Vec3d direction) {
    }

    private record DragChoice(DragMode mode, double distance) {
    }

    private record DragState(
            DragMode mode,
            Vec3d startPlaneIntersection,
            Vec3d startCenter,
            Vec3d startSceneAnchor,
            double startWidth,
            double startHeight
    ) {
    }

    public record GizmoHandles(
            PortalInstance portal,
            Vec3d moveHandle,
            Vec3d widthHandlePositive,
            Vec3d widthHandleNegative,
            Vec3d heightHandlePositive,
            Vec3d heightHandleNegative,
            double moveHalfSize,
            double resizeHalfSize
    ) {
    }
}
