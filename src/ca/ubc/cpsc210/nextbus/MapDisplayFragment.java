package ca.ubc.cpsc210.nextbus;

import java.util.ArrayList;

import org.osmdroid.DefaultResourceProxyImpl;
import org.osmdroid.ResourceProxy;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBoxE6;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.ItemizedIconOverlay.OnItemGestureListener;
import org.osmdroid.views.overlay.OverlayItem;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import ca.ubc.cpsc210.exception.TranslinkException;
import ca.ubc.cpsc210.nextbus.model.Bus;
import ca.ubc.cpsc210.nextbus.model.BusStop;
import ca.ubc.cpsc210.nextbus.translink.ITranslinkService;
import ca.ubc.cpsc210.nextbus.translink.TranslinkService;
import ca.ubc.cpsc210.nextbus.util.LatLon;
import ca.ubc.cpsc210.nextbus.util.TextOverlay;

/**
 * Fragment holding the map in the UI.
 */
public class MapDisplayFragment extends Fragment {

	/**
	 * Log tag for LogCat messages
	 */
	private final static String LOG_TAG = "MapDisplayFragment";

	/**
	 * Location of Nelson & Granville, downtown Vancouver
	 */
	private final static GeoPoint NELSON_GRANVILLE 
							= new GeoPoint(49.279285, -123.123007);

	/**
	 * Overlay for bus markers.
	 */
	private ItemizedIconOverlay<OverlayItem> busLocnOverlay;

	/**
	 * Overlay for bus stop location
	 */
	private ItemizedIconOverlay<OverlayItem> busStopLocationOverlay;
	
	/**
	 * Overlay for user location
	 */
	private ItemizedIconOverlay<OverlayItem> userLocationOverlay;
	
	/**
	 * Overlay for legend
	 */
	private TextOverlay legendOverlay;

	
	/**
	 * View that shows the map
	 */
	private MapView mapView;

	/**
	 * Selected bus stop
	 */
	private BusStop selectedStop;

	/**
	 * Wraps Translink web service
	 */
	private ITranslinkService tlService;

	/**
	 * Map controller for zooming in/out, centering
	 */
	private IMapController mapController;

	/**
	 * True if and only if map should zoom to fit displayed route.
	 */
	private boolean zoomToFit;

	/**
	 * Bus selected by user
	 */
	private OverlayItem selectedBus;
	
	// user location provided via Android framework APIs in android.location
	// Google Location API, part of Google Play Services, is an alternative
	// which more powerful and automated according to developer.android.com
	
	// LocationManager directs interacts with sensors to get location data
	// LocationListener listens to push updates from LocationManager
	// Location stores location related data
	// SERVICE_PROVIDER specifies which sensor is used: usually GPS or Network
	// note: NETWORK_PROVIDER doesn't seem to work on the emulator
	/**
	 * Android LocationManager for accessing system location service
	 */
	private LocationManager mapLocationManager;
	
	/**
	 * MapDisplayLocationListener for listening to updates from LocaitonManager
	 */
	private MapDisplayLocationListener mapLocationListener;
	
	/**
	 * current location received by MapDisplayLocationListener
	 */
	private Location currentLocation;


	/**
	 * Set up Translink service
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		Log.d(LOG_TAG, "onActivityCreated");

		setHasOptionsMenu(true);

		tlService = new TranslinkService(getActivity());

		Log.d(LOG_TAG, "Stop number for mapping: " + (selectedStop == null ? "not set" : selectedStop.getStopNum()));
		
		// instantiate LocationManager and LocationListener
		mapLocationManager = (LocationManager) this.getActivity().getSystemService(Context.LOCATION_SERVICE);
		mapLocationListener = new MapDisplayLocationListener(this);
		// print log in LogCat
		Log.d(LOG_TAG, "LocationManager and LocationListener are created");
	}

	/**
	 * Set up map view with overlays for buses and selected bus stop.
	 */
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		Log.d(LOG_TAG, "onCreateView");

		if (mapView == null) {
			mapView = new MapView(getActivity(), null);

			mapView.setTileSource(TileSourceFactory.MAPNIK);
			mapView.setClickable(true);
			mapView.setBuiltInZoomControls(true);

			// set default view for map (this seems to be important even when
			// it gets overwritten by plotBuses)
			mapController = mapView.getController();
			mapController.setZoom(mapView.getMaxZoomLevel() - 4);
			mapController.setCenter(NELSON_GRANVILLE);


			busLocnOverlay = createBusLocnOverlay();
			busStopLocationOverlay = createBusStopLocnOverlay();
			// create overlay for user location
			userLocationOverlay = createUserLocnOverlay(); 
			legendOverlay = createTextOverlay();

			// Order matters: overlays added later are displayed on top of
			// overlays added earlier.
			mapView.getOverlays().add(busStopLocationOverlay);
			mapView.getOverlays().add(busLocnOverlay);
			// add overlay for user location to mapView
			mapView.getOverlays().add(userLocationOverlay); 
			mapView.getOverlays().add(legendOverlay);
		}

		return mapView;
	}
	

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		inflater.inflate(R.menu.fragment_map_refresh, menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.map_refresh) {
			update(false);
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * When view is destroyed, remove map view from its parent so that it can be
	 * added again when view is re-created.
	 */
	@Override
	public void onDestroyView() {
		Log.d(LOG_TAG, "onDestroyView");

		((ViewGroup) mapView.getParent()).removeView(mapView);

		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		Log.d(LOG_TAG, "onDestroy");

		super.onDestroy();
	}

	/**
	 * Update bus locations.
	 */
	@Override
	public void onResume() {
		Log.d(LOG_TAG, "onResume");

		// start listening for location changes when user resumes
		startLocationService();

		update(true);
		
		super.onResume();
	}
	
	/**
	 * Start location service
	 */
	private void startLocationService() {
		boolean gpsLocationOn;
		boolean networkLocationOn;
		
		// check that system location providers are available
		gpsLocationOn = mapLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
		networkLocationOn = mapLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		// print log in LogCat
		Log.d(LOG_TAG, "GPS location is enabled: " + gpsLocationOn);
		Log.d(LOG_TAG, "Network location is enabled: " + networkLocationOn);
		
		// request location updates from network provider if it is available
		if (networkLocationOn) {
			mapLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 25, mapLocationListener);
			Log.d(LOG_TAG, "LocationListener starts listening to network location provider");
			if (currentLocation == null) {
				currentLocation = mapLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
				Log.d(LOG_TAG, "No new location yet, use last known location from network");
			}
		}
		
		// request location updates from GPS provider if it is available
		if (gpsLocationOn) {
			mapLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 25, mapLocationListener);
			Log.d(LOG_TAG, "LocationListener starts listening to GPS location provider");
			if (currentLocation == null) {
				currentLocation = mapLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				Log.d(LOG_TAG, "No new location yet, use last known location from GPS");
			}
		}
		
		// create dialog if no provider is available
		if (!gpsLocationOn && !networkLocationOn) {
			AlertDialog dialog = createSimpleDialog("Warning", "Unable to find a location service provider, "
					+"please check location access in settings.");
			dialog.show();
			currentLocation = null;
			Log.d(LOG_TAG, "Unable to find an enabled location provider");
		}
	}
	
	/* (non-Javadoc)
	 * @see android.support.v4.app.Fragment#onPause()
	 * stop location service on pause
	 */
	@Override
	public void onPause() {
		Log.d(LOG_TAG, "onPause");

		// stop the LocationListener when user pauses
		stopLocationService();

		super.onPause();
	}
	
	/**
	 * stop location service
	 */
	private void stopLocationService() {
		// stop listening for location updates from LocationManager
		mapLocationManager.removeUpdates(mapLocationListener);
		Log.d(LOG_TAG, "LocationListener stops listening to LocationManager");
	}
	
	/**
	 * called by MapDisplayLocationListener to update map when location updates
	 */
	void updateLocation() {
		Log.d(LOG_TAG, "onUpdateLocation");
		plotUser();
		mapView.invalidate();
	}

	/**
	 * called by MapDisplayLocationListener to set currentLocation
	 * @param currentLocation  current location received from the listener
	 */
	public void setCurrentLocation(Location currentLocation) {
		this.currentLocation = currentLocation;
	}
	
	/**
	 * Set selected bus stop
	 * @param selectedStop  the selected stop
	 */
	public void setBusStop(BusStop selectedStop) {
		this.selectedStop = selectedStop;
	}

	/**
	 * Update bus location info for selected stop,
	 * zoomToFit status and repaint.
	 * 
	 * @Param zoomToFit  true if map must be zoomed to fit (when new bus stop has been selected)
	 */
	void update(boolean zoomToFit) {
		Log.d(LOG_TAG, "update - zoomToFit: " + zoomToFit);
		
		this.zoomToFit = zoomToFit;

		if(selectedStop != null) {
			new GetBusInfo().execute(selectedStop);
			selectedBus = null;
		}

		mapView.invalidate();
	}


	/**
	 * Create the overlay for bus markers.
	 */
	private ItemizedIconOverlay<OverlayItem> createBusLocnOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());

		OnItemGestureListener<OverlayItem> gestureListener = new OnItemGestureListener<OverlayItem>() {
			/**
			 * Display bus information in dialog box when user taps
			 * bus.
			 * 
			 * @param index  index of item tapped
			 * @param oi the OverlayItem that was tapped
			 * @return true to indicate that tap event has been handled
			 */
			@Override
			public boolean onItemSingleTapUp(int index, OverlayItem oi) {
				// If a bus was selected before, set the bus icon to null = default blue icon
				if (selectedBus != null) {
					selectedBus.setMarker(null);
				}
				// set this current overlay item to the selected bus icon (yellow icon)
				oi.setMarker(getResources().getDrawable(R.drawable.selected_bus));
				mapView.invalidate();
				selectedBus = oi;
				
				// Display a pop-up dialogue of the selected bus' destination and time the 
				//location of the bus was last updated
				AlertDialog dlg = createSimpleDialog(oi.getTitle(), oi.getSnippet());
				dlg.show();

				return true;
			}

			@Override
			public boolean onItemLongPress(int index, OverlayItem oi) {
				// do nothing
				return false;
			}
		};

		return new ItemizedIconOverlay<OverlayItem>(
				new ArrayList<OverlayItem>(), 
				    getResources().getDrawable(R.drawable.bus), 
				        gestureListener, rp);
	}

	/**
	 * Create the overlay for user location marker.
	 */
	private ItemizedIconOverlay<OverlayItem> createUserLocnOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());
		
		OnItemGestureListener<OverlayItem> gestureListener = new OnItemGestureListener<OverlayItem>() {
			/**
			 * Display the user location description in dialog box when user taps
			 * the blue pin.
			 * 
			 * @param index  index of item tapped
			 * @param oi the OverlayItem that was tapped
			 * @return true to indicate that tap event has been handled
			 */
			@Override
			public boolean onItemSingleTapUp(int index, OverlayItem oi) {
				// Display a pop-up dialogue of the user's current location 
				AlertDialog dlg = createSimpleDialog(oi.getTitle(), oi.getSnippet());
				dlg.show();
				return true;
			}

			@Override
			public boolean onItemLongPress(int index, OverlayItem oi) {
				// do nothing
				return false;
			}
		};
		
		return new ItemizedIconOverlay<OverlayItem>(
				new ArrayList<OverlayItem>(), 
				        getResources().getDrawable(R.drawable.map_pin_blue), 
				        gestureListener, rp);
	}
	
	/**
	 * Create the overlay for bus stop marker.
	 */
	private ItemizedIconOverlay<OverlayItem> createBusStopLocnOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());

		OnItemGestureListener<OverlayItem> gestureListener = new OnItemGestureListener<OverlayItem>() {
			/**
			 * Display bus stop description in dialog box when user taps
			 * stop.
			 * 
			 * @param index  index of item tapped
			 * @param oi the OverlayItem that was tapped
			 * @return true to indicate that tap event has been handled
			 */
			@Override
			public boolean onItemSingleTapUp(int index, OverlayItem oi) {
				AlertDialog dlg = createSimpleDialog(oi.getTitle(), oi.getSnippet());
				dlg.show();

				return true;
			}

			@Override
			public boolean onItemLongPress(int index, OverlayItem oi) {
				// do nothing
				return false;
			}
		};

		return new ItemizedIconOverlay<OverlayItem>(
				new ArrayList<OverlayItem>(), 
				        getResources().getDrawable(R.drawable.stop), 
				        gestureListener, rp);
	}

	private TextOverlay createTextOverlay() {
		ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());
		Resources res = getResources();
		String legend = res.getString(R.string.legend);
		
		return new TextOverlay(rp, legend);
	}
	
	/**
	 * Plot user location
	 */
	private void plotUser() {
		// make sure the user is not added more than once
		userLocationOverlay.removeAllItems();
		
		// plot the user's current location if it is known
		if (currentLocation != null) {
			GeoPoint point = new GeoPoint(currentLocation.getLatitude(), currentLocation.getLongitude());
			OverlayItem overlayItem = new OverlayItem("you are here", "current location", point);
			userLocationOverlay.addItem(overlayItem);
		}
	}
	
	/**
	 * Plot bus stop
	 */
	private void plotBusStop() {
		LatLon latlon = selectedStop.getLatLon();
		GeoPoint point = new GeoPoint(latlon.getLatitude(),
				latlon.getLongitude());
		OverlayItem overlayItem = new OverlayItem(Integer.valueOf(selectedStop.getStopNum()).toString(), 
				selectedStop.getLocationDesc(), point);
		busStopLocationOverlay.removeAllItems(); // make sure not adding
											     // bus stop more than once
		busStopLocationOverlay.addItem(overlayItem);
	}

	/**
	 * Plot buses onto bus location overlay
	 * 
	 * @param zoomToFit  determines if map should be zoomed to bounds of plotted buses
	 */
	private void plotBuses(boolean zoomToFit) {
		busLocnOverlay.removeAllItems();
		// sometimes Translink returns unknown location for a bus
		// presumably GPS is not working, we represent this as LatLon object
		LatLon unknown = new LatLon(0.0, 0.0);
		
		// we need to store the four edges of the viewable map area
		// this will allow us to zoom to fit if needed
		double north = selectedStop.getLatLon().getLatitude();
		double south = north;
		double west = selectedStop.getLatLon().getLongitude();
		double east = west;
		
		for (Bus bus : selectedStop.getBuses()) {
			// get location of bus as LatLon object
			LatLon latlon = bus.getLatLon();
			
			if(!latlon.equals(unknown)) {
				// create new GeoPoint object using latlon
				GeoPoint point = new GeoPoint(latlon.getLatitude(), latlon.getLongitude());
				OverlayItem overlayItem = new OverlayItem(bus.getDestination(), 
						bus.getDescription(), point);
				busLocnOverlay.addItem(overlayItem);
				
				// the following code updates the edges of the viewable map
				// so that all buses currently service the stop will be visible
				if (Double.compare(north, latlon.getLatitude()) < 0) {
					north = latlon.getLatitude();
				}
				if (Double.compare(south, latlon.getLatitude()) > 0) {
					south = latlon.getLatitude();
				}
				if (Double.compare(west, latlon.getLongitude()) > 0) {
					west = latlon.getLongitude();
				}
				if (Double.compare(east, latlon.getLongitude()) < 0) {
					east = latlon.getLongitude();
				}
			}
		}
		// if zoom to fit is needed, create a bounding box based 
		// on the four edges determined above and zoom to the bounding box
		if (zoomToFit) {
			BoundingBoxE6 boundingBox = new BoundingBoxE6(north, east, south, west);
			mapView.zoomToBoundingBox(boundingBox);
		}
		
	}


	/**
	 * Helper to create simple alert dialog to display message
	 * @param title  the title to be displayed at top of dialog
	 * @param msg  message to display in dialog
	 * @return  the alert dialog
	 */
	private AlertDialog createSimpleDialog(String title, String msg) {
		AlertDialog.Builder dialogBldr = new AlertDialog.Builder(getActivity());
		dialogBldr.setTitle(title);
		dialogBldr.setMessage(msg);
		dialogBldr.setNeutralButton(R.string.ok, null);

		return dialogBldr.create();
	}

	/** 
	 * Asynchronous task to get bus location estimates from Translink service.
	 * Displays progress dialog while running in background.  
	 */
	private class GetBusInfo extends
			AsyncTask<BusStop, Void, Void> {
		private ProgressDialog dialog = new ProgressDialog(getActivity());
		private boolean success = true;

		@Override
		protected void onPreExecute() {
			dialog.setMessage("Retrieving bus info...");
			dialog.show();
		}

		@Override
		protected Void doInBackground(BusStop... selectedStops) {
			BusStop selectedStop = selectedStops[0];

			try {
				tlService.addBusLocationsForStop(selectedStop);
			} catch (TranslinkException e) {
				e.printStackTrace();
				success = false;
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void dummy) {
			dialog.dismiss();

			if (success) {
				plotBuses(zoomToFit);
				plotBusStop();
				plotUser();
				mapView.invalidate();
			} else {
				AlertDialog dialog = createSimpleDialog("Error", "Unable to retrieve bus location info...");
				dialog.show();
			}
		}
	}
}
