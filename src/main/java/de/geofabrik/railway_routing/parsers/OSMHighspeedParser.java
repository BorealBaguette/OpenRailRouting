package de.geofabrik.railway_routing.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;

/**
 * Read value of highspeed=*. Only `yes` counts as a high-speed line.
 */
public class OSMHighspeedParser implements TagParser {

    private final BooleanEncodedValue highspeedEnc;

    public OSMHighspeedParser(BooleanEncodedValue enc) {
        this.highspeedEnc = enc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        highspeedEnc.setBool(false, edgeId, edgeIntAccess, way.hasTag("highspeed", "yes"));
    }
}
