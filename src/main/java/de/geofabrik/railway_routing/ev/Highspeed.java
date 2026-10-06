package de.geofabrik.railway_routing.ev;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;

/**
 * Stores if a track is tagged `highspeed=yes` in OSM.
 */
public class Highspeed {

    public static String KEY = "highspeed";

    public static BooleanEncodedValue create() {
        return new SimpleBooleanEncodedValue(KEY, false);
    }
}
