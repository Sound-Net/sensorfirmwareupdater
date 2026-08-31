package org.soundnet.flasher;

import com.pixelduke.transit.Style;
import com.pixelduke.transit.TransitStyleClass;
import com.pixelduke.transit.TransitTheme;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.Optional;

/**
 * The whole user interface: pick a port, pick a firmware build, press the button.
 *
 * <p>Written for researchers who will never open an IDE, so the visible surface
 * stays at two drop-downs and a button. Which of the two COM ports the
 * bootloader landed on, block sizes, checksums and downloads all happen
 * underneath, surfacing only in the collapsed details pane or when something
 * goes wrong.
 */
public final class FlasherApp extends Application {

    private final ComboBox<PortInfo> portBox = new ComboBox<>();
    private final ComboBox<FirmwareImage> firmwareBox = new ComboBox<>();
    private final Button updateButton = new Button("Update sensor");
    private final Button refreshPortsButton = new Button("Refresh");
    private final Button refreshCatalogButton = new Button("Check for new firmware");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Connect a sensor by USB to begin.");
    private final Label firmwareNotes = new Label();
    private final Label catalogStatus = new Label("Looking for firmware...");
    private final TextArea logArea = new TextArea();

    private final FirmwareCatalog catalog = new FirmwareCatalog();

    private Timeline portPoller;
    private boolean busy;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Label title = new Label("SoundNet Firmware Updater");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label(
                "Installs new firmware on a SoundNet sensor over USB. "
                        + "Nothing else needs to be installed.");
        subtitle.getStyleClass().add("app-subtitle");
        subtitle.setWrapText(true);

        portBox.setMaxWidth(Double.MAX_VALUE);
        portBox.setPlaceholder(new Label("No serial ports found"));
        HBox.setHgrow(portBox, Priority.ALWAYS);
        refreshPortsButton.setOnAction(e -> refreshPorts());
        HBox portRow = new HBox(8, portBox, refreshPortsButton);
        portRow.setAlignment(Pos.CENTER_LEFT);

        firmwareBox.setMaxWidth(Double.MAX_VALUE);
        firmwareBox.setPlaceholder(new Label("No firmware available"));
        firmwareBox.valueProperty().addListener((obs, old, value) ->
                firmwareNotes.setText(value == null ? "" : value.detail()));
        firmwareNotes.getStyleClass().add("notes");
        firmwareNotes.setWrapText(true);

        catalogStatus.getStyleClass().add("notes");
        catalogStatus.setWrapText(true);
        refreshCatalogButton.setOnAction(e -> refreshCatalog(true));
        HBox catalogRow = new HBox(10, catalogStatus, spacer(), refreshCatalogButton);
        catalogRow.setAlignment(Pos.CENTER_LEFT);

        // Transit paints :default buttons in the accent colour, so marking it
        // the default button is what makes it the primary action.
        updateButton.getStyleClass().add("primary-action");
        updateButton.setMaxWidth(Double.MAX_VALUE);
        updateButton.setDisable(true);
        updateButton.setDefaultButton(true);
        updateButton.setOnAction(e -> onUpdate());

        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        statusLabel.getStyleClass().add("status");
        statusLabel.setWrapText(true);

        logArea.setEditable(false);
        logArea.setPrefRowCount(9);
        logArea.getStyleClass().add("log");
        TitledPane details = new TitledPane("Details", logArea);
        details.setExpanded(false);

        VBox root = new VBox(16,
                title,
                subtitle,
                new Separator(),
                section("1.   Sensor", portRow),
                section("2.   Firmware", new VBox(6, firmwareBox, firmwareNotes, catalogRow)),
                updateButton,
                progressBar,
                statusLabel,
                details);
        root.setPadding(new Insets(24));
        // Transit paints its own window background through this style class.
        root.getStyleClass().add(TransitStyleClass.BACKGROUND);

        Scene scene = new Scene(root, 600, 580);
        new TransitTheme(scene, Style.LIGHT);
        // Loaded after the theme so these rules win over it.
        scene.getStylesheets().add(
                FlasherApp.class.getResource("/style.css").toExternalForm());

        stage.setTitle("SoundNet Firmware Updater");
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(560);
        stage.show();

        refreshPorts();
        loadFirmwareOffline();
        refreshCatalog(false);

        // Sensors get plugged in after the window is already open.
        portPoller = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (!busy) {
                refreshPorts();
            }
        }));
        portPoller.setCycleCount(Animation.INDEFINITE);
        portPoller.play();
    }

    @Override
    public void stop() {
        if (portPoller != null) {
            portPoller.stop();
        }
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private VBox section(String heading, Region content) {
        Label label = new Label(heading);
        label.getStyleClass().add("section-heading");
        return new VBox(7, label, content);
    }

    // ------------------------------------------------------------- firmware

    /** Fills the drop-down from what is already on this computer, immediately. */
    private void loadFirmwareOffline() {
        applyCatalog(catalog.loadOffline(uiListener()));
    }

    /**
     * Asks the firmware repository what is published. Runs in the background so a
     * slow or missing connection never holds up the window.
     */
    private void refreshCatalog(boolean userAsked) {
        refreshCatalogButton.setDisable(true);
        if (userAsked) {
            catalogStatus.setText("Checking the firmware repository...");
        }
        Thread thread = new Thread(() -> {
            FirmwareCatalog.Result result = catalog.fetchOnline(uiListener());
            Platform.runLater(() -> {
                applyCatalog(result);
                refreshCatalogButton.setDisable(busy);
                if (userAsked && !result.online()) {
                    catalogStatus.setText(result.status()
                            + ".  Could not reach the firmware repository.");
                }
            });
        }, "soundnet-catalog");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyCatalog(FirmwareCatalog.Result result) {
        FirmwareImage selected = firmwareBox.getValue();
        firmwareBox.getItems().setAll(result.images());
        if (selected != null && result.images().contains(selected)) {
            firmwareBox.setValue(selected);
        } else if (!result.images().isEmpty()) {
            firmwareBox.getSelectionModel().selectFirst();
        }
        catalogStatus.setText(result.status());
        if (result.images().isEmpty()) {
            statusLabel.setText("No firmware is available to install.");
        }
        updateReadiness();
    }

    // ----------------------------------------------------------------- ports

    private void refreshPorts() {
        PortInfo selected = portBox.getValue();
        List<PortInfo> ports = SerialPortService.listPorts();
        if (!ports.equals(portBox.getItems())) {
            portBox.getItems().setAll(ports);
            if (selected != null && ports.contains(selected)) {
                portBox.setValue(selected);
            } else {
                // Pick the sensor automatically when there is exactly one.
                ports.stream().filter(PortInfo::isProMicro).findFirst()
                        .ifPresent(portBox::setValue);
            }
        }
        updateReadiness();
    }

    private void updateReadiness() {
        updateButton.setDisable(busy
                || portBox.getValue() == null
                || firmwareBox.getValue() == null);
    }

    // ---------------------------------------------------------------- update

    private void onUpdate() {
        PortInfo port = portBox.getValue();
        FirmwareImage image = firmwareBox.getValue();
        if (port == null || image == null) {
            return;
        }
        setBusy(true);
        logArea.clear();
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("Checking the sensor...");

        // Identify before writing anything. SENSOR_TYPE is compiled in, so
        // installing the wrong revision gives a sensor that runs but cannot talk
        // to its Xsens - worth one dialog to avoid.
        Thread probe = new Thread(() -> {
            Optional<DeviceProbe.Identity> identity =
                    port.isBootloader() ? Optional.empty()
                            : DeviceProbe.identify(port.systemName(), uiListener());
            Platform.runLater(() -> afterProbe(port, image, identity));
        }, "soundnet-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void afterProbe(PortInfo port, FirmwareImage image,
                            Optional<DeviceProbe.Identity> identity) {
        if (identity.isPresent()) {
            BoardRevision detected = identity.get().revision();
            if (detected != null && detected != image.revision()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Check the hardware revision");
                alert.setHeaderText("This sensor says it is a " + detected.name() + ".");
                alert.setContentText(
                        "You have chosen firmware for " + image.revision().name() + ".\n\n"
                                + "These revisions use different pins, so the wrong firmware will "
                                + "leave the sensor running but unable to read its motion sensor.\n\n"
                                + "Install " + image.revision().name() + " firmware anyway?");
                alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
                alert.initOwner(updateButton.getScene().getWindow());
                styleDialog(alert);
                Optional<ButtonType> choice = alert.showAndWait();
                if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                    log("Update cancelled - hardware revision did not match.");
                    statusLabel.setText("Update cancelled.");
                    progressBar.setVisible(false);
                    setBusy(false);
                    return;
                }
                log("Proceeding despite revision mismatch, at the user's request.");
            }
        } else {
            log("Continuing without identifying the sensor first.");
        }
        startFlash(port, image);
    }

    private void startFlash(PortInfo port, FirmwareImage image) {
        FlashTask task = new FlashTask(port.systemName(), image, this::log);

        progressBar.progressProperty().unbind();
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().unbind();
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            unbind();
            progressBar.setProgress(1.0);
            statusLabel.getStyleClass().removeIf("error"::equals);
            statusLabel.setText("Done. The sensor is now running "
                    + image.revision().name() + " v" + image.version() + ".");
            setBusy(false);
        });

        task.setOnFailed(e -> {
            unbind();
            progressBar.setVisible(false);
            Throwable error = task.getException();
            String message = error instanceof FlashException
                    ? error.getMessage()
                    : "Something went wrong: " + describe(error);
            statusLabel.getStyleClass().add("error");
            statusLabel.setText(message.split("\\R")[0]);
            log("FAILED: " + message);
            showError(message);
            setBusy(false);
        });

        Thread thread = new Thread(task, "soundnet-flash");
        thread.setDaemon(true);
        thread.start();
    }

    private void unbind() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return (message == null || message.isBlank())
                ? error.getClass().getSimpleName() : message;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("The sensor was not updated");
        alert.setHeaderText("The sensor was not updated.");
        alert.setContentText(message);
        alert.getDialogPane().setPrefWidth(540);
        alert.initOwner(updateButton.getScene().getWindow());
        styleDialog(alert);
        alert.showAndWait();
    }

    /** Dialogs get their own Scene, so the theme has to be applied to each one. */
    private void styleDialog(Alert alert) {
        Scene scene = alert.getDialogPane().getScene();
        new TransitTheme(scene, Style.LIGHT);
        alert.getDialogPane().getStyleClass().add(TransitStyleClass.BACKGROUND);
    }

    private void setBusy(boolean value) {
        busy = value;
        portBox.setDisable(value);
        firmwareBox.setDisable(value);
        refreshPortsButton.setDisable(value);
        refreshCatalogButton.setDisable(value);
        if (!value) {
            statusLabel.getStyleClass().removeIf("error"::equals);
        }
        updateReadiness();
    }

    private FlashListener uiListener() {
        return new FlashListener() {
            public void stage(String message) {
                Platform.runLater(() -> statusLabel.setText(message));
            }

            public void progress(double fraction) {
            }

            public void log(String message) {
                Platform.runLater(() -> FlasherApp.this.log(message));
            }
        };
    }

    private void log(String message) {
        logArea.appendText(message + System.lineSeparator());
    }
}
