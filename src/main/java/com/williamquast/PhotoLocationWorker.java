package com.williamquast;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.tools.FileUtil;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
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

    private static final int THREAD_COUNT = 20; // count of thread to handle processing. mainly disk IO blocking, not CPU
    private static final long TESTING_DELAY = 0; // ms delay used to simulate slow computers for manual UI testing

    private static final Logger log = LoggerFactory.getLogger(PhotoLocationWorker.class);

    private Thread supervisorThread = new Thread();
    private ExecutorService executorService =
            new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT,
            0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>());
    private boolean cancelled = false;

    private File sourceDir;
    private FinishListener finishListener;
    private ProgressListener progressListener;

    private Phaser phaser = new Phaser(); // used to keep track of incomplete work in the executorService
    private AtomicInteger foundItems = new AtomicInteger();
    private AtomicInteger processedItems = new AtomicInteger();

    private AtomicBoolean uiReady = new AtomicBoolean(true); // used to prevent flooding the UI thread with updates
    private List<ExtractItem> itemsBuffer = new ArrayList<>(); // items waiting to be handled over to the UI thread

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
        log.info("Begin processAndWait. sourceDir=" + sourceDir.getAbsolutePath());
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
            log.info("Supervisor thread was interrupted or cancelled. Abort.", ex);
            finish(new Result("Cancelled. (" + ex.getMessage() + ")", true));
        } catch (Exception ex) {
            log.error("Supervisor thread had an exception while waiting.", ex);
            finish(new Result("Unknown error. (" + ex.getMessage() + ")"));
        } finally {
            // work is done, release threads
            executorService.shutdownNow();
        }
        log.info("End processAndWait");
    }

    private synchronized void finish(final Result result) {
        final List<ExtractItem> itemsDelivery = itemsBuffer;
        itemsBuffer = new ArrayList<>();

        Platform.runLater(() -> {
            // deliver any last items to the ui thread, regardless of uiReady
            progressListener.onProgress(foundItems.get(), processedItems.get(), itemsDelivery);

            finishListener.onFinished(result);
        });

        log.info("finish. found=" + foundItems.get() + " processed=" + processedItems.get());
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

    /**
     * Used by the tasks below to give a priority in the ExecutorService.
     * For UI status purposes, we want Search tasks to complete before we do Processing tasks.
     */
    private abstract class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {

        abstract int getPriority();

        @Override
        public int compareTo(PriorityRunnable o) {
            return this.getPriority() - o.getPriority();
        }
    }

    /**
     * Task to determine if a file is a directory and recurse, or a regular file that can be processed.
     */
    protected class SearchDirectoryRunnable extends PriorityRunnable {

        private File dir;

        public SearchDirectoryRunnable(File dir) {
            this.dir = dir;
        }

        @Override
        public void run() {
            log.debug("SearchDirectoryRunnable. dir=" + dir.getPath());
            try {
                if (!dir.isDirectory()) {
                    submitResult(new ExtractItem(dir.getPath(), null, "Source is not a directory."));
                }
                if (!dir.canRead()) {
                    submitResult(new ExtractItem(dir.getPath(), null, "Source directory is not readable. (permissions)"));
                }

                testingDelay();
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            phaser.register(); // deregister in finally of SearchDirectoryRunnable
                            executorService.execute(new SearchDirectoryRunnable(file));
                        } else {
                            if (file.canRead()) {
                                foundItems.incrementAndGet();
                                submitStatus();
                                phaser.register(); // deregister in finally of ProcessPhotoFileRunnable
                                executorService.execute(new ProcessPhotoFileRunnable(file));
                            } else {
                                submitResult(new ExtractItem(file.getName(), null, "Source file is not readable. (permissions)"));
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.error("SearchDirectoryRunnable failed.", ex);
                submitResult(new ExtractItem(dir.getPath(), null, "Failed to search directory. (" + ex.getMessage() + ")" ));
            } finally {
                phaser.arriveAndDeregister();
            }
        }

        @Override
        int getPriority() {
            return 0;
        }
    }

    /**
     * Task to process a single file for GeoLocation and send the ExtractItem to the UI queue
     */
    protected class ProcessPhotoFileRunnable extends PriorityRunnable {

        private File file;

        public ProcessPhotoFileRunnable(File file) {
            this.file = file;
        }

        @Override
        public void run() {
            log.debug("Begin ProcessPhotoFileRunnable. file=" + file.getName());
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

                    FileType fileType = detectFileType(file);
                    if (fileType != FileType.Unknown) {
                        Metadata metadata = ImageMetadataReader.readMetadata(file);

                        // log all readable meatadata for debug
                        if (log.isDebugEnabled()) {
                            metadata.getDirectories().forEach(directory -> log.debug(file.getName() + " : " + directory.toString()));
                        }

                        Collection<GpsDirectory> gpsDirectories = metadata.getDirectoriesOfType(GpsDirectory.class);
                        Iterator<GpsDirectory> itr = gpsDirectories.iterator();
                        if (itr.hasNext()) {
                            GpsDirectory gpsDirectory = itr.next();
                            if (gpsDirectory != null && gpsDirectory.getGeoLocation() != null) {
                                GeoLocation geoLocation = gpsDirectory.getGeoLocation();

                                if (!geoLocation.isZero()) {

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

                                    // successful ExtractItem
                                    answer = new ExtractItem(fileName, date, waypoint);
                                } else {
                                    answer = new ExtractItem(fileName, date, "GeoLocation data is invalid or corrupt. (lat/lng 0,0)");
                                }
                            } else {
                                answer = new ExtractItem(fileName, date, "No GeoLocation data found.");
                            }
                        } else {
                            answer = new ExtractItem(fileName, date, "No GpsDirectory data found.");
                        }
                    } else {
                        answer = new ExtractItem(fileName, date, "Unknown media file type.");
                    }

                } catch (ImageProcessingException ex) {
                    log.error("ProcessPhotoFileRunnable failed reading photo metadata.", ex);
                    answer = new ExtractItem(fileName, date, "Failed to read photo metadata. (" + ex.getClass().getSimpleName() + " : " + ex.getMessage() + ")");
                } catch (IOException ex) {
                    log.error("ProcessPhotoFileRunnable failed reading file.", ex);
                    answer = new ExtractItem(fileName, date, "Failed to read file. (" + ex.getClass().getSimpleName() + " : " + ex.getMessage() + ")");
                } catch (Exception ex) {
                    log.error("ProcessPhotoFileRunnable unknown failure.", ex);
                    answer = new ExtractItem(fileName, date, "Unknown failure while processing file. (" + ex.getClass().getSimpleName() + " : " + ex.getMessage() + ")");
                }

                submitResult(answer);

            } finally {
                processedItems.incrementAndGet();
                phaser.arriveAndDeregister();
            }

        }

        /**
         * Returns the FileType of the file using safe resource handling.
         */
        private FileType detectFileType(File file) throws IOException {
            BufferedInputStream fileIn = null;
            try {
                fileIn = new BufferedInputStream(new FileInputStream(file));
                return FileTypeDetector.detectFileType(fileIn);
            } finally {
                try {
                    if (fileIn != null) {
                        fileIn.close();
                    }
                } catch (Exception ex) { /* ignore */ }
            }
        }

        @Override
        int getPriority() {
            return 1;
        }
    }
}