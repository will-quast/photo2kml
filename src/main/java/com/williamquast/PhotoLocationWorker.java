package com.williamquast;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoLocationWorker {

    private static final int THREAD_COUNT = 2;
    private static final long TESTING_DELAY = 0;

    private Thread supervisorThread = new Thread();
    private ExecutorService executorService =
            new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT,
            0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>());
    private boolean cancelled = false;

    private File sourceDir;
    private FinishListener finishListener;
    private ProgressListener progressListener;

    private Phaser phaser = new Phaser();
    private AtomicInteger foundItems = new AtomicInteger();
    private AtomicInteger processedItems = new AtomicInteger();

    private AtomicBoolean uiReady = new AtomicBoolean(true);
    private List<ExtractItem> itemsBuffer = new ArrayList<>();

    public PhotoLocationWorker start() {
        supervisorThread = new Thread(this::processAndWait);
        supervisorThread.start();
        return this;
    }

    public void cancel() {
        cancelled = true;
        supervisorThread.interrupt();
    }

    private void processAndWait() {
        if (sourceDir == null) throw new IllegalStateException("SourceDir is required.");
        if (finishListener == null) throw new IllegalStateException("FinishListener is required.");
        if (progressListener == null) throw new IllegalStateException("ProgressListener is required.");

        try {
            // search recursively and process photos as they are found
            phaser.register();
            executorService.execute(new SearchDirectoryRunnable(sourceDir));

            // wait for all tasks to complete before advancing the SwingWorker
            phaser.register();
            phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister());

            finish(new Result(foundItems.get(), processedItems.get()));
        } catch (InterruptedException ex) {
            finish(new Result("Cancelled. (" + ex.getMessage() + ")", true));
        } catch (Exception ex) {
            finish(new Result("Unknown error. (" + ex.getMessage() + ")"));
        } finally {
            // work is done, release threads
            executorService.shutdownNow();
        }
    }

    private synchronized void finish(final Result result) {
        final List<ExtractItem> itemsDelivery = itemsBuffer;
        itemsBuffer = new ArrayList<>();

        Platform.runLater(() -> {
            // deliver any last items to the ui thread, regardless of uiReady
            progressListener.onProgress(foundItems.get(), processedItems.get(), itemsDelivery);

            finishListener.onFinished(result);
        });

        System.out.println(getClass().getCanonicalName() + " found=" + foundItems.get() + " processed=" + processedItems.get());
    }

    private synchronized void submitStatus() {
        if (uiReady.get()) {
            uiReady.set(false);
            Platform.runLater(() -> {
                progressListener.onProgress(foundItems.get(), processedItems.get(), null);
                uiReady.set(true);
            });
        }
    }

    private synchronized void submitResult(ExtractItem extractItem) {
        itemsBuffer.add(extractItem);

        if (uiReady.get()) {
            final List<ExtractItem> itemsDelivery = itemsBuffer;
            itemsBuffer = new ArrayList<>();

            uiReady.set(false);
            Platform.runLater(() -> {
                progressListener.onProgress(foundItems.get(), processedItems.get(), itemsDelivery);
                uiReady.set(true);
            });
        }
    }

    private void testingDelay() {
        if (TESTING_DELAY != 0) {
            try {
                Thread.sleep(TESTING_DELAY);
            } catch (InterruptedException e) { /* ignore */ }
        }
    }

    public PhotoLocationWorker sourceDir(final File sourceDir) {
        this.sourceDir = sourceDir;
        return this;
    }

    public PhotoLocationWorker finishListener(final FinishListener finishListener) {
        this.finishListener = finishListener;
        return this;
    }

    public PhotoLocationWorker progressListener(final ProgressListener progressListener) {
        this.progressListener = progressListener;
        return this;
    }

    @FunctionalInterface
    public interface FinishListener {
        void onFinished(Result result);
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int totalTotal, int currentProgress, List<ExtractItem> items);
    }

    public class Result {
        boolean success;
        boolean cancelled;
        String failureReason;
        int foundItems;
        int processedItems;

        public Result(int foundItems, int processedItems) {
            this.success = true;
            this.foundItems = foundItems;
            this.processedItems = processedItems;
        }

        public Result(String failureReason) {
            this.success = false;
            this.cancelled = false;
            this.failureReason = failureReason;
        }

        public Result(String failureReason, boolean cancelled) {
            this.success = false;
            this.cancelled = cancelled;
            this.failureReason = failureReason;
        }
    }

    private abstract class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {

        abstract int getPriority();

        @Override
        public int compareTo(PriorityRunnable o) {
            return this.getPriority() - o.getPriority();
        }
    }

    private class SearchDirectoryRunnable extends PriorityRunnable {

        private File dir;

        public SearchDirectoryRunnable(File dir) {
            this.dir = dir;
        }

        @Override
        public void run() {
            try {
                if (!dir.canRead()) {
                    submitResult(new ExtractItem(dir.getPath(), null, "source is not readable. (permissions)"));
                }
                if (!dir.isDirectory()) {
                    submitResult(new ExtractItem(dir.getPath(), null, "source is not a directory."));
                }

                testingDelay();
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            phaser.register(); // deregister in finally of SearchDirectoryRunnable
                            executorService.execute(new SearchDirectoryRunnable(file));
                        } else if (file.getName().toLowerCase().endsWith(".jpg")
                                || file.getName().toLowerCase().endsWith(".jpeg")
                                || file.getName().toLowerCase().endsWith(".png")) {
                            foundItems.incrementAndGet();
                            submitStatus();
                            phaser.register(); // deregister in finally of ProcessPhotoFileRunnable
                            executorService.execute(new ProcessPhotoFileRunnable(file));
                        }
                    }
                }
            } finally {
                phaser.arriveAndDeregister();
            }
        }

        @Override
        int getPriority() {
            return 0;
        }
    }

    private class ProcessPhotoFileRunnable extends PriorityRunnable {

        private File file;

        public ProcessPhotoFileRunnable(File file) {
            this.file = file;
        }

        @Override
        public void run() {
            try {
                String fileName = null;
                Date date = null;
                ExtractItem answer;
                try {
                    testingDelay();

                    BasicFileAttributes attr = Files.readAttributes (Paths.get(file.getPath()), BasicFileAttributes.class);
                    FileTime fileTime = attr.creationTime();
                    date = new Date(fileTime.toMillis());

                    fileName = file.getName();

                    Metadata metadata = ImageMetadataReader.readMetadata(file);

                    metadata.getDirectories().forEach(directory -> System.out.println(directory.toString()));

                    Collection<GpsDirectory> gpsDirectories = metadata.getDirectoriesOfType(GpsDirectory.class);
                    Iterator<GpsDirectory> itr = gpsDirectories.iterator();
                    if (itr.hasNext()) {
                        GpsDirectory gpsDirectory = itr.next();
                        if (gpsDirectory != null && gpsDirectory.getGeoLocation() != null) {
                            GeoLocation geoLocation = gpsDirectory.getGeoLocation();

                            // prefer to use GPS date
                            date = gpsDirectory.getGpsDate();

                            // search for a fallback date not based on file date
                            if (date == null) {
                                ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
                                date = directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                            }

                            if (date == null) {
                                ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                                date = directory.getDate(ExifIFD0Directory.TAG_DATETIME);
                            }

                            Waypoint waypoint = new Waypoint(fileName, date, geoLocation.getLongitude(), geoLocation.getLatitude());

                            answer = new ExtractItem(fileName, date, waypoint);
                        } else {
                            answer = new ExtractItem(fileName, date, "no GeoLocation data found.");
                        }
                    } else {
                        answer = new ExtractItem(fileName, date, "no GpsDirectory data found.");
                    }

                } catch (ImageProcessingException ex) {
                    answer = new ExtractItem(fileName, date, "failed to read photo metadata. (" + ex.getMessage() + ")");
                } catch (IOException ex) {
                    answer = new ExtractItem(fileName, date, "failed to read file. (" + ex.getMessage() + ")");
                } catch (Exception ex) {
                    answer = new ExtractItem(fileName, date, "unknown failure processing photo. (" + ex.getMessage() + ")");
                }

                submitResult(answer);

            } finally {
                processedItems.incrementAndGet();
                phaser.arriveAndDeregister();
            }
        }

        @Override
        int getPriority() {
            return 1;
        }
    }
}