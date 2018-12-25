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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


public class MainController {

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
        timestampColumn.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
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
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Export File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KML File (.kml)", "*.kml"));
        File saveFile = fileChooser.showSaveDialog(primaryStage);
        if (saveFile != null) {
            List<ExtractItem> exportItems = tableModel
                    .stream()
                    .filter(ExtractItem::isSuccess)
                    .sorted(Comparator.comparing(o -> o.getWaypoint().timestamp))
                    .collect(Collectors.toList());
            kmlOutputWorker = new KmlOutputWorker()
                    .items(exportItems)
                    .outputFile(saveFile)
                    .callback(this::handleKmlOutputFinished)
                    .start();
            statusLabel.setText("Writing KML file...");
        }
    }

    @FXML
    public void onCancelButton() {
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

        System.out.println(getClass().getCanonicalName() + " tableModel.size=" + tableModel.size());
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

//    private void intiView() {

//
//        frame = new JFrame("Photo2KML");
//        frame.setPreferredSize(new Dimension(width, height));
//        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//        frame.setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("/app.png")));
//
//        contentPanel = new JPanel(new GridBagLayout());
//        tableModel = new TableModel();
//        table = new JTable(tableModel);
//        statusLabel = new JLabel();
//        progressBar = new JProgressBar();
//        cancelButton = new JButton();
//
//        GridBagConstraints c = new GridBagConstraints();
//        c.fill = GridBagConstraints.HORIZONTAL;
//        c.gridx = 0;
//        c.gridy = 0;
//        JScrollPane scrollPane = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
//        contentPanel.add(scrollPane, c);
//
//        frame.setContentPane(contentPanel);
//        frame.pack();
//        frame.setVisible(true);
//    }
}
