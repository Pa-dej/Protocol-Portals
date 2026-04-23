package padej.client.render;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import padej.client.portal.PortalInstance;

import java.util.ArrayList;
import java.util.List;

public final class PortalOuterFrustumCulling {
    private static final double CULL_EPSILON = 1.0E-4D;
    private static volatile List<PortalCullVolume> activeCullVolumes = List.of();

    private PortalOuterFrustumCulling() {
    }

    public static void update(List<PortalInstance> portals, Vec3d camera) {
        if (portals.isEmpty()) {
            activeCullVolumes = List.of();
            return;
        }

        List<PortalCullVolume> volumes = new ArrayList<>(portals.size());
        for (PortalInstance portal : portals) {
            PortalCullVolume volume = PortalCullVolume.fromPortal(portal, camera);
            if (volume != null) {
                volumes.add(volume);
            }
        }
        activeCullVolumes = List.copyOf(volumes);
    }

    public static void clear() {
        activeCullVolumes = List.of();
    }

    public static boolean shouldCull(Box box) {
        List<PortalCullVolume> volumes = activeCullVolumes;
        for (PortalCullVolume volume : volumes) {
            if (volume.shouldCull(box)) {
                return true;
            }
        }
        return false;
    }

    private static final class PortalCullVolume {
        private final Plane portalPlane;
        private final Plane[] conePlanes;

        private PortalCullVolume(Plane portalPlane, Plane[] conePlanes) {
            this.portalPlane = portalPlane;
            this.conePlanes = conePlanes;
        }

        private static PortalCullVolume fromPortal(PortalInstance portal, Vec3d camera) {
            Vec3d toCamera = camera.subtract(portal.center());
            double viewerSide = toCamera.dotProduct(portal.normal());
            if (Math.abs(viewerSide) <= 1.0E-7D) {
                return null;
            }

            Vec3d portalPlaneNormal = viewerSide >= 0.0D ? portal.normal() : portal.normal().multiply(-1.0D);
            Plane portalPlane = Plane.fromPointNormal(portal.center(), portalPlaneNormal);

            Vec3d[] corners = getPortalCorners(portal);
            Vec3d insideDirection = portal.center().subtract(camera);
            if (insideDirection.lengthSquared() <= 1.0E-10D) {
                return null;
            }

            Plane[] conePlanes = new Plane[4];
            for (int i = 0; i < corners.length; i++) {
                Vec3d a = corners[i].subtract(camera);
                Vec3d b = corners[(i + 1) % corners.length].subtract(camera);
                Vec3d normal = a.crossProduct(b);
                if (normal.lengthSquared() <= 1.0E-12D) {
                    return null;
                }

                normal = normal.normalize();
                if (normal.dotProduct(insideDirection) < 0.0D) {
                    normal = normal.multiply(-1.0D);
                }
                conePlanes[i] = Plane.fromPointNormal(camera, normal);
            }

            return new PortalCullVolume(portalPlane, conePlanes);
        }

        private boolean shouldCull(Box box) {
            if (!isFullyBehindPortalPlane(box)) {
                return false;
            }
            return isFullyInsideCone(box);
        }

        private boolean isFullyBehindPortalPlane(Box box) {
            return portalPlane.maxSignedDistance(box) < -CULL_EPSILON;
        }

        private boolean isFullyInsideCone(Box box) {
            for (Plane conePlane : conePlanes) {
                if (conePlane.minSignedDistance(box) <= CULL_EPSILON) {
                    return false;
                }
            }
            return true;
        }

        private static Vec3d[] getPortalCorners(PortalInstance portal) {
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
    }

    private record Plane(double x, double y, double z, double w) {
        private static Plane fromPointNormal(Vec3d point, Vec3d normal) {
            return new Plane(
                    normal.x,
                    normal.y,
                    normal.z,
                    -normal.dotProduct(point)
            );
        }

        private double minSignedDistance(Box box) {
            double px = x >= 0.0D ? box.minX : box.maxX;
            double py = y >= 0.0D ? box.minY : box.maxY;
            double pz = z >= 0.0D ? box.minZ : box.maxZ;
            return x * px + y * py + z * pz + w;
        }

        private double maxSignedDistance(Box box) {
            double px = x >= 0.0D ? box.maxX : box.minX;
            double py = y >= 0.0D ? box.maxY : box.minY;
            double pz = z >= 0.0D ? box.maxZ : box.minZ;
            return x * px + y * py + z * pz + w;
        }
    }
}
