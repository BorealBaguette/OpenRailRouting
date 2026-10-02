package de.geofabrik.railway_routing;

import static de.geofabrik.railway_routing.RailwayHopper.WaypointMode.HARD;
import static de.geofabrik.railway_routing.RailwayHopper.WaypointMode.SOFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.graphhopper.GHRequest;

class WaypointModesTest {

    private static GHRequest request(int points, String modes) {
        GHRequest request = new GHRequest();
        for (int i = 0; i < points; i++) {
            request.addPoint(new com.graphhopper.util.shapes.GHPoint(49 + i * 0.01, 8.4));
        }
        if (modes != null) {
            request.putHint(RailwayHopper.WAYPOINT_MODES_HINT, modes);
        }
        return request;
    }

    @Test
    void defaultsToSoft() {
        assertEquals(Arrays.asList(SOFT, SOFT, SOFT), RailwayHopper.parseWaypointModes(request(3, null)));
    }

    @Test
    void parsesPerPointModes() {
        assertEquals(Arrays.asList(HARD, SOFT, SOFT, HARD),
                RailwayHopper.parseWaypointModes(request(4, "hard,soft, ,HARD")));
    }

    @Test
    void rejectsCountMismatch() {
        assertThrows(IllegalArgumentException.class, () -> RailwayHopper.parseWaypointModes(request(3, "hard,soft")));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> RailwayHopper.parseWaypointModes(request(2, "hard,medium")));
    }
}
