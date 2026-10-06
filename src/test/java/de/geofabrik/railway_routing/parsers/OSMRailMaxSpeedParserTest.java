package de.geofabrik.railway_routing.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.ArrayEdgeIntAccess;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.storage.IntsRef;
import de.geofabrik.railway_routing.RailImportRegistry;

class OSMRailMaxSpeedParserTest {

    @Test
    void maxSpeed() {
        DecimalEncodedValue maxSpeedEnc = RailImportRegistry.createMaxSpeed();
        maxSpeedEnc.init(new EncodedValue.InitializerConfig());
        OSMRailMaxSpeedParser parser = new OSMRailMaxSpeedParser(maxSpeedEnc);
        IntsRef relFlags = new IntsRef(2);
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        ReaderWay way = new ReaderWay(29L);
        way.setTag("railway", "rail");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(0, maxSpeedEnc.getDecimal(false, edgeId, edgeIntAccess));

        way.setTag("maxspeed", "320");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(320, maxSpeedEnc.getDecimal(false, edgeId, edgeIntAccess));
        assertEquals(320, maxSpeedEnc.getDecimal(true, edgeId, edgeIntAccess));

        way.setTag("maxspeed", "125 mph");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(202, maxSpeedEnc.getDecimal(false, edgeId, edgeIntAccess));

        way.removeTag("maxspeed");
        way.setTag("maxspeed:forward", "160");
        way.setTag("maxspeed:backward", "100");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertEquals(160, maxSpeedEnc.getDecimal(false, edgeId, edgeIntAccess));
        assertEquals(100, maxSpeedEnc.getDecimal(true, edgeId, edgeIntAccess));
    }
}
