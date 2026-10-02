package de.geofabrik.railway_routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
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
    private static final double SOFT_WEIGHT_TOLERANCE = 1e-3;
    private static final int SOFT_RESOLUTION_PASSES = 2;

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
     * 2. Soft waypoints are then resolved in order (see resolveSoftWaypoints): one the natural
     * route already passes close by is moved onto that route, so it never causes a detour or a
     * backtrack; otherwise the cheapest nearby track to route through is chosen, which lets the
     * preferred_direction penalty pick the correct track of a double-track line.
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
     * Resolves soft waypoints in route order, in up to SOFT_RESOLUTION_PASSES passes (later passes
     * see every neighbour already placed). For each one:
     * <p>
     * - Intermediate waypoint the direct route previous->next already passes within (distance to
     *   the nearest running track + softWaypointRadius), without reversing at a waypoint: moved
     *   onto that route. This keeps imprecise stop coordinates (e.g. imported from MOTIS) from
     *   dragging the route off its line or making it reverse back towards the point.
     * <p>
     * - Otherwise: the cheapest of that on-route point (if close enough) and the distinct tracks
     *   near its snap. Wrong-direction running and dead ends cost weight, and so do reversals at
     *   waypoints, which GraphHopper itself doesn't charge for (see windowWeight). The current
     *   placement is kept unless another is strictly cheaper.
     * <p>
     * Every route here also includes the points beyond the neighbours, which fix the direction
     * the train arrives at the previous point and leaves the next one. Without them the previous
     * point would count as a fresh departure that may leave in either direction.
     */
    private GHRequest resolveSoftWaypoints(List<GHPoint> rawPoints, GHRequest request, List<WaypointMode> modes) {
        List<GHPoint> anchors = request.getPoints();
        if (anchors.size() < 2 || softWaypointRadius <= 0 || !modes.contains(WaypointMode.SOFT)) {
            return request;
        }
        Router router = createRouter();
        Solver solver = router.createSolver(request);
        solver.init();
        SoftContext context = new SoftContext(request, solver.createSnapFilter(), getLocationIndex(),
                reversalWeight(request.getProfile()));

        List<GHPoint> resolved = new ArrayList<>(anchors);
        Set<Integer> changed = new HashSet<>();
        // a point only needs another look once something within two places of it has moved
        Set<Integer> stale = new HashSet<>();
        for (int i = 0; i < resolved.size(); i++) {
            if (modes.get(i) == WaypointMode.SOFT) {
                stale.add(i);
            }
        }
        for (int pass = 0; pass < SOFT_RESOLUTION_PASSES && !stale.isEmpty(); pass++) {
            Set<Integer> current = stale;
            stale = new HashSet<>();
            for (int i = 0; i < resolved.size(); i++) {
                if (!current.contains(i)) {
                    continue;
                }
                stale.remove(i);
                GHPoint placement = resolveSoftWaypoint(i, rawPoints.get(i), anchors.get(i), resolved, context);
                if (!samePoint(placement, resolved.get(i))) {
                    resolved.set(i, placement);
                    changed.add(i);
                    for (int k = Math.max(0, i - 2); k <= Math.min(resolved.size() - 1, i + 2); k++) {
                        if (k != i && modes.get(k) == WaypointMode.SOFT) {
                            stale.add(k);
                        }
                    }
                }
            }
        }
        return changed.isEmpty() ? request : copyRequest(request, resolved, changed);
    }

    private class SoftContext {
        final GHRequest request;
        final EdgeFilter snapFilter;
        final EdgeFilter runningTrackFilter;
        final LocationIndex locationIndex;
        final double reversalWeight;

        SoftContext(GHRequest request, EdgeFilter snapFilter, LocationIndex locationIndex, double reversalWeight) {
            this.request = request;
            this.snapFilter = snapFilter;
            this.runningTrackFilter = runningTrackFilter(snapFilter);
            this.locationIndex = locationIndex;
            this.reversalWeight = reversalWeight;
        }
    }

    /** Returns the placement for soft waypoint i; resolved.get(i) if nothing beats it. */
    private GHPoint resolveSoftWaypoint(int i, GHPoint raw, GHPoint anchor, List<GHPoint> resolved, SoftContext ctx) {
        GHPoint current = resolved.get(i);
        Snap anchorSnap = ctx.locationIndex.findClosest(anchor.getLat(), anchor.getLon(), ctx.snapFilter);
        if (!anchorSnap.isValid()) {
            return current;
        }
        int last = resolved.size() - 1;
        GHPoint before = i > 1 ? resolved.get(i - 2) : null;
        GHPoint prev = i > 0 ? resolved.get(i - 1) : null;
        GHPoint next = i < last ? resolved.get(i + 1) : null;
        GHPoint after = i + 2 <= last ? resolved.get(i + 2) : null;

        double tolerance = softTolerance(raw, anchorSnap, ctx);
        List<GHPoint> candidates = new ArrayList<>();
        candidates.add(current);
        if (prev != null && next != null) {
            GHResponse direct = super.route(scoringRequest(ctx.request, withContext(before, Arrays.asList(prev, next), after)));
            if (!direct.hasErrors()) {
                ResponsePath path = direct.getBest();
                int prevIndex = before != null ? 1 : 0;
                List<Integer> indices = path.getWaypointIndices();
                GHPoint onRoute = closestPointOnPath(raw,
                        path.getPoints().copy(indices.get(prevIndex), indices.get(prevIndex + 1) + 1));
                if (onRoute != null && distance(raw, onRoute) <= tolerance) {
                    if (reversalsAtWaypoints(path) == 0) {
                        return onRoute;
                    }
                    candidates.add(onRoute);
                }
            }
        }
        for (GHPoint point : nearbyRunningTracks(raw, tolerance, ctx)) {
            if (candidates.stream().noneMatch(c -> distance(c, point) < DUPLICATE_SNAP_DISTANCE)) {
                candidates.add(point);
            }
        }
        if (candidates.size() < 2) {
            return current;
        }

        GHPoint best = current;
        double bestWeight = windowWeight(ctx, withContext(before, core(prev, current, next), after));
        for (GHPoint candidate : candidates.subList(1, candidates.size())) {
            double weight = windowWeight(ctx, withContext(before, core(prev, candidate, next), after));
            if (weight < bestWeight * (1 - SOFT_WEIGHT_TOLERANCE)) {
                bestWeight = weight;
                best = candidate;
            }
        }
        return best;
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
     * the point, nearest first, one per spot, at most MAX_SOFT_TRACK_CANDIDATES. Centred on the
     * point itself rather than on its snap: a station node often sits between the lines it
     * serves, and from the snap on one of them, its own sidings come before the other line.
     * <p>
     * A track is split into many edges, and an edge whose closest point is merely its end, where
     * a neighbouring edge comes closer, is the same track further along - not an alternative.
     * Left in, it would let an endpoint slide along its own track towards the rest of the trip.
     */
    private List<GHPoint> nearbyRunningTracks(GHPoint point, double maxDistance, SoftContext ctx) {
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
            found.add(new TrackPoint(closest, distance(point, closest), edge.getBaseNode(), edge.getAdjNode(), endNode));
        });
        found.removeIf(candidate -> candidate.endNode >= 0 && found.stream().anyMatch(other -> other != candidate
                && (other.baseNode == candidate.endNode || other.adjNode == candidate.endNode)
                && other.distance < candidate.distance));
        found.sort(Comparator.comparingDouble(candidate -> candidate.distance));
        List<GHPoint> distinct = new ArrayList<>();
        for (TrackPoint candidate : found) {
            if (distinct.size() >= MAX_SOFT_TRACK_CANDIDATES) {
                break;
            }
            if (distinct.stream().noneMatch(c -> distance(c, candidate.point) < DUPLICATE_SNAP_DISTANCE)) {
                distinct.add(candidate.point);
            }
        }
        return distinct;
    }

    private static class TrackPoint {
        final GHPoint point;
        final double distance;
        final int baseNode;
        final int adjNode;
        /** The edge end node the closest point sits on, or -1 if it's inside the edge. */
        final int endNode;

        TrackPoint(GHPoint point, double distance, int baseNode, int adjNode, int endNode) {
            this.point = point;
            this.distance = distance;
            this.baseNode = baseNode;
            this.adjNode = adjNode;
            this.endNode = endNode;
        }
    }

    /**
     * Route weight through the points, plus reversalWeight per reversal at an intermediate point.
     * GraphHopper routes each leg independently, so turning back at a waypoint is free there; left
     * uncharged, a placement that forces the train to reverse at a stop would look as cheap as one
     * it simply runs through.
     */
    private double windowWeight(SoftContext ctx, List<GHPoint> points) {
        GHResponse response = super.route(scoringRequest(ctx.request, points));
        if (response.hasErrors()) {
            return Double.POSITIVE_INFINITY;
        }
        int reversals = reversalsAtWaypoints(response.getBest());
        return response.getBest().getRouteWeight() + (reversals == 0 ? 0 : reversals * ctx.reversalWeight);
    }

    /** Intermediate waypoints where the route leaves back the way it came (geometry A, waypoint, A). */
    private static int reversalsAtWaypoints(ResponsePath path) {
        PointList points = path.getPoints();
        List<Integer> indices = path.getWaypointIndices();
        int reversals = 0;
        for (int k = 1; k < indices.size() - 1; k++) {
            int at = indices.get(k);
            if (at > 0 && at + 1 < points.size()
                    && points.getLat(at - 1) == points.getLat(at + 1) && points.getLon(at - 1) == points.getLon(at + 1)) {
                reversals++;
            }
        }
        return reversals;
    }

    private double reversalWeight(String profileName) {
        Profile profile = getProfile(profileName);
        if (profile == null || !profile.hasTurnCosts()) {
            return 0;
        }
        int uTurnCosts = profile.getTurnCostsConfig().getUTurnCosts();
        // negative means u-turns are forbidden outright
        return uTurnCosts < 0 ? Double.POSITIVE_INFINITY : uTurnCosts;
    }

    private static List<GHPoint> core(GHPoint prev, GHPoint via, GHPoint next) {
        List<GHPoint> points = new ArrayList<>(3);
        if (prev != null) {
            points.add(prev);
        }
        points.add(via);
        if (next != null) {
            points.add(next);
        }
        return points;
    }

    private static List<GHPoint> withContext(GHPoint before, List<GHPoint> core, GHPoint after) {
        List<GHPoint> points = new ArrayList<>(core.size() + 2);
        if (before != null) {
            points.add(before);
        }
        points.addAll(core);
        if (after != null) {
            points.add(after);
        }
        return points;
    }

    private GHRequest scoringRequest(GHRequest template, List<GHPoint> points) {
        GHRequest request = new GHRequest(points);
        request.setProfile(template.getProfile());
        request.setCustomModel(template.getCustomModel());
        request.setSnapPreventions(template.getSnapPreventions());
        request.getHints().putAll(template.getHints());
        request.putHint(Parameters.Routing.INSTRUCTIONS, false);
        request.putHint(Parameters.Routing.CALC_POINTS, true);
        // unsimplified geometry: closest points found on it lie exactly on a track, and reversals
        // at waypoints show up as exact A, waypoint, A repeats
        request.putHint(Parameters.Routing.WAY_POINT_MAX_DISTANCE, 0);
        return request;
    }

    private static double distance(GHPoint a, GHPoint b) {
        return DistanceCalcEarth.DIST_EARTH.calcDist(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    private static boolean samePoint(GHPoint a, GHPoint b) {
        return a.getLat() == b.getLat() && a.getLon() == b.getLon();
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
