package de.geofabrik.railway_routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.Router;
import com.graphhopper.routing.Router.Solver;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;

import de.geofabrik.railway_routing.ev.RailwayClass;
import de.geofabrik.railway_routing.ev.RailwayService;
import de.geofabrik.railway_routing.reader.OSMRailwayReader;
import de.geofabrik.railway_routing.reader.RailwayOSMParsers;

public class RailwayHopper extends GraphHopper {

    /**
     * Which railway_class a profile should prefer to snap onto, when reasonably close by. Light
     * rail is how many tram systems are tagged, such as Bergen's Bybanen.
     */
    private static final Map<String, Set<RailwayClass>> PREFERRED_CLASSES_BY_PROFILE = Map.of(
            "train", Set.of(RailwayClass.RAIL),
            "metro", Set.of(RailwayClass.SUBWAY),
            "tram", Set.of(RailwayClass.TRAM, RailwayClass.LIGHT_RAIL)
    );

    /** Upper bound on the number of retry routing calls per waypoint, to cap worst-case latency. */
    private int maxSnapAttempts = 8;
    /** Upper bound on edges explored while flood-filling a candidate's local component. Components
     *  that grow past this are assumed to be "big enough" (likely the real network) and are left
     *  alone rather than excluded. */
    private int componentFloodFillCap = 2000;
    /** How much farther (in meters) than the true nearest edge we're willing to snap in order to
     *  land on the profile's preferred railway_class instead - e.g. a tram stop a little farther
     *  than an adjacent train platform. Bounded, not absolute: beyond this, use the nearest edge
     *  regardless of category. */
    private double snapPreferenceRadius = 100.0;
    /** Slack (in meters, on top of the waypoint's own distance to the track) within which a soft
     *  waypoint counts as passed by a route, and within which alternative tracks are considered. */
    private double softWaypointRadius = 50.0;

    /** Request hint: comma-separated "hard"/"soft" per point, e.g. waypoint_modes=soft,hard,soft.
     *  A single hint because GraphHopper drops repeated unknown query parameters. */
    public static final String WAYPOINT_MODES_HINT = "waypoint_modes";
    private static final int MAX_SOFT_TRACK_CANDIDATES = 6;
    private static final double DUPLICATE_SNAP_DISTANCE = 2.0;

    enum WaypointMode {
        /** Snap exactly to the nearest edge; never nudged, moved or substituted. */
        HARD,
        /** An approximate location (e.g. a stop coordinate): the route only has to pass close by. */
        SOFT
    }

    public RailwayHopper() {
        super();
        setImportRegistry(new RailImportRegistry());
        setOsmParsersSupplier(RailwayOSMParsers::new);
        setOsmReaderSupplier((baseGraph, osmParsers, config) -> new OSMRailwayReader(baseGraph, osmParsers, config));
    }

    public void setMaxSnapAttempts(int maxSnapAttempts) {
        this.maxSnapAttempts = maxSnapAttempts;
    }

    public void setComponentFloodFillCap(int componentFloodFillCap) {
        this.componentFloodFillCap = componentFloodFillCap;
    }

    public void setSnapPreferenceRadius(double snapPreferenceRadius) {
        this.snapPreferenceRadius = snapPreferenceRadius;
    }

    public void setSoftWaypointRadius(double softWaypointRadius) {
        this.softWaypointRadius = softWaypointRadius;
    }

    /**
     * Adjustments on top of GraphHopper's stock routing. Each waypoint is HARD or SOFT (see
     * WAYPOINT_MODES_HINT, default SOFT). HARD waypoints get plain nearest-edge snapping and are
     * exempt from everything below; SOFT waypoints go through all of it.
     * <p>
     * 1. Snapping is purely nearest-edge and blind to railway_class, so e.g. a "tram" query for a
     * point right in front of a train station can snap onto the station's platform tracks instead
     * of the tram line a few meters away. Before routing, each waypoint is nudged onto the
     * profile's preferred railway_class if a matching edge exists within snapPreferenceRadius of
     * the true nearest edge - a soft preference, not a hard requirement. Candidates whose local
     * component is small (a dead-end siding/spur/stub) are skipped in favour of the next-nearest
     * one, since snapping onto a dead end forces a there-and-back detour instead of a real route.
     * <p>
     * 2. Soft waypoints are then placed together (see resolveSoftWaypoints): each on one of a few
     * nearby tracks, choosing the combination that makes the cheapest route overall, so an
     * imprecise or wrong-track point never causes a detour or a reversal when a direct route
     * exists.
     * <p>
     * 3. If the (possibly nudged) request still fails because a waypoint's edge belongs to a
     * component disconnected from the rest of the route (e.g. a branch line not joined to the
     * main network in OSM), retry substituting nearby alternate snap candidates for one waypoint
     * at a time. Each failing candidate's entire local component is flood-filled and excluded
     * before searching for the next one, so a single failed attempt skips past small dead-end
     * clusters (sidings, yards, short disused spurs) instead of retrying edge-by-edge within it.
     */
    @Override
    public GHResponse route(GHRequest request) {
        List<WaypointMode> modes;
        try {
            modes = parseWaypointModes(request);
        } catch (IllegalArgumentException e) {
            return new GHResponse().addError(e);
        }
        GHRequest preferredRequest = preferCategorySnaps(request, modes);
        GHRequest resolvedRequest = resolveSoftWaypoints(request.getPoints(), preferredRequest, modes);
        GHResponse response = super.route(resolvedRequest);
        if (resolvedRequest.getPoints().size() < 2 || !hasSnapOrConnectivityError(response)) {
            return response;
        }
        GHResponse retried = routeWithAlternateSnaps(resolvedRequest, modes);
        return retried != null ? retried : response;
    }

    static List<WaypointMode> parseWaypointModes(GHRequest request) {
        int count = request.getPoints().size();
        String value = request.getHints().getString(WAYPOINT_MODES_HINT, "");
        if (value.isEmpty()) {
            return Collections.nCopies(count, WaypointMode.SOFT);
        }
        String[] parts = value.split(",", -1);
        if (parts.length != count) {
            throw new IllegalArgumentException(WAYPOINT_MODES_HINT + " has " + parts.length
                    + " entries but the request has " + count + " points");
        }
        List<WaypointMode> modes = new ArrayList<>(count);
        for (String part : parts) {
            switch (part.trim().toLowerCase(Locale.ROOT)) {
                case "hard":
                    modes.add(WaypointMode.HARD);
                    break;
                case "soft":
                case "":
                    modes.add(WaypointMode.SOFT);
                    break;
                default:
                    throw new IllegalArgumentException(WAYPOINT_MODES_HINT + " entries must be 'hard' or 'soft', got '"
                            + part + "'");
            }
        }
        return modes;
    }

    /**
     * Places all soft waypoints together. Each soft waypoint gets a few candidate positions: its
     * current (category-nudged) snap, the closest point on the route between its neighbours, and
     * the distinct running tracks near the pin. Each hard waypoint has only its own position. The
     * chosen positions are the cheapest chain of legs through one candidate per waypoint, found
     * with a Viterbi pass over the candidates.
     * <p>
     * Choosing them together matters where tracks carry no railway:preferred_direction (Paris
     * Metro, Bybanen): both tracks of a line look equally good from any one stop, so deciding stop
     * by stop leaves mixed choices whose track changes need a detour or a reversal. A chain keeps
     * consecutive stops on tracks that connect directly. Imprecise stops (e.g. MOTIS coordinates)
     * likewise end up on whichever nearby track the route runs along anyway, and a soft endpoint
     * on whichever of several lines at a station is cheapest to reach.
     */
    private GHRequest resolveSoftWaypoints(List<GHPoint> rawPoints, GHRequest request, List<WaypointMode> modes) {
        List<GHPoint> anchors = request.getPoints();
        if (anchors.size() < 2 || softWaypointRadius <= 0 || !modes.contains(WaypointMode.SOFT)) {
            return request;
        }
        Router router = createRouter();
        Solver solver = router.createSolver(request);
        solver.init();
        SoftContext ctx = new SoftContext(request, solver.createSnapFilter(), getLocationIndex());

        int count = anchors.size();
        List<List<GHPoint>> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(modes.get(i) == WaypointMode.SOFT
                    ? softCandidates(i, rawPoints.get(i), anchors, ctx)
                    : Collections.singletonList(anchors.get(i)));
        }

        // cost[c]: cheapest chain from the first waypoint to candidate c of the current one
        double[] cost = new double[candidates.get(0).size()];
        int[][] cameFrom = new int[count][];
        for (int i = 1; i < count; i++) {
            List<GHPoint> from = candidates.get(i - 1);
            List<GHPoint> to = candidates.get(i);
            double[] next = new double[to.size()];
            int[] choice = new int[to.size()];
            for (int b = 0; b < to.size(); b++) {
                next[b] = Double.POSITIVE_INFINITY;
                for (int a = 0; a < from.size(); a++) {
                    if (cost[a] == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    double total = cost[a] + legWeight(ctx, from.get(a), to.get(b));
                    // strictly cheaper only: ties keep the earlier candidate, i.e. the current snap
                    if (total < next[b]) {
                        next[b] = total;
                        choice[b] = a;
                    }
                }
            }
            cost = next;
            cameFrom[i] = choice;
        }
        int pick = 0;
        for (int c = 1; c < cost.length; c++) {
            if (cost[c] < cost[pick]) {
                pick = c;
            }
        }
        if (cost[pick] == Double.POSITIVE_INFINITY) {
            // nothing connects; leave it to the normal routing and its alternate-snap retries
            return request;
        }

        List<GHPoint> resolved = new ArrayList<>(anchors);
        Set<Integer> changed = new HashSet<>();
        for (int i = count - 1; i >= 0; i--) {
            if (pick != 0) {
                resolved.set(i, candidates.get(i).get(pick));
                changed.add(i);
            }
            if (i > 0) {
                pick = cameFrom[i][pick];
            }
        }
        return changed.isEmpty() ? request : copyRequest(request, resolved, changed);
    }

    private class SoftContext {
        final GHRequest request;
        final EdgeFilter snapFilter;
        final EdgeFilter runningTrackFilter;
        /** The profile's own railway classes, ranked first among candidate tracks; never null. */
        final EdgeFilter preferredClass;
        final LocationIndex locationIndex;
        final Map<List<GHPoint>, Double> legWeights = new HashMap<>();

        SoftContext(GHRequest request, EdgeFilter snapFilter, LocationIndex locationIndex) {
            this.request = request;
            this.snapFilter = snapFilter;
            this.runningTrackFilter = runningTrackFilter(snapFilter);
            Set<RailwayClass> preferred = PREFERRED_CLASSES_BY_PROFILE.get(request.getProfile());
            if (preferred == null || !getEncodingManager().hasEncodedValue(RailwayClass.KEY)) {
                this.preferredClass = edge -> false;
            } else {
                EnumEncodedValue<RailwayClass> railwayClassEnc =
                        getEncodingManager().getEnumEncodedValue(RailwayClass.KEY, RailwayClass.class);
                this.preferredClass = edge -> preferred.contains(edge.get(railwayClassEnc));
            }
            this.locationIndex = locationIndex;
        }
    }

    /** Candidate positions for soft waypoint i; the first is its current snap, kept on ties. */
    private List<GHPoint> softCandidates(int i, GHPoint raw, List<GHPoint> anchors, SoftContext ctx) {
        GHPoint anchor = anchors.get(i);
        List<GHPoint> candidates = new ArrayList<>();
        candidates.add(anchor);
        Snap anchorSnap = ctx.locationIndex.findClosest(anchor.getLat(), anchor.getLon(), ctx.snapFilter);
        if (!anchorSnap.isValid()) {
            return candidates;
        }
        double tolerance = softTolerance(raw, anchorSnap, ctx);
        List<TrackPoint> found = new ArrayList<>();
        if (i > 0 && i < anchors.size() - 1) {
            // where the route between its neighbours already passes, if that's close enough
            GHResponse direct = super.route(scoringRequest(ctx.request, Arrays.asList(anchors.get(i - 1), anchors.get(i + 1)), true));
            if (!direct.hasErrors()) {
                GHPoint onRoute = closestPointOnPath(raw, direct.getBest().getPoints());
                if (onRoute != null && distance(raw, onRoute) <= tolerance) {
                    Snap onRouteSnap = ctx.locationIndex.findClosest(onRoute.getLat(), onRoute.getLon(), ctx.snapFilter);
                    if (onRouteSnap.isValid()) {
                        found.add(TrackPoint.of(onRoute, onRouteSnap));
                    }
                }
            }
        }
        found.addAll(nearbyRunningTracks(raw, tolerance, ctx));
        List<TrackPoint> kept = new ArrayList<>();
        kept.add(TrackPoint.of(anchorSnap.getSnappedPoint(), anchorSnap));
        for (TrackPoint candidate : found) {
            if (kept.stream().noneMatch(k -> k.sameSpot(candidate))) {
                kept.add(candidate);
                candidates.add(candidate.point);
            }
        }
        return candidates;
    }

    /** Distance to the nearest running track (not a yard or siding: a point beside a yard is still
     *  "at" the line the yard belongs to), plus softWaypointRadius. */
    private double softTolerance(GHPoint raw, Snap anchorSnap, SoftContext ctx) {
        double trackDistance = distance(raw, anchorSnap.getSnappedPoint());
        Snap runningSnap = ctx.locationIndex.findClosest(raw.getLat(), raw.getLon(), ctx.runningTrackFilter);
        if (runningSnap.isValid()) {
            trackDistance = Math.max(trackDistance, runningSnap.getQueryDistance());
        }
        return trackDistance + softWaypointRadius;
    }

    private EdgeFilter runningTrackFilter(EdgeFilter snapFilter) {
        if (!getEncodingManager().hasEncodedValue(RailwayService.KEY)) {
            return snapFilter;
        }
        EnumEncodedValue<RailwayService> railwayServiceEnc =
                getEncodingManager().getEnumEncodedValue(RailwayService.KEY, RailwayService.class);
        return edge -> snapFilter.accept(edge) && !isDeadEndService(edge.get(railwayServiceEnc));
    }

    /**
     * The closest point of every running track (not a siding, yard or spur) within maxDistance of
     * the point, the profile's own railway classes first, then nearest first, one per spot, at most
     * MAX_SOFT_TRACK_CANDIDATES. Centred on the
     * point itself rather than on its snap: a station node often sits between the lines it
     * serves, and from the snap on one of them, its own sidings come before the other line.
     * <p>
     * A track is split into many edges, and an edge whose closest point is merely its end, where
     * a neighbouring edge comes closer, is the same track further along - not an alternative.
     * Left in, it would let an endpoint slide along its own track towards the rest of the trip.
     */
    private List<TrackPoint> nearbyRunningTracks(GHPoint point, double maxDistance, SoftContext ctx) {
        double latDelta = maxDistance / DistanceCalcEarth.METERS_PER_DEGREE;
        double lonDelta = latDelta / Math.cos(Math.toRadians(point.getLat()));
        BBox box = new BBox(point.getLon() - lonDelta, point.getLon() + lonDelta,
                point.getLat() - latDelta, point.getLat() + latDelta);
        BaseGraph graph = getBaseGraph();
        Set<Integer> seen = new HashSet<>();
        List<TrackPoint> found = new ArrayList<>();
        ctx.locationIndex.query(box, edgeId -> {
            if (!seen.add(edgeId)) {
                return;
            }
            EdgeIteratorState edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            if (!ctx.runningTrackFilter.accept(edge)) {
                return;
            }
            PointList geometry = edge.fetchWayGeometry(FetchMode.ALL);
            GHPoint closest = closestPointOnPath(point, geometry);
            if (closest == null || distance(point, closest) > maxDistance) {
                return;
            }
            int endNode = -1;
            if (closest.getLat() == geometry.getLat(0) && closest.getLon() == geometry.getLon(0)) {
                endNode = edge.getBaseNode();
            } else if (closest.getLat() == geometry.getLat(geometry.size() - 1)
                    && closest.getLon() == geometry.getLon(geometry.size() - 1)) {
                endNode = edge.getAdjNode();
            }
            found.add(new TrackPoint(closest, distance(point, closest), edgeId, edge.getBaseNode(), edge.getAdjNode(),
                    endNode, ctx.preferredClass.accept(edge)));
        });
        found.removeIf(candidate -> candidate.endNode >= 0 && found.stream().anyMatch(other -> other != candidate
                && (other.baseNode == candidate.endNode || other.adjNode == candidate.endNode)
                && other.distance < candidate.distance));
        // the profile's own kind of track first: at a big interchange the nearest tracks often
        // belong to other lines (Paris Étoile: RER A and Métro 2 come before Métro 6)
        found.sort(Comparator.comparing((TrackPoint candidate) -> !candidate.preferred)
                .thenComparingDouble(candidate -> candidate.distance));
        List<TrackPoint> distinct = new ArrayList<>();
        for (TrackPoint candidate : found) {
            if (distinct.size() >= MAX_SOFT_TRACK_CANDIDATES) {
                break;
            }
            if (distinct.stream().noneMatch(c -> c.sameSpot(candidate))) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private static class TrackPoint {
        final GHPoint point;
        final double distance;
        final int edge;
        final int baseNode;
        final int adjNode;
        /** The edge end node the closest point sits on, or -1 if it's inside the edge. */
        final int endNode;
        final boolean preferred;

        TrackPoint(GHPoint point, double distance, int edge, int baseNode, int adjNode, int endNode, boolean preferred) {
            this.point = point;
            this.distance = distance;
            this.edge = edge;
            this.baseNode = baseNode;
            this.adjNode = adjNode;
            this.endNode = endNode;
            this.preferred = preferred;
        }

        static TrackPoint of(GHPoint point, Snap snap) {
            EdgeIteratorState edge = snap.getClosestEdge();
            return new TrackPoint(point, 0, edge.getEdge(), edge.getBaseNode(), edge.getAdjNode(), -1, false);
        }

        /**
         * The same spot on the same track: close together on one edge or on two edges that meet.
         * Distance alone isn't enough - lines stacked at different depths (Paris Opéra: Métro 3, 7
         * and 8 within 2 m of each other) are separate tracks and must all stay candidates.
         */
        boolean sameSpot(TrackPoint other) {
            boolean connected = edge == other.edge || baseNode == other.baseNode || baseNode == other.adjNode
                    || adjNode == other.baseNode || adjNode == other.adjNode;
            return connected && distance(point, other.point) < DUPLICATE_SNAP_DISTANCE;
        }
    }

    private double legWeight(SoftContext ctx, GHPoint from, GHPoint to) {
        return ctx.legWeights.computeIfAbsent(Arrays.asList(from, to), leg -> {
            GHResponse response = super.route(scoringRequest(ctx.request, leg, false));
            return response.hasErrors() ? Double.POSITIVE_INFINITY : response.getBest().getRouteWeight();
        });
    }

    private GHRequest scoringRequest(GHRequest template, List<GHPoint> points, boolean withGeometry) {
        GHRequest request = new GHRequest(points);
        request.setProfile(template.getProfile());
        request.setCustomModel(template.getCustomModel());
        request.setSnapPreventions(template.getSnapPreventions());
        request.getHints().putAll(template.getHints());
        request.putHint(Parameters.Routing.INSTRUCTIONS, false);
        request.putHint(Parameters.Routing.CALC_POINTS, withGeometry);
        // unsimplified geometry, so the closest point found on it lies exactly on a track
        request.putHint(Parameters.Routing.WAY_POINT_MAX_DISTANCE, 0);
        return request;
    }

    private static double distance(GHPoint a, GHPoint b) {
        return DistanceCalcEarth.DIST_EARTH.calcDist(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    private static GHPoint closestPointOnPath(GHPoint point, PointList path) {
        DistanceCalc calc = DistanceCalcEarth.DIST_EARTH;
        GHPoint best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < path.size(); i++) {
            GHPoint candidate = new GHPoint(path.getLat(i), path.getLon(i));
            if (i + 1 < path.size() && calc.validEdgeDistance(point.getLat(), point.getLon(),
                    path.getLat(i), path.getLon(i), path.getLat(i + 1), path.getLon(i + 1))) {
                GHPoint crossing = calc.calcCrossingPointToEdge(point.getLat(), point.getLon(),
                        path.getLat(i), path.getLon(i), path.getLat(i + 1), path.getLon(i + 1));
                if (calc.calcDist(point.getLat(), point.getLon(), crossing.getLat(), crossing.getLon())
                        < calc.calcDist(point.getLat(), point.getLon(), candidate.getLat(), candidate.getLon())) {
                    candidate = crossing;
                }
            }
            double distance = calc.calcDist(point.getLat(), point.getLon(), candidate.getLat(), candidate.getLon());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private GHRequest preferCategorySnaps(GHRequest request, List<WaypointMode> modes) {
        Set<RailwayClass> preferred = PREFERRED_CLASSES_BY_PROFILE.get(request.getProfile());
        if (preferred == null || request.getPoints().isEmpty()) {
            return request;
        }
        EnumEncodedValue<RailwayClass> railwayClassEnc;
        EnumEncodedValue<RailwayService> railwayServiceEnc;
        try {
            railwayClassEnc = getEncodingManager().getEnumEncodedValue(RailwayClass.KEY, RailwayClass.class);
            railwayServiceEnc = getEncodingManager().getEnumEncodedValue(RailwayService.KEY, RailwayService.class);
        } catch (IllegalArgumentException e) {
            return request;
        }

        Router router = createRouter();
        Solver solver = router.createSolver(request);
        solver.init();
        EdgeFilter snapFilter = solver.createSnapFilter();
        LocationIndex locationIndex = getLocationIndex();
        BaseGraph baseGraph = getBaseGraph();

        List<GHPoint> points = request.getPoints();
        List<GHPoint> adjustedPoints = new ArrayList<>(points);
        Set<Integer> adjustedIndices = new HashSet<>();

        for (int i = 0; i < points.size(); i++) {
            if (modes.get(i) == WaypointMode.HARD) {
                continue;
            }
            GHPoint point = points.get(i);
            Snap nearest = locationIndex.findClosest(point.getLat(), point.getLon(), snapFilter);
            if (!nearest.isValid() || preferred.contains(nearest.getClosestEdge().get(railwayClassEnc))) {
                continue;
            }
            Snap preferredSnap = findWellConnectedPreferredSnap(point, preferred, railwayClassEnc, railwayServiceEnc,
                    snapFilter, locationIndex, baseGraph, nearest.getQueryDistance() + snapPreferenceRadius);
            if (preferredSnap != null) {
                adjustedPoints.set(i, preferredSnap.getSnappedPoint());
                adjustedIndices.add(i);
            }
        }

        return adjustedIndices.isEmpty() ? request : copyRequest(request, adjustedPoints, adjustedIndices);
    }

    /**
     * Searches for the nearest preferred-class edge within maxDistance, skipping two kinds of bad
     * candidates in favour of the next-nearest one:
     * <p>
     * - railway_service SIDING/YARD/SPUR edges. These are dead-end stub tracks (stabling sidings,
     * yard throats, etc.) that are nevertheless part of the same well-connected component as the
     * running line they branch off - so the flood-fill check below doesn't catch them - but
     * snapping onto one still forces a there-and-back detour to reach and leave the stub.
     * <p>
     * - candidates whose local component is small (a genuine dead-end/disconnected stub not
     * caught by the service-tag check, e.g. an untagged short spur).
     * <p>
     * Bounded by maxSnapAttempts to cap worst-case cost. Returns null if no suitable candidate is
     * found within maxDistance.
     */
    private Snap findWellConnectedPreferredSnap(GHPoint point, Set<RailwayClass> preferred,
            EnumEncodedValue<RailwayClass> railwayClassEnc, EnumEncodedValue<RailwayService> railwayServiceEnc,
            EdgeFilter snapFilter, LocationIndex locationIndex, BaseGraph baseGraph, double maxDistance) {
        Set<Integer> excludedEdges = new HashSet<>();
        for (int attempt = 0; attempt < maxSnapAttempts; attempt++) {
            final Set<Integer> excludedSoFar = excludedEdges;
            EdgeFilter preferredFilter = edge -> !excludedSoFar.contains(edge.getEdge()) && snapFilter.accept(edge)
                    && preferred.contains(edge.get(railwayClassEnc))
                    && !isDeadEndService(edge.get(railwayServiceEnc));
            Snap candidate = locationIndex.findClosest(point.getLat(), point.getLon(), preferredFilter);
            if (!candidate.isValid() || candidate.getQueryDistance() > maxDistance) {
                return null;
            }
            Set<Integer> component = floodFillSmallComponent(baseGraph, snapFilter,
                    candidate.getClosestEdge().getBaseNode(), componentFloodFillCap);
            if (component == null) {
                return candidate;
            }
            excludedEdges.addAll(component);
        }
        return null;
    }

    private boolean isDeadEndService(RailwayService service) {
        return service == RailwayService.SIDING || service == RailwayService.YARD || service == RailwayService.SPUR;
    }

    private boolean hasSnapOrConnectivityError(GHResponse response) {
        if (!response.hasErrors()) {
            return false;
        }
        for (Throwable error : response.getErrors()) {
            if (error instanceof ConnectionNotFoundException || error instanceof PointNotFoundException) {
                return true;
            }
        }
        return false;
    }

    private GHResponse routeWithAlternateSnaps(GHRequest request, List<WaypointMode> modes) {
        Router router = createRouter();
        Solver solver = router.createSolver(request);
        solver.init();
        EdgeFilter snapFilter = solver.createSnapFilter();
        LocationIndex locationIndex = getLocationIndex();
        BaseGraph baseGraph = getBaseGraph();

        List<GHPoint> points = request.getPoints();
        int attempts = 0;
        for (int pointIndex = 0; pointIndex < points.size() && attempts < maxSnapAttempts; pointIndex++) {
            if (modes.get(pointIndex) == WaypointMode.HARD) {
                continue;
            }
            GHPoint originalPoint = points.get(pointIndex);
            Set<Integer> excludedEdges = new HashSet<>();
            // the edge the initial (already-failed) attempt snapped to; don't try it again
            Snap firstSnap = locationIndex.findClosest(originalPoint.getLat(), originalPoint.getLon(), snapFilter);
            if (firstSnap.isValid()) {
                excludedEdges.add(firstSnap.getClosestEdge().getEdge());
            }

            while (attempts < maxSnapAttempts) {
                final Set<Integer> excludedSoFar = excludedEdges;
                EdgeFilter candidateFilter = edge -> !excludedSoFar.contains(edge.getEdge()) && snapFilter.accept(edge);
                Snap candidate = locationIndex.findClosest(originalPoint.getLat(), originalPoint.getLon(), candidateFilter);
                if (!candidate.isValid()) {
                    break;
                }
                attempts++;

                List<GHPoint> substituted = new ArrayList<>(points);
                substituted.set(pointIndex, candidate.getSnappedPoint());
                GHResponse retryResponse = super.route(copyRequest(request, substituted, Collections.singleton(pointIndex)));
                if (!retryResponse.hasErrors()) {
                    return retryResponse;
                }

                // This candidate didn't connect either. Exclude its whole local component (if
                // small enough) so the next search jumps past it instead of retrying nearby
                // edges of the same dead end one at a time.
                Set<Integer> component = floodFillSmallComponent(baseGraph, snapFilter,
                        candidate.getClosestEdge().getBaseNode(), componentFloodFillCap);
                if (component != null) {
                    excludedEdges.addAll(component);
                } else {
                    excludedEdges.add(candidate.getClosestEdge().getEdge());
                }
            }
        }
        return null;
    }

    /**
     * Flood-fills the connected component reachable from startNode (following edges accepted by
     * filter), stopping as soon as more than cap edges have been found. Returns the full edge-id
     * set if the component is small (bounded by cap), or null if it grew past the cap - in that
     * case it's assumed to be a legitimately large network and is left alone.
     */
    private Set<Integer> floodFillSmallComponent(BaseGraph baseGraph, EdgeFilter filter, int startNode, int cap) {
        Set<Integer> componentEdges = new HashSet<>();
        Set<Integer> visitedNodes = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startNode);
        visitedNodes.add(startNode);
        EdgeExplorer explorer = baseGraph.createEdgeExplorer(filter);

        while (!queue.isEmpty()) {
            if (componentEdges.size() > cap) {
                return null;
            }
            int node = queue.poll();
            EdgeIterator iter = explorer.setBaseNode(node);
            while (iter.next()) {
                componentEdges.add(iter.getEdge());
                int adjNode = iter.getAdjNode();
                if (visitedNodes.add(adjNode)) {
                    queue.add(adjNode);
                }
            }
        }
        return componentEdges;
    }

    /**
     * Copies a GHRequest, using the given (some waypoints substituted) points. The point_hint for
     * each substituted waypoint, if any, is dropped: it described the original raw coordinate and
     * could otherwise fight the substituted candidate's own natural snap.
     */
    private GHRequest copyRequest(GHRequest original, List<GHPoint> points, Set<Integer> substitutedIndices) {
        GHRequest copy = new GHRequest(points);
        copy.setProfile(original.getProfile());
        copy.setAlgorithm(original.getAlgorithm());
        copy.setLocale(original.getLocale());
        copy.setCustomModel(original.getCustomModel());
        copy.setCurbsides(original.getCurbsides());
        copy.setSnapPreventions(original.getSnapPreventions());
        copy.setPathDetails(original.getPathDetails());
        copy.setHeadings(original.getHeadings());
        copy.getHints().putAll(original.getHints());

        List<String> pointHints = original.getPointHints();
        if (!pointHints.isEmpty()) {
            List<String> adjustedHints = new ArrayList<>(pointHints);
            for (int index : substitutedIndices) {
                if (index < adjustedHints.size()) {
                    adjustedHints.set(index, "");
                }
            }
            copy.setPointHints(adjustedHints);
        }
        return copy;
    }
}
