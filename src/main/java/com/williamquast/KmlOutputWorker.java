package com.williamquast;

import javafx.application.Platform;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KmlOutputWorker {

    private static final Logger log = LoggerFactory.getLogger(KmlOutputWorker.class);

    private Thread thread;
    private List<ExtractItem> items;
    private Comparator<ExtractItem> sort;
    private File outputFile;
    private Consumer<Result> callback;

    public KmlOutputWorker items(final List<ExtractItem> items) {
        this.items = items;
        return this;
    }

    public KmlOutputWorker sort(final Comparator<ExtractItem> sort) {
        this.sort = sort;
        return this;
    }

    public KmlOutputWorker outputFile(final File outputFile) {
        this.outputFile = outputFile;
        return this;
    }

    public KmlOutputWorker callback(final Consumer<Result> callback) {
        this.callback = callback;
        return this;
    }

    public KmlOutputWorker start() {
        thread = new Thread(this::writeFile);
        thread.start();
        return this;
    }

    public void cancel() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void writeFile() {
        log.info("Begin writeFile. file=" + outputFile.getAbsolutePath());

        try {
            List<ExtractItem> sortedItems = items
                    .stream()
                    .filter(ExtractItem::isSuccess)
                    .sorted(sort)
                    .collect(Collectors.toList());

            PrintWriter output = new PrintWriter(new FileWriter(outputFile));

            output.println(
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                            "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" +
                            "  <Document>");

            for (ExtractItem result : sortedItems) {
                if (result.success) {
                    output.println(
                            "    <Placemark>\n" +
                                    "      <name>" + result.waypoint.name + "</name>\n" +
                                    "      <Point>\n" +
                                    "        <coordinates>" + result.waypoint.x + "," + result.waypoint.y + ",0</coordinates>\n" +
                                    "      </Point>\n" +
                                    "    </Placemark>");
                }
            }

            output.println(
                    "  </Document>\n" +
                            "</kml>");

            output.close();
            finish(new Result());
        } catch (Exception ex) {
            log.error("Failed writing KML document to file.", ex);
            finish(new Result(false, ex.getMessage()));
        }

        log.info("Finished writeFile. file=" + outputFile.getPath());
    }

    private void finish(final Result result) {
        Platform.runLater(() -> callback.accept(result));
    }

    public class Result {
        boolean success;
        String failureReason;

        public Result() {
            this.success = true;
        }

        public Result(boolean success, String failureReason) {
            this.success = success;
            this.failureReason = failureReason;
        }
    }
}
