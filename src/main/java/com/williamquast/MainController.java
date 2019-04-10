package com.williamquast;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


public class MainController {

    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZZ");

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML
    private TableView<ExtractItem> tableView;
    @FXML
    private TableColumn<ExtractItem, String> filenameColumn;
    @FXML
    private TableColumn<ExtractItem, String> timestampColumn;
    @FXML
    private TableColumn<ExtractItem, String> resultColumn;
    @FXML
    private Label statusLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Button cancelButton;

    private Stage primaryStage;
    private ObservableList<ExtractItem> tableModel;
    private PhotoLocationWorker photoLocationWorker;
    private KmlOutputWorker kmlOutputWorker;

    @FXML
    public void initialize() {
        log.info("initialize.");
        tableView.setPlaceholder(new Label("To begin, click File -> Search Directory..."));
        statusLabel.setText("Ready");
        progressBar.setProgress(0.0);
        progressBar.setVisible(false);
        cancelButton.setVisible(false);
        initTable();
    }

    private void initTable() {
        tableModel = FXCollections.observableArrayList();
        tableView.setItems(tableModel);
        filenameColumn.setCellValueFactory(new PropertyValueFactory<>("filename"));
        timestampColumn.setCellValueFactory(param ->
                new ReadOnlyObjectWrapper<>(TIMESTAMP_FORMAT.format(param.getValue().timestamp)));
        resultColumn.setCellValueFactory(param -> {
            ExtractItem item = param.getValue();
            if (item == null) {
                return null;
            }

            if (item.success) {
                return new ReadOnlyObjectWrapper<>(String.format("%.4f, %.4f", item.waypoint.y, item.waypoint.x));
            } else {
                return new ReadOnlyObjectWrapper<>(item.failureReason);
            }
        });
    }

    @FXML
    public void onSearchDirectoryMenuItem() {
        log.debug("onSearchDirectoryMenuItem.");
        DirectoryChooser dirChooser = new DirectoryChooser();
        File sourceDir = dirChooser.showDialog(primaryStage);
        if (sourceDir != null) {
            photoLocationWorker = new PhotoLocationWorker()
                    .sourceDir(sourceDir)
                    .finishListener(this::handlePhotoLocationFinished)
                    .progressListener(this::handleProgressUpdated)
                    .start();
        }
    }

    @FXML
    public void onExportAllAsKmlMenuItem() {
        log.debug("onExportAllAsKmlMenuItem.");
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Export File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KML File (.kml)", "*.kml"));
        File saveFile = fileChooser.showSaveDialog(primaryStage);
        if (saveFile != null) {
            kmlOutputWorker = new KmlOutputWorker()
                    .items(tableModel)
                    .sort(tableView.getComparator())
                    .outputFile(saveFile)
                    .callback(this::handleKmlOutputFinished)
                    .start();
            statusLabel.setText("Writing KML file...");
        }
    }

    @FXML
    public void onCancelButton() {
        log.debug("onCancelButton.");
        if (kmlOutputWorker != null) {
            kmlOutputWorker.cancel();
            kmlOutputWorker = null;
        }
        if (photoLocationWorker != null) {
            photoLocationWorker.cancel();
            photoLocationWorker = null;
        }
    }

    @FXML
    public void onCloseButton() {
        log.debug("onCloseButton.");
        onCancelButton();
        Stage stage = (Stage) cancelButton.getScene().getWindow();
        stage.close();
    }

    private void handlePhotoLocationFinished(PhotoLocationWorker.Result result) {
        if (result.success) {
            statusLabel.setText(String.format("Completed processing %d photos", result.processedItems));
        } else {
            statusLabel.setText(String.format("Failed. (%s)", result.failureReason));
        }
        progressBar.setVisible(false);
        cancelButton.setVisible(false);
        photoLocationWorker = null;

        log.debug("handlePhotoLocationFinished. tableModel.size=" + tableModel.size());
    }

    private void handleProgressUpdated(int totalProgress, int currentProgress, List<ExtractItem> items) {
        if (items != null && !items.isEmpty()) {
            tableModel.addAll(items);
        }
        progressBar.setVisible(true);
        progressBar.setProgress((double) currentProgress / (double)totalProgress);
        cancelButton.setVisible(true);
        statusLabel.setText(String.format("Processed %d of %d photos", currentProgress, totalProgress));
    }

    private void handleKmlOutputFinished(KmlOutputWorker.Result result) {
        progressBar.setVisible(false);
        cancelButton.setVisible(false);
        if (result.success) {
            statusLabel.setText("Save KML file complete. ");
        } else {
            statusLabel.setText(String.format("Save KML file failed. (%s)", result.failureReason));
        }
        kmlOutputWorker = null;
    }

    private void showAlert(String string) {
        Alert alert = new Alert(Alert.AlertType.ERROR, string, ButtonType.OK);
        alert.show();
    }
}
