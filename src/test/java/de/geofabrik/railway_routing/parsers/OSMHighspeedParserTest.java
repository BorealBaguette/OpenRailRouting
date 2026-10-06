package de.geofabrik.railway_routing.parsers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.ArrayEdgeIntAccess;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.storage.IntsRef;
import de.geofabrik.railway_routing.ev.Highspeed;

class OSMHighspeedParserTest {

    @Test
    void highspeed() {
        BooleanEncodedValue highspeedEnc = Highspeed.create();
        highspeedEnc.init(new EncodedValue.InitializerConfig());
        OSMHighspeedParser parser = new OSMHighspeedParser(highspeedEnc);
        IntsRef relFlags = new IntsRef(2);
        ReaderWay way = new ReaderWay(29L);
        way.setTag("railway", "rail");
        EdgeIntAccess edgeIntAccess = new ArrayEdgeIntAccess(1);
        int edgeId = 0;
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertFalse(highspeedEnc.getBool(false, edgeId, edgeIntAccess));
        way.setTag("highspeed", "yes");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertTrue(highspeedEnc.getBool(false, edgeId, edgeIntAccess));
        way.setTag("highspeed", "no");
        parser.handleWayTags(edgeId, edgeIntAccess, way, relFlags);
        assertFalse(highspeedEnc.getBool(false, edgeId, edgeIntAccess));
    }
}
