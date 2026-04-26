package padej.client.portal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.UUID;

public final class PortalEditController {
    private static final double HANDLE_RADIUS = 0.32D;
    private static final double HANDLE_OFFSET = 0.65D;

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
        boolean sneaking = client.player.isSneaking();

        if (!primaryDown || !sneaking) {
            activeDrag = null;
            primaryDownLastTick = primaryDown;
            return;
        }

        PortalInstance portal = currentPortal;
        if (!primaryDownLastTick) {
            beginDrag(portal, cameraRay);
        } else {
            updateDrag(portal, cameraRay);
        }

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

    private void updateDrag(PortalInstance portal, Ray ray) {
        DragState drag = activeDrag;
        if (drag == null) {
            return;
        }

        Vec3d planeIntersection = intersectRayPlane(ray, drag.startCenter(), portal.normal());
        if (planeIntersection == null) {
            return;
        }

        switch (drag.mode()) {
            case MOVE -> {
                Vec3d delta = planeIntersection.subtract(drag.startPlaneIntersection());
                double alongRight = delta.dotProduct(portal.right());
                double alongUp = delta.dotProduct(portal.up());
                Vec3d planarDelta = portal.right().multiply(alongRight).add(portal.up().multiply(alongUp));

                Vec3d desiredCenter = drag.startCenter().add(planarDelta);
                Vec3d snappedCenter = PortalManager.snapPortalCenterToBlockGrid(desiredCenter, portal.normal());
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
                double nextWidth = Math.max(1.0D, Math.round(Math.abs(relative.dotProduct(portal.right())) * 2.0D));
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
                double nextHeight = Math.max(1.0D, Math.round(Math.abs(relative.dotProduct(portal.up())) * 2.0D));
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
        Vec3d center = portal.center();
        Vec3d widthHandlePos = center.add(portal.right().multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d widthHandleNeg = center.subtract(portal.right().multiply(portal.width() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandlePos = center.add(portal.up().multiply(portal.height() * 0.5D + HANDLE_OFFSET));
        Vec3d heightHandleNeg = center.subtract(portal.up().multiply(portal.height() * 0.5D + HANDLE_OFFSET));

        DragChoice best = null;
        best = chooseBest(best, DragMode.WIDTH, raySphereDistance(ray, widthHandlePos, HANDLE_RADIUS));
        best = chooseBest(best, DragMode.WIDTH, raySphereDistance(ray, widthHandleNeg, HANDLE_RADIUS));
        best = chooseBest(best, DragMode.HEIGHT, raySphereDistance(ray, heightHandlePos, HANDLE_RADIUS));
        best = chooseBest(best, DragMode.HEIGHT, raySphereDistance(ray, heightHandleNeg, HANDLE_RADIUS));
        best = chooseBest(best, DragMode.MOVE, raySphereDistance(ray, center, HANDLE_RADIUS * 1.2D));
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

    private static double raySphereDistance(Ray ray, Vec3d center, double radius) {
        Vec3d offset = ray.origin().subtract(center);
        double b = offset.dotProduct(ray.direction());
        double c = offset.lengthSquared() - radius * radius;
        double h = b * b - c;
        if (h < 0.0D) {
            return Double.NaN;
        }

        double sqrt = Math.sqrt(h);
        double near = -b - sqrt;
        if (near > 0.0D) {
            return near;
        }

        double far = -b + sqrt;
        return far > 0.0D ? far : Double.NaN;
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
}
