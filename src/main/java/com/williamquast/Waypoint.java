package com.williamquast;

import java.util.Date;

public class Waypoint {

    String name;
    Date timestamp;
    double x;
    double y;

    public Waypoint(String name, Date timestamp, double x, double y) {
        this.name = name;
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
    }
}
