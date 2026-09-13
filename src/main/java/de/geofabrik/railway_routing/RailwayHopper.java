package de.geofabrik.railway_routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.Router;
import com.graphhopper.routing.Router.Solver;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;

import de.geofabrik.railway_routing.reader.OSMRailwayReader;
import de.geofabrik.railway_routing.reader.RailwayOSMParsers;

public class RailwayHopper extends GraphHopper {

    /** Upper bound on the number of retry routing calls per waypoint, to cap worst-case latency. */
    private int maxSnapAttempts = 8;
    /** Upper bound on edges explored while flood-filling a candidate's local component. Components
     *  that grow past this are assumed to be "big enough" (likely the real network) and are left
     *  alone rather than excluded. */
    private int componentFloodFillCap = 2000;

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

    /**
     * GraphHopper snaps each waypoint to its single nearest edge. If that edge belongs to a
     * component that isn't connected to the rest of the requested route (e.g. a branch line
     * not joined to the main network in OSM), routing fails even though a well-connected edge
     * exists nearby. On such a failure, retry the request substituting nearby alternate snap
     * candidates for one waypoint at a time. Unlike a simple "try the next-nearest edge" loop,
     * each failing candidate's entire local component is flood-filled and excluded before
     * searching for the next one, so a single failed attempt skips past small dead-end clusters
     * (sidings, yards, short disused spurs) instead of retrying edge-by-edge within the same one.
     */
    @Override
    public GHResponse route(GHRequest request) {
        GHResponse response = super.route(request);
        if (request.getPoints().size() < 2 || !hasSnapOrConnectivityError(response)) {
            return response;
        }
        GHResponse retried = routeWithAlternateSnaps(request);
        return retried != null ? retried : response;
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

    private GHResponse routeWithAlternateSnaps(GHRequest request) {
        Router router = createRouter();
        Solver solver = router.createSolver(request);
        solver.init();
        EdgeFilter snapFilter = solver.createSnapFilter();
        LocationIndex locationIndex = getLocationIndex();
        BaseGraph baseGraph = getBaseGraph();

        List<GHPoint> points = request.getPoints();
        int attempts = 0;
        for (int pointIndex = 0; pointIndex < points.size() && attempts < maxSnapAttempts; pointIndex++) {
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
                GHResponse retryResponse = super.route(copyRequest(request, substituted, pointIndex));
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
     * Copies a GHRequest, using the given (one waypoint substituted) points. The point_hint
     * for the substituted waypoint, if any, is dropped: it described the original raw
     * coordinate and could otherwise fight the alternate candidate's own natural snap.
     */
    private GHRequest copyRequest(GHRequest original, List<GHPoint> points, int substitutedPointIndex) {
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
            if (substitutedPointIndex < adjustedHints.size()) {
                adjustedHints.set(substitutedPointIndex, "");
            }
            copy.setPointHints(adjustedHints);
        }
        return copy;
    }
}
