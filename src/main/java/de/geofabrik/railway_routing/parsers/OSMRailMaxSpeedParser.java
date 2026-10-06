package de.geofabrik.railway_routing.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.util.parsers.AbstractAverageSpeedParser;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;

/**
 * Read maxspeed=* (and maxspeed:forward/backward) into max_speed. Unlike GraphHopper's
 * OSMMaxSpeedParser, which caps values at 150 km/h for roads, this keeps high-speed line values.
 * Untagged ways keep 0, like gauge.
 */
public class OSMRailMaxSpeedParser implements TagParser {

    private final DecimalEncodedValue maxSpeedEnc;

    public OSMRailMaxSpeedParser(DecimalEncodedValue maxSpeedEnc) {
        this.maxSpeedEnc = maxSpeedEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        maxSpeedEnc.setDecimal(false, edgeId, edgeIntAccess, toStorable(AbstractAverageSpeedParser.getMaxSpeed(way, false)));
        maxSpeedEnc.setDecimal(true, edgeId, edgeIntAccess, toStorable(AbstractAverageSpeedParser.getMaxSpeed(way, true)));
    }

    private double toStorable(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0) {
            return 0;
        }
        return Math.min(speed, maxSpeedEnc.getMaxStorableDecimal());
    }
}
