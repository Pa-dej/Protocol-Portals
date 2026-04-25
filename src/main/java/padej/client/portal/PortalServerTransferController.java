package padej.client.portal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.IDN;
import java.util.Hashtable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

public final class PortalServerTransferController {
    private static final Logger LOGGER = LoggerFactory.getLogger("PortalTransfer");

    private static final Text SAVING_LEVEL_TEXT = Text.translatable("menu.savingLevel");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("^\\[(.+)]\\(https?://[^)]+\\)$");

    private static final double CROSSING_EPSILON = 0.03D;
    private static final double PORTAL_BOUNDS_MARGIN = 0.20D;
    private static final double MAX_TRIGGER_DISTANCE_FROM_PLANE = 1.50D;

    private static final long CONNECT_COOLDOWN_TICKS = 80L;
    private static final long DISCONNECT_TIMEOUT_TICKS = 20L * 12L;
    private static final long ADDRESS_RESOLVE_TIMEOUT_TICKS = 20L * 2L;
    private static final long CONNECT_SCREEN_TIMEOUT_TICKS = 20L * 6L;

    private static final ExecutorService DNS_RESOLVE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "protocol-portals-dns-resolver");
        thread.setDaemon(true);
        return thread;
    });

    private final PortalManager portalManager;
    private final Map<UUID, Double> lastSignedDistanceByPortal = new HashMap<>();
    private final Set<UUID> invalidAddressWarnedPortals = new HashSet<>();

    private long tickCounter = 0L;
    private long connectCooldownUntilTick = 0L;
    private PendingTransfer pendingTransfer = null;

    public PortalServerTransferController(PortalManager portalManager) {
        this.portalManager = portalManager;
    }

    public void tick(MinecraftClient client) {
        tickCounter++;

        if (pendingTransfer != null) {
            processPendingTransfer(client);
            return;
        }

        if (client.player == null || client.world == null) {
            clearTracking();
            return;
        }

        if (client.currentScreen instanceof ConnectScreen) {
            return;
        }

        List<PortalInstance> portals = portalManager.activePortals();
        if (portals.isEmpty()) {
            clearTracking();
            return;
        }

        Vec3d eyePos = client.player.getEyePos();
        Set<UUID> activePortalIds = new HashSet<>(portals.size());

        for (PortalInstance portal : portals) {
            UUID portalId = portal.id();
            activePortalIds.add(portalId);

            String rawAddress = portal.serverAddress();
            if (rawAddress == null) {
                lastSignedDistanceByPortal.remove(portalId);
                invalidAddressWarnedPortals.remove(portalId);
                continue;
            }

            Vec3d relative = eyePos.subtract(portal.center());
            double signedDistance = relative.dotProduct(portal.normal());
            Double previousSignedDistance = lastSignedDistanceByPortal.put(portalId, signedDistance);

            if (previousSignedDistance == null) {
                continue;
            }
            if (!isWithinPortalBounds(relative, portal)) {
                continue;
            }
            if (!isCrossingPlane(previousSignedDistance, signedDistance)) {
                continue;
            }
            if (Math.abs(previousSignedDistance) > MAX_TRIGGER_DISTANCE_FROM_PLANE
                    && Math.abs(signedDistance) > MAX_TRIGGER_DISTANCE_FROM_PLANE) {
                continue;
            }
            if (tickCounter < connectCooldownUntilTick) {
                continue;
            }

            String normalizedAddress = normalizeAddress(rawAddress);
            if (normalizedAddress == null) {
                warnInvalidAddress(client, portal, rawAddress);
                continue;
            }
            invalidAddressWarnedPortals.remove(portalId);

            LOGGER.info("[PortalTransfer] CROSSING portal='{}' addr='{}' prevDist={} curDist={} screen={} integrated={}",
                    portal.sceneName(), normalizedAddress,
                    String.format("%.3f", previousSignedDistance),
                    String.format("%.3f", signedDistance),
                    screenName(client.currentScreen),
                    client.isIntegratedServerRunning());

            connectCooldownUntilTick = tickCounter + CONNECT_COOLDOWN_TICKS;
            pendingTransfer = new PendingTransfer(
                    portal.sceneName(),
                    normalizedAddress,
                    tickCounter,
                    resolveConnectAddressAsync(normalizedAddress)
            );
            break;
        }

        lastSignedDistanceByPortal.keySet().removeIf(id -> !activePortalIds.contains(id));
        invalidAddressWarnedPortals.removeIf(id -> !activePortalIds.contains(id));
    }

    private void processPendingTransfer(MinecraftClient client) {
        PendingTransfer transfer = pendingTransfer;
        if (transfer == null) {
            return;
        }

        switch (transfer.stage) {
            case DISCONNECT -> {
                issueVanillaDisconnect(client, transfer);
                transfer.advanceTo(TransferStage.WAIT_FOR_DISCONNECT, tickCounter);
            }
            case WAIT_FOR_DISCONNECT -> {
                if (isFullyDisconnected(client)) {
                    transfer.advanceTo(TransferStage.CONNECT, tickCounter);
                    return;
                }

                if (tickCounter - transfer.stageStartTick > DISCONNECT_TIMEOUT_TICKS) {
                    LOGGER.warn("[PortalTransfer] Disconnect timeout for portal='{}' addr='{}'. worldNull={} playerNull={} integrated={}",
                            transfer.sceneName,
                            transfer.serverAddress,
                            client.world == null,
                            client.player == null,
                            client.isIntegratedServerRunning());
                    pendingTransfer = null;
                }
            }
            case CONNECT -> {
                String connectAddress = resolveConnectAddressForTransfer(transfer);
                if (connectAddress == null) {
                    return;
                }
                startVanillaConnect(client, transfer, connectAddress);
                transfer.advanceTo(TransferStage.WAIT_FOR_CONNECT_SCREEN, tickCounter);
            }
            case WAIT_FOR_CONNECT_SCREEN -> {
                if (client.currentScreen instanceof ConnectScreen) {
                    LOGGER.info("[PortalTransfer] ConnectScreen opened for portal='{}' addr='{}'",
                            transfer.sceneName,
                            transfer.serverAddress);
                    pendingTransfer = null;
                    return;
                }

                if (tickCounter - transfer.stageStartTick > CONNECT_SCREEN_TIMEOUT_TICKS) {
                    LOGGER.warn("[PortalTransfer] Connect screen timeout for portal='{}' addr='{}', currentScreen={}",
                            transfer.sceneName,
                            transfer.serverAddress,
                            screenName(client.currentScreen));
                    pendingTransfer = null;
                }
            }
        }
    }

    private static void issueVanillaDisconnect(MinecraftClient client, PendingTransfer transfer) {
        boolean singleplayer = client.isInSingleplayer();
        ServerInfo currentServerEntry = client.getCurrentServerEntry();

        LOGGER.info("[PortalTransfer] Disconnecting before transfer portal='{}' addr='{}' screen={} integrated={} singleplayer={}",
                transfer.sceneName,
                transfer.serverAddress,
                screenName(client.currentScreen),
                client.isIntegratedServerRunning(),
                singleplayer);

        if (client.world != null) {
            client.world.disconnect();
        }

        if (singleplayer) {
            client.disconnect(new MessageScreen(SAVING_LEVEL_TEXT));
        } else {
            client.disconnect();
        }

        transfer.postDisconnectScreen = createPostDisconnectScreen(singleplayer, currentServerEntry);
        client.setScreen(transfer.postDisconnectScreen);
    }

    private String resolveConnectAddressForTransfer(PendingTransfer transfer) {
        if (transfer.resolvedConnectAddress != null) {
            return transfer.resolvedConnectAddress;
        }

        if (transfer.connectAddressFuture.isDone()) {
            String resolved;
            try {
                resolved = transfer.connectAddressFuture.getNow(transfer.serverAddress);
            } catch (Exception ex) {
                resolved = transfer.serverAddress;
            }
            if (resolved == null || !ServerAddress.isValid(resolved)) {
                resolved = transfer.serverAddress;
            }
            transfer.resolvedConnectAddress = resolved;
            if (!resolved.equals(transfer.serverAddress)) {
                LOGGER.info("[PortalTransfer] Using SRV redirect '{}' -> '{}'",
                        transfer.serverAddress, resolved);
            }
            return transfer.resolvedConnectAddress;
        }

        if (tickCounter - transfer.stageStartTick > ADDRESS_RESOLVE_TIMEOUT_TICKS) {
            transfer.resolvedConnectAddress = transfer.serverAddress;
            LOGGER.warn("[PortalTransfer] Address resolve timeout for '{}', using original address.",
                    transfer.serverAddress);
            return transfer.resolvedConnectAddress;
        }

        return null;
    }

    private static CompletableFuture<String> resolveConnectAddressAsync(String normalizedAddress) {
        return CompletableFuture.supplyAsync(() -> resolveSrvRedirectAddress(normalizedAddress), DNS_RESOLVE_EXECUTOR)
                .exceptionally(ex -> normalizedAddress);
    }

    private static String resolveSrvRedirectAddress(String normalizedAddress) {
        ServerAddress parsed = ServerAddress.parse(normalizedAddress);
        if (parsed.getPort() != 25565) {
            return normalizedAddress;
        }

        String host = parsed.getAddress();
        String lookupName = "_minecraft._tcp." + host;
        DirContext context = null;
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(lookupName, new String[]{"SRV"});
            Attribute srv = attributes.get("SRV");
            if (srv == null || srv.size() == 0) {
                return normalizedAddress;
            }

            String selectedAddress = normalizedAddress;
            int bestPriority = Integer.MAX_VALUE;
            int bestWeight = Integer.MIN_VALUE;

            NamingEnumeration<?> entries = srv.getAll();
            while (entries.hasMore()) {
                Object rawEntry = entries.next();
                if (rawEntry == null) {
                    continue;
                }
                String entry = rawEntry.toString().trim();
                String[] parts = entry.split("\\s+");
                if (parts.length < 4) {
                    continue;
                }

                int priority;
                int weight;
                int port;
                try {
                    priority = Integer.parseInt(parts[0]);
                    weight = Integer.parseInt(parts[1]);
                    port = Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String targetHost = parts[3].trim();
                if (targetHost.endsWith(".")) {
                    targetHost = targetHost.substring(0, targetHost.length() - 1);
                }
                if (targetHost.isEmpty()) {
                    continue;
                }

                String candidate = port == 25565 ? targetHost : targetHost + ":" + port;
                if (!ServerAddress.isValid(candidate)) {
                    continue;
                }

                if (priority < bestPriority || (priority == bestPriority && weight > bestWeight)) {
                    bestPriority = priority;
                    bestWeight = weight;
                    selectedAddress = candidate;
                }
            }

            return selectedAddress;
        } catch (Exception ex) {
            LOGGER.debug("[PortalTransfer] SRV lookup failed for '{}': {}", normalizedAddress, ex.toString());
            return normalizedAddress;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                }
            }
        }
    }

    private static void startVanillaConnect(MinecraftClient client, PendingTransfer transfer, String connectAddress) {
        Screen parentScreen = transfer.postDisconnectScreen instanceof MultiplayerScreen
                ? transfer.postDisconnectScreen
                : new MultiplayerScreen(new TitleScreen());

        ServerAddress parsedConnectAddress = ServerAddress.parse(connectAddress);
        ServerInfo serverInfo = new ServerInfo(
                "Portal: " + transfer.sceneName,
                connectAddress,
                ServerInfo.ServerType.OTHER
        );

        LOGGER.info("[PortalTransfer] Connecting portal='{}' addr='{}' connectAddr='{}' hostDebug='{}' parent={} integrated={}",
                transfer.sceneName,
                transfer.serverAddress,
                connectAddress,
                debugHostString(parsedConnectAddress.getAddress()),
                screenName(parentScreen),
                client.isIntegratedServerRunning());

        ConnectScreen.connect(
                parentScreen,
                client,
                parsedConnectAddress,
                serverInfo,
                false,
                null
        );
    }

    private static Screen createPostDisconnectScreen(boolean singleplayer, ServerInfo currentServerEntry) {
        TitleScreen titleScreen = new TitleScreen();
        if (singleplayer) {
            return titleScreen;
        }
        if (currentServerEntry != null && currentServerEntry.isRealm()) {
            return new RealmsMainScreen(titleScreen);
        }
        return new MultiplayerScreen(titleScreen);
    }

    private static boolean isFullyDisconnected(MinecraftClient client) {
        return client.world == null
                && client.player == null
                && client.getNetworkHandler() == null
                && !client.isIntegratedServerRunning();
    }

    private void warnInvalidAddress(MinecraftClient client, PortalInstance portal, String serverAddress) {
        if (!invalidAddressWarnedPortals.add(portal.id())) {
            return;
        }
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("Portal '" + portal.sceneName() + "' has invalid server address: " + serverAddress),
                    false
            );
        }
    }

    private static String screenName(Screen screen) {
        return screen == null ? "null(in-game)" : screen.getClass().getSimpleName();
    }

    private static boolean isCrossingPlane(double previous, double current) {
        if (Math.abs(previous) <= CROSSING_EPSILON && Math.abs(current) <= CROSSING_EPSILON) {
            return false;
        }
        return (previous > CROSSING_EPSILON && current < -CROSSING_EPSILON)
                || (previous < -CROSSING_EPSILON && current > CROSSING_EPSILON);
    }

    private static boolean isWithinPortalBounds(Vec3d relative, PortalInstance portal) {
        double halfWidth = portal.width() * 0.5D + PORTAL_BOUNDS_MARGIN;
        double halfHeight = portal.height() * 0.5D + PORTAL_BOUNDS_MARGIN;
        return Math.abs(relative.dotProduct(portal.right())) <= halfWidth
                && Math.abs(relative.dotProduct(portal.up())) <= halfHeight;
    }

    private void clearTracking() {
        lastSignedDistanceByPortal.clear();
        invalidAddressWarnedPortals.clear();
    }

    private static String normalizeAddress(String rawAddress) {
        String address = rawAddress == null ? "" : rawAddress.trim();
        if (address.isEmpty()) {
            return null;
        }

        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(address);
        if (matcher.matches()) {
            address = matcher.group(1).trim();
        }

        if (address.regionMatches(true, 0, "http://", 0, 7)) {
            address = address.substring(7);
        } else if (address.regionMatches(true, 0, "https://", 0, 8)) {
            address = address.substring(8);
        }

        int slashIndex = address.indexOf('/');
        if (slashIndex >= 0) {
            address = address.substring(0, slashIndex).trim();
        }

        // Remove invisible/control characters that often appear after copy-paste.
        address = stripInvisibleCharacters(address);
        if (address.isEmpty()) {
            return null;
        }

        if (!ServerAddress.isValid(address)) {
            return null;
        }

        ServerAddress parsed = ServerAddress.parse(address);
        String host = stripInvisibleCharacters(parsed.getAddress());
        if (host.isEmpty()) {
            return null;
        }

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        asciiHost = asciiHost.trim();
        if (asciiHost.endsWith(".")) {
            asciiHost = asciiHost.substring(0, asciiHost.length() - 1);
        }
        if (asciiHost.isEmpty()) {
            return null;
        }

        int port = parsed.getPort();
        String normalized = port == 25565 ? asciiHost : asciiHost + ":" + port;
        return ServerAddress.isValid(normalized) ? normalized : null;
    }

    private static String stripInvisibleCharacters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                continue;
            }
            // Zero-width and BOM characters that visually look like "normal" text.
            if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') {
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String debugHostString(String host) {
        StringBuilder sb = new StringBuilder(host.length() * 6);
        for (int i = 0; i < host.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("U+");
            String hex = Integer.toHexString(host.charAt(i)).toUpperCase();
            for (int p = hex.length(); p < 4; p++) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private enum TransferStage {
        DISCONNECT,
        WAIT_FOR_DISCONNECT,
        CONNECT,
        WAIT_FOR_CONNECT_SCREEN
    }

    private static final class PendingTransfer {
        final String sceneName;
        final String serverAddress;
        final long triggerTick;
        final CompletableFuture<String> connectAddressFuture;
        TransferStage stage = TransferStage.DISCONNECT;
        long stageStartTick;
        Screen postDisconnectScreen;
        String resolvedConnectAddress;

        PendingTransfer(
                String sceneName,
                String serverAddress,
                long triggerTick,
                CompletableFuture<String> connectAddressFuture
        ) {
            this.sceneName = sceneName;
            this.serverAddress = serverAddress;
            this.triggerTick = triggerTick;
            this.stageStartTick = triggerTick;
            this.connectAddressFuture = connectAddressFuture;
        }

        void advanceTo(TransferStage nextStage, long currentTick) {
            this.stage = nextStage;
            this.stageStartTick = currentTick;
        }
    }
}
