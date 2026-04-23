package padej.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import padej.client.portal.PortalManager;
import padej.client.scene.SceneRepository;
import padej.client.scene.SceneSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;

public final class ProtocolPortalsScreen extends Screen {
    private final SceneRepository sceneRepository;
    private final PortalManager portalManager;
    private final Screen parent;

    private TextFieldWidget sceneNameField;
    private TextFieldWidget captureRadiusField;
    private ButtonWidget createSnapshotButton;
    private ButtonWidget createPortalButton;
    private ButtonWidget createDebugPlaneButton;
    private ButtonWidget removeNearestButton;
    private ButtonWidget removeByNameButton;
    private ButtonWidget autoRadiusButton;
    private ButtonWidget closeButton;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int closeButtonY;
    private int hintY;
    private int statusBoxY;
    private int statusBoxHeight;
    private int radiusLabelY;
    private boolean showHintLine;

    private String statusMessage = "Ready. Enter scene name and choose an action.";
    private int statusColor = 0xFFE2E2E2;

    public ProtocolPortalsScreen(Screen parent, SceneRepository sceneRepository, PortalManager portalManager) {
        super(Text.literal("Protocol Portals"));
        this.parent = parent;
        this.sceneRepository = sceneRepository;
        this.portalManager = portalManager;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(420, width - 20);
        int availablePanelHeight = Math.max(170, height - 20);
        int fieldHeight = 20;
        int buttonHeight = 20;
        int fieldGap = 6;
        int rowGap = 4;
        int sectionGap = 8;
        int topOffset = 28;
        int bottomPadding = 10;
        statusBoxHeight = 46;
        showHintLine = true;

        int panelHeight = measurePanelHeight(fieldHeight, buttonHeight, fieldGap, rowGap, sectionGap, topOffset, bottomPadding, statusBoxHeight, showHintLine);
        if (panelHeight > availablePanelHeight) {
            showHintLine = false;
            statusBoxHeight = 38;
            rowGap = 3;
            sectionGap = 6;
            topOffset = 26;
            panelHeight = measurePanelHeight(fieldHeight, buttonHeight, fieldGap, rowGap, sectionGap, topOffset, bottomPadding, statusBoxHeight, showHintLine);
        }

        if (panelHeight > availablePanelHeight) {
            statusBoxHeight = 30;
            rowGap = 2;
            sectionGap = 4;
            topOffset = 24;
            panelHeight = measurePanelHeight(fieldHeight, buttonHeight, fieldGap, rowGap, sectionGap, topOffset, bottomPadding, statusBoxHeight, showHintLine);
        }

        panelHeight = Math.min(panelHeight, availablePanelHeight);
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(10, (height - panelHeight) / 2);
        panelBottom = panelTop + panelHeight;

        int contentLeft = panelLeft + 10;
        int contentWidth = panelWidth - 20;
        int y = panelTop + topOffset;

        sceneNameField = new TextFieldWidget(
                textRenderer,
                contentLeft,
                y,
                contentWidth,
                20,
                Text.literal("Scene Name")
        );
        sceneNameField.setMaxLength(64);
        sceneNameField.setPlaceholder(Text.literal("scene_name"));
        sceneNameField.setDrawsBackground(true);
        addDrawableChild(sceneNameField);
        y += fieldHeight + fieldGap;

        int radiusFieldWidth = 72;
        int autoButtonWidth = 56;
        int radiusGap = 6;
        int radiusFieldX = contentLeft + contentWidth - radiusFieldWidth - autoButtonWidth - radiusGap;
        radiusLabelY = y + 6;
        captureRadiusField = new TextFieldWidget(
                textRenderer,
                radiusFieldX,
                y,
                radiusFieldWidth,
                buttonHeight,
                Text.literal("Capture Radius")
        );
        captureRadiusField.setMaxLength(2);
        captureRadiusField.setPlaceholder(Text.literal("auto"));
        captureRadiusField.setDrawsBackground(true);
        if (sceneRepository.isManualCaptureChunkRadiusEnabled()) {
            captureRadiusField.setText(String.valueOf(sceneRepository.manualCaptureChunkRadius()));
        }
        addDrawableChild(captureRadiusField);

        autoRadiusButton = addDrawableChild(ButtonWidget.builder(Text.literal("Auto"), button -> setCaptureRadiusAutoFromUi())
                .dimensions(radiusFieldX + radiusFieldWidth + radiusGap, y, autoButtonWidth, buttonHeight)
                .build());
        y += buttonHeight + sectionGap;

        int gap = 6;
        int columnWidth = (contentWidth - gap) / 2;

        createSnapshotButton = addDrawableChild(ButtonWidget.builder(Text.literal("Create Snapshot"), button -> startSnapshot())
                .dimensions(contentLeft, y, columnWidth, 20)
                .build());

        createPortalButton = addDrawableChild(ButtonWidget.builder(Text.literal("Create Portal"), button -> createPortal())
                .dimensions(contentLeft + columnWidth + gap, y, columnWidth, 20)
                .build());
        y += buttonHeight + rowGap;

        createDebugPlaneButton = addDrawableChild(ButtonWidget.builder(Text.literal("Create Debug Plane"), button -> createDebugPlane())
                .dimensions(contentLeft, y, columnWidth, 20)
                .build());

        removeNearestButton = addDrawableChild(ButtonWidget.builder(Text.literal("Remove Nearest"), button -> removeNearestPortal())
                .dimensions(contentLeft + columnWidth + gap, y, columnWidth, 20)
                .build());
        y += buttonHeight + rowGap;

        removeByNameButton = addDrawableChild(ButtonWidget.builder(Text.literal("Remove By Scene Name"), button -> removePortalByName())
                .dimensions(contentLeft, y, contentWidth, 20)
                .build());
        y += buttonHeight + sectionGap;

        statusBoxY = y;
        y += statusBoxHeight + sectionGap;

        closeButtonY = y;
        closeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(contentLeft + (contentWidth - 96) / 2, y, 96, 20)
                .build());
        hintY = y + buttonHeight + 4;

        updateActionButtons();
        setInitialFocus(sceneNameField);
    }

    @Override
    public void tick() {
        super.tick();
        updateActionButtons();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (hasControlDown()) {
                startSnapshot();
            } else {
                createPortal();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xED101216);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 18, 0xFF232A36);
        context.drawBorder(panelLeft, panelTop, panelWidth, panelBottom - panelTop, 0xFF4B5A73);

        int centerX = panelLeft + panelWidth / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, panelTop + 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("Scene Name"), panelLeft + 10, panelTop + 20, 0xAFAFAF);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("Capture Radius (2-16, empty = auto)"),
                panelLeft + 10,
                radiusLabelY,
                0xAFAFAF
        );

        context.fill(panelLeft + 10, statusBoxY, panelLeft + panelWidth - 10, statusBoxY + statusBoxHeight, 0xF0121A23);
        context.drawBorder(panelLeft + 10, statusBoxY, panelWidth - 20, statusBoxHeight, 0xFF2F3C4D);
        context.drawTextWithShadow(textRenderer, Text.literal("Status"), panelLeft + 14, statusBoxY + 4, 0xBFCDE0);

        int textY = statusBoxY + 16;
        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(statusMessage), panelWidth - 28);
        int maxStatusLines = Math.max(1, (statusBoxHeight - 16) / 10);
        int visibleLineCount = Math.min(maxStatusLines, lines.size());
        for (int i = 0; i < visibleLineCount; i++) {
            context.drawTextWithShadow(textRenderer, lines.get(i), panelLeft + 14, textY + i * 10, statusColor);
        }

        if (showHintLine) {
            context.drawTextWithShadow(
                    textRenderer,
                    Text.literal("Enter: portal, Ctrl+Enter: snapshot"),
                    panelLeft + 10,
                    hintY,
                    0x888888
            );
        }
        renderInteractiveWidgets(context, mouseX, mouseY, delta);
    }

    private void updateActionButtons() {
        boolean validName = isSceneNameValidForActions();
        if (createSnapshotButton != null) {
            createSnapshotButton.active = validName;
        }
        if (createPortalButton != null) {
            createPortalButton.active = validName;
        }
        if (removeByNameButton != null) {
            removeByNameButton.active = validName;
        }
    }

    private boolean isSceneNameValidForActions() {
        if (sceneNameField == null) {
            return false;
        }
        String value = sceneNameField.getText().trim();
        return !value.isEmpty() && sceneRepository.isValidFileName(value);
    }

    private void startSnapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        String fileName = readSceneName();
        if (fileName == null) {
            return;
        }
        if (!applyCaptureRadiusFromUi()) {
            return;
        }
        if (client.player == null || client.world == null) {
            setError("You must be in world to capture snapshot.");
            return;
        }

        setStatus("Starting snapshot capture '" + fileName + "'...");
        try {
            SceneRepository.CaptureStartResult start = sceneRepository.startCapture(
                    client,
                    fileName,
                    result -> client.execute(() -> setStatus("Snapshot '" + result.sceneName() + "' saved. Blocks: "
                            + result.blockCount() + ", chunks: " + result.chunkCount() + "/" + result.requestedChunkCount())),
                    error -> client.execute(() -> setError(error))
            );
            setStatus("Capture scheduled for " + start.totalChunks() + " chunks (radius "
                    + (start.horizontalRadius() / 16) + " chunks).");
        } catch (IOException exception) {
            setError("Snapshot creation failed: " + exception.getMessage());
        }
    }

    private void createPortal() {
        MinecraftClient client = MinecraftClient.getInstance();
        String fileName = readSceneName();
        if (fileName == null) {
            return;
        }
        if (client.player == null) {
            setError("You must be in world to create portal.");
            return;
        }

        Optional<SceneSnapshot> scene = sceneRepository.load(fileName);
        if (scene.isEmpty()) {
            setError("Scene '" + fileName + "' not found. Create snapshot first.");
            return;
        }

        String normalized = fileName.toLowerCase(Locale.ROOT);
        setStatus("Preparing portal scene '" + fileName + "' on worker threads...");
        portalManager.createPortalAsync(client, client.player, normalized, scene.get())
                .thenAccept(portal -> client.execute(() -> setStatus("Portal created at "
                        + formatVec(portal.center().x, portal.center().y, portal.center().z)
                        + ", render blocks: " + portal.renderBlocks().size())))
                .exceptionally(throwable -> {
                    Throwable cause = unwrapCompletionCause(throwable);
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    client.execute(() -> setError("Portal creation failed: " + message));
                    return null;
                });
    }

    private void createDebugPlane() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            setError("You must be in world to create debug plane.");
            return;
        }
        var plane = portalManager.createDebugPlane(client.player);
        setStatus("Debug plane created at " + formatVec(plane.center().x, plane.center().y, plane.center().z));
    }

    private void removeNearestPortal() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            setError("You must be in world to remove portal.");
            return;
        }
        var removed = portalManager.removeNearestPortal(client.player.getPos());
        if (removed.isEmpty()) {
            setError("No portals to remove.");
            return;
        }
        setStatus("Removed nearest portal for scene '" + removed.get().sceneName() + "'.");
    }

    private void removePortalByName() {
        MinecraftClient client = MinecraftClient.getInstance();
        String fileName = readSceneName();
        if (fileName == null) {
            return;
        }
        if (client.player == null) {
            setError("You must be in world to remove portal.");
            return;
        }

        String normalized = fileName.toLowerCase(Locale.ROOT);
        var removed = portalManager.removePortalBySceneName(normalized, client.player.getPos());
        if (removed.isEmpty()) {
            setError("No portal found for scene '" + normalized + "'.");
            return;
        }
        setStatus("Removed portal for scene '" + removed.get().sceneName() + "'.");
    }

    private String readSceneName() {
        String value = sceneNameField == null ? "" : sceneNameField.getText().trim();
        if (value.isEmpty()) {
            setError("Enter scene name.");
            return null;
        }
        if (!sceneRepository.isValidFileName(value)) {
            setError(SceneRepository.invalidNameText(value).getString());
            return null;
        }
        return value;
    }

    private boolean applyCaptureRadiusFromUi() {
        if (captureRadiusField == null) {
            return true;
        }
        String raw = captureRadiusField.getText().trim();
        if (raw.isEmpty()) {
            sceneRepository.setCaptureChunkRadiusAuto();
            return true;
        }

        int chunks;
        try {
            chunks = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            setError("Capture radius must be a number from "
                    + sceneRepository.minCaptureChunkRadius() + " to " + sceneRepository.maxCaptureChunkRadius() + ".");
            return false;
        }

        int min = sceneRepository.minCaptureChunkRadius();
        int max = sceneRepository.maxCaptureChunkRadius();
        if (chunks < min || chunks > max) {
            setError("Capture radius must be between " + min + " and " + max + " chunks.");
            return false;
        }

        sceneRepository.setCaptureChunkRadiusManual(chunks);
        return true;
    }

    private void setCaptureRadiusAutoFromUi() {
        if (captureRadiusField != null) {
            captureRadiusField.setText("");
        }
        sceneRepository.setCaptureChunkRadiusAuto();
        setStatus("Capture radius mode set to auto (game setting).");
    }

    private void setStatus(String message) {
        statusMessage = message;
        statusColor = 0xFF8CFF9B;
    }

    private void setError(String message) {
        statusMessage = message;
        statusColor = 0xFFFF7B7B;
    }

    private static String formatVec(double x, double y, double z) {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }

    private static Throwable unwrapCompletionCause(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void renderInteractiveWidgets(DrawContext context, int mouseX, int mouseY, float delta) {
        if (sceneNameField != null) {
            sceneNameField.render(context, mouseX, mouseY, delta);
        }
        if (captureRadiusField != null) {
            captureRadiusField.render(context, mouseX, mouseY, delta);
        }
        if (autoRadiusButton != null) {
            autoRadiusButton.render(context, mouseX, mouseY, delta);
        }
        if (createSnapshotButton != null) {
            createSnapshotButton.render(context, mouseX, mouseY, delta);
        }
        if (createPortalButton != null) {
            createPortalButton.render(context, mouseX, mouseY, delta);
        }
        if (createDebugPlaneButton != null) {
            createDebugPlaneButton.render(context, mouseX, mouseY, delta);
        }
        if (removeNearestButton != null) {
            removeNearestButton.render(context, mouseX, mouseY, delta);
        }
        if (removeByNameButton != null) {
            removeByNameButton.render(context, mouseX, mouseY, delta);
        }
        if (closeButton != null) {
            closeButton.render(context, mouseX, mouseY, delta);
        }
    }

    private static int measurePanelHeight(
            int fieldHeight,
            int buttonHeight,
            int fieldGap,
            int rowGap,
            int sectionGap,
            int topOffset,
            int bottomPadding,
            int statusBoxHeight,
            boolean showHintLine
    ) {
        int hintHeight = showHintLine ? 12 : 0;
        int contentHeight =
                fieldHeight + fieldGap
                        + buttonHeight + sectionGap
                        + buttonHeight + rowGap
                        + buttonHeight + rowGap
                        + buttonHeight + sectionGap
                        + statusBoxHeight + sectionGap
                        + buttonHeight + hintHeight;
        return topOffset + contentHeight + bottomPadding;
    }
}
