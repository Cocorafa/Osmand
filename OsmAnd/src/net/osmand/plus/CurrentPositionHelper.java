package net.osmand.plus;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.LongSparseArray;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.GeocodingUtilities;
import net.osmand.binary.GeocodingUtilities.GeocodingResult;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResource;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResourceType;
import net.osmand.router.GeneralRouter.GeneralRouterProfile;
import net.osmand.router.RoutePlannerFrontEnd;
import net.osmand.router.RoutingConfiguration;
import net.osmand.router.RoutingContext;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CurrentPositionHelper {
	
	private RouteDataObject lastFound;
	private Location lastAskedLocation = null;
	private RoutingContext ctx;
	private RoutingContext defCtx;
	private OsmandApplication app;
	private ApplicationMode am;
	private List<BinaryMapReaderResource> usedReaders = new ArrayList<>();
	private static final Log log = PlatformUtil.getLog(CurrentPositionHelper.class);

	private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
	private LongSparseArray<AtomicInteger> requestNumbersMap = new LongSparseArray<>();

	CurrentPositionHelper(OsmandApplication app) {
		this.app = app;
	}

	public boolean getRouteSegment(Location loc,
								   @Nullable ApplicationMode appMode,
								   boolean cancelPreviousSearch,
								   ResultMatcher<RouteDataObject> result) {
		return scheduleRouteSegmentFind(loc, false, true, cancelPreviousSearch, null, result, appMode);
	}

	public boolean getMultipleRouteSegmentsIds(List<Location> points,
	                                           @Nullable ApplicationMode appMode,
	                                           boolean cancelPreviousSearch,
	                                           ResultMatcher<Map<Long, Location>> result) {
		return scheduleMultipleRouteSegmentFind(points, false, true, cancelPreviousSearch, null, result, appMode);

	}

	public boolean getGeocodingResult(Location loc, ResultMatcher<GeocodingResult> result) {
		return scheduleRouteSegmentFind(loc, false, false, true, result, null, null);
	}
	
	public RouteDataObject getLastKnownRouteSegment(Location loc) {
		Location last = lastAskedLocation;
		RouteDataObject r = lastFound;
		if (loc == null || loc.getAccuracy() > 50) {
			return null;
		}
		if(last != null && last.distanceTo(loc) < 10) {
			return r;
		}
		if (r == null) {
			scheduleRouteSegmentFind(loc, true, false, true, null, null, null);
			return null;
		}
		double d = getOrthogonalDistance(r, loc);
		if (d > 15) {
			scheduleRouteSegmentFind(loc, true, false, true, null, null, null);
		}
		if (d < 70) {
			return r;
		}
		return null;
	}

	///////////////////////// PRIVATE IMPLEMENTATION //////////////////////////

	private long getRequestNumberKey(boolean storeFound, boolean allowEmptyNames) {
		return (storeFound ? 0x2 : 0x0) | (allowEmptyNames ? 0x1 : 0x0);
	}

	private boolean scheduleRouteSegmentFind(final Location loc,
											 final boolean storeFound,
											 final boolean allowEmptyNames,
											 final boolean cancelPreviousSearch,
											 @Nullable final ResultMatcher<GeocodingResult> geoCoding,
											 @Nullable final ResultMatcher<RouteDataObject> result,
											 @Nullable final ApplicationMode appMode) {
		boolean res = false;
		if (loc != null) {
			long requestKey = getRequestNumberKey(storeFound, allowEmptyNames);
			AtomicInteger requestNumber = requestNumbersMap.get(requestKey);
			if (requestNumber == null) {
				requestNumber = new AtomicInteger();
				requestNumbersMap.put(requestKey, requestNumber);
			}
			final int request = requestNumber.incrementAndGet();
			final AtomicInteger finalRequestNumber = requestNumber;
			singleThreadExecutor.submit(new Runnable() {
				@Override
				public void run() {
					processGeocoding(loc, geoCoding, storeFound, allowEmptyNames, result, appMode, request, finalRequestNumber, cancelPreviousSearch);
				}
			});
			res = true;
		}
		return res;
	}

	private boolean scheduleMultipleRouteSegmentFind(final List<Location> points,
	                                                 final boolean storeFound,
	                                                 final boolean allowEmptyNames,
	                                                 final boolean cancelPreviousSearch,
	                                                 @Nullable final ResultMatcher<GeocodingResult> geoCoding,
	                                                 @Nullable final ResultMatcher<Map<Long, Location>> result,
	                                                 @Nullable final ApplicationMode appMode) {
		boolean res = false;
		if (points.get(0) != null) {
			long requestKey = getRequestNumberKey(storeFound, allowEmptyNames);
			AtomicInteger requestNumber = requestNumbersMap.get(requestKey);
			if (requestNumber == null) {
				requestNumber = new AtomicInteger();
				requestNumbersMap.put(requestKey, requestNumber);
			}
			final int request = requestNumber.incrementAndGet();
			final AtomicInteger finalRequestNumber = requestNumber;
			singleThreadExecutor.submit(new Runnable() {
				@Override
				public void run() {
					processMultipleGeocoding(points, geoCoding, storeFound, allowEmptyNames, result, appMode, request, finalRequestNumber, cancelPreviousSearch);
				}
			});
			res = true;
		}
		return res;
	}

	private synchronized void processMultipleGeocoding(@NonNull List<Location> points,
	                                                   @Nullable ResultMatcher<GeocodingResult> geoCoding,
	                                                   boolean storeFound,
	                                                   boolean allowEmptyNames,
	                                                   @Nullable final ResultMatcher<Map<Long, Location>> result,
	                                                   @Nullable ApplicationMode appMode,
	                                                   int request,
	                                                   @NonNull AtomicInteger requestNumber,
	                                                   boolean cancelPreviousSearch) {

		if (cancelPreviousSearch && request != requestNumber.get()) {
			return;
		}

		final Map<Long, Location> gr = runUpdateInThreadBatch(points,
				geoCoding != null, allowEmptyNames, appMode);

		if(result != null) {
			app.runInUIThread(new Runnable() {
				@Override
				public void run() {
					result.publish(gr == null || gr.isEmpty() ? null : gr);
				}
			});
		}
	}


	@Nullable
	private Map<Long, Location> runUpdateInThreadBatch(List<Location> points,
	                                                                    boolean geocoding,
	                                                                    boolean allowEmptyNames,
	                                                                    @Nullable ApplicationMode appMode) {

		List<BinaryMapReaderResource> checkReaders = usedReaders;
		for (Location loc : points) {
			checkReaders = checkReaders(loc.getLatitude(), loc.getLongitude(), checkReaders);
		}

		if (appMode == null) {
			appMode = app.getSettings().getApplicationMode();
		}
		if (ctx == null || am != appMode || checkReaders != usedReaders) {
			initCtx(app, checkReaders, appMode);
			if (ctx == null) {
				return null;
			}
		}
		try {
			return new GeocodingUtilities().multipleReverseGeocodingSearch(geocoding ? defCtx : ctx, points, allowEmptyNames);
		} catch (Exception e) {
			log.error("Exception happened during runUpdateInThread", e);
			return null;
		}
	}
	
	private void initCtx(OsmandApplication app, List<BinaryMapReaderResource> checkReaders,
						 @NonNull ApplicationMode appMode) {
		am = appMode;
		String p ;
		if (am.isDerivedRoutingFrom(ApplicationMode.BICYCLE)) {
			p = GeneralRouterProfile.BICYCLE.name().toLowerCase();
		} else if (am.isDerivedRoutingFrom(ApplicationMode.PEDESTRIAN)) {
			p = GeneralRouterProfile.PEDESTRIAN.name().toLowerCase();
		} else if (am.isDerivedRoutingFrom(ApplicationMode.CAR)) {
			p = GeneralRouterProfile.CAR.name().toLowerCase();
		} else {
			p = "geocoding";
		}
		
		BinaryMapIndexReader[] rs = new BinaryMapIndexReader[checkReaders.size()];
		if (rs.length > 0) {
			int i = 0;
			for (BinaryMapReaderResource rep : checkReaders) {
				rs[i++] = rep.getReader(BinaryMapReaderResourceType.STREET_LOOKUP);
			}
			RoutingConfiguration cfg = app.getRoutingConfig().build(p, 100,
					new HashMap<String, String>());
			cfg.routeCalculationTime = System.currentTimeMillis();
			ctx = new RoutePlannerFrontEnd().buildRoutingContext(cfg, null, rs);
			RoutingConfiguration defCfg = app.getRoutingConfig().build("geocoding", 100,
					new HashMap<String, String>());
			defCtx = new RoutePlannerFrontEnd().buildRoutingContext(defCfg, null, rs);
		} else {
			ctx = null;
			defCtx = null;
		}
		usedReaders = checkReaders;
	}

	// single synchronized method
	private synchronized void processGeocoding(@NonNull Location loc,
											   @Nullable ResultMatcher<GeocodingResult> geoCoding,
											   boolean storeFound,
											   boolean allowEmptyNames,
											   @Nullable final ResultMatcher<RouteDataObject> result,
											   @Nullable ApplicationMode appMode,
											   int request,
											   @NonNull AtomicInteger requestNumber,
											   boolean cancelPreviousSearch) {

		if (cancelPreviousSearch && request != requestNumber.get()) {
			return;
		}

		final List<GeocodingResult> gr = runUpdateInThread(loc.getLatitude(), loc.getLongitude(),
				geoCoding != null, allowEmptyNames, appMode);
		if (storeFound) {
			lastAskedLocation = loc;
			lastFound = gr == null || gr.isEmpty() ? null : gr.get(0).point.getRoad();
		} else if(geoCoding != null) {
			justifyResult(gr, geoCoding);
		} else if(result != null) {
			app.runInUIThread(new Runnable() {
				@Override
				public void run() {
					result.publish(gr == null || gr.isEmpty() ? null : gr.get(0).point.getRoad());
				}
			});
		}
	}

	@Nullable
	private List<GeocodingResult> runUpdateInThread(double lat, double lon,
													boolean geocoding,
													boolean allowEmptyNames,
													@Nullable ApplicationMode appMode) {

		List<BinaryMapReaderResource> checkReaders = checkReaders(lat, lon, usedReaders);
		if (appMode == null) {
			appMode = app.getSettings().getApplicationMode();
		}
		if (ctx == null || am != appMode || checkReaders != usedReaders) {
			initCtx(app, checkReaders, appMode);
			if (ctx == null) {
				return null;
			}
		}
		try {
			return new GeocodingUtilities().reverseGeocodingSearch(geocoding ? defCtx : ctx, lat, lon, allowEmptyNames);
		} catch (Exception e) {
			log.error("Exception happened during runUpdateInThread", e);
			return null;
		}
	}
	
	private List<BinaryMapReaderResource> checkReaders(double lat, double lon,
			List<BinaryMapReaderResource> ur) {
		List<BinaryMapReaderResource> res = ur;
		for(BinaryMapReaderResource t : ur ) {
			if(t.isClosed()) {
				res = new ArrayList<>();
				break;
			}
		}
		int y31 = MapUtils.get31TileNumberY(lat);
		int x31 = MapUtils.get31TileNumberX(lon);
		for(BinaryMapReaderResource r : app.getResourceManager().getFileReaders()) {
			if(!r.isClosed() && r.getShallowReader().containsRouteData(x31, y31, x31, y31, 15)) {
				if(!res.contains(r)) {
					res = new ArrayList<>(res);
					res.add(r);
				}
			}
		}
		return res;
	}

	private void justifyResult(List<GeocodingResult> res, final ResultMatcher<GeocodingResult> result) {
		List<GeocodingResult> complete = new ArrayList<>();
		double minBuildingDistance = 0;
		if (res != null) {
			for (GeocodingResult r : res) {
				BinaryMapIndexReader foundRepo = null;
				List<BinaryMapReaderResource> rts  = usedReaders;
				for (BinaryMapReaderResource rt : rts) {
					if(rt.isClosed()) {
						continue;
					}
					BinaryMapIndexReader reader = rt.getReader(BinaryMapReaderResourceType.STREET_LOOKUP);
					for (RouteRegion rb : reader.getRoutingIndexes()) {
						if (r.regionFP == rb.getFilePointer() && r.regionLen == rb.getLength()) {
							foundRepo = reader;
							break;
						}
					}
				}
				if (result.isCancelled()) {
					break;
				} else if (foundRepo != null) {
					List<GeocodingResult> justified = null;
					try {
						justified = new GeocodingUtilities().justifyReverseGeocodingSearch(r, foundRepo,
								minBuildingDistance, result);
					} catch (IOException e) {
						log.error("Exception happened during reverse geocoding", e);
					}
					if (justified != null && !justified.isEmpty()) {
						double md = justified.get(0).getDistance();
						if (minBuildingDistance == 0) {
							minBuildingDistance = md;
						} else {
							minBuildingDistance = Math.min(md, minBuildingDistance);
						}
						complete.addAll(justified);
					}
				} else {
					complete.add(r);
				}
			}
		}

		if (result.isCancelled()) {
			app.runInUIThread(new Runnable() {
				public void run() {
					result.publish(null);
				}
			});
			return;
		}
		Collections.sort(complete, GeocodingUtilities.DISTANCE_COMPARATOR);
//		for(GeocodingResult rt : complete) {
//			System.out.println(rt.toString());
//		}
		final GeocodingResult rts = complete.size() > 0 ? complete.get(0) : new GeocodingResult();
		app.runInUIThread(new Runnable() {
			public void run() {
				result.publish(rts);
			}
		});
	}

	public static double getOrthogonalDistance(RouteDataObject r, Location loc){
		double d = 1000;
		if (r.getPointsLength() > 0) {
			double pLt = MapUtils.get31LatitudeY(r.getPoint31YTile(0));
			double pLn = MapUtils.get31LongitudeX(r.getPoint31XTile(0));
			for (int i = 1; i < r.getPointsLength(); i++) {
				double lt = MapUtils.get31LatitudeY(r.getPoint31YTile(i));
				double ln = MapUtils.get31LongitudeX(r.getPoint31XTile(i));
				double od = MapUtils.getOrthogonalDistance(loc.getLatitude(), loc.getLongitude(), pLt, pLn, lt, ln);
				if (od < d) {
					d = od;
				}
				pLt = lt;
				pLn = ln;
			}
		}
		return d;
	}
}
