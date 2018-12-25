package com.williamquast;

import java.util.Date;
import java.util.Objects;

public class ExtractItem implements Comparable {

    String filename;
    Date timestamp;
    boolean success;
    String failureReason;
    Waypoint waypoint;

    public ExtractItem(String filename, Date timestamp, Waypoint waypoint) {
        this.success = true;
        this.filename = filename;
        this.timestamp = timestamp;
        this.waypoint = waypoint;
    }

    public ExtractItem(String filename, Date timestamp, String failureReason) {
        this.success = false;
        this.filename = filename;
        this.timestamp = timestamp;
        this.failureReason = failureReason;
    }

    public String getFilename() {
        return filename;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Waypoint getWaypoint() {
        return waypoint;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExtractItem result = (ExtractItem) o;
        return Objects.equals(filename, result.filename);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename);
    }

    @Override
    public int compareTo(Object o) {
        return filename.compareTo(((ExtractItem) o).filename);
    }

    @Override
    public String toString() {
        return "ExtractItem{" +
                "filename='" + filename + '\'' +
                ", timestamp=" + timestamp +
                ", success=" + success +
                ", failureReason='" + failureReason + '\'' +
                ", waypoint=" + waypoint +
                '}';
    }
}
