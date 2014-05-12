package ca.ubc.cpsc210.nextbus;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.util.Log;

public class MapDisplayLocationListener implements LocationListener {
	/**
	 * Log tag for LogCat messages
	 */
	private final static String LOG_TAG = "MapDisplayLocationListener";
	
	/**
	 * the MapDisplayFragment to which the listener is associated
	 */
	private MapDisplayFragment mapFragment;
	

	/**
	 * @param mapFragment
	 */
	MapDisplayLocationListener(MapDisplayFragment mapFragment) {
		this.mapFragment = mapFragment;
	}

	/* (non-Javadoc)
	 * @see android.location.LocationListener#onLocationChanged(android.location.Location)
	 * LocationManager calls this method with the new location as the parameter.
	 * This new location will be passed to the associated MapDisplayFragment.
	 * The updateLocation method in MapDisplayFragment will be called.
	 * The new location may be null at times, print a message if it is not.
	 */
	@Override
	public void onLocationChanged(Location location) {
		Log.d(LOG_TAG, "onLocationChanged");
		mapFragment.setCurrentLocation(location);
		mapFragment.updateLocation();
		if (location != null) {
			Log.d(LOG_TAG, "The location has changed!");
		}
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// do nothing

	}

	@Override
	public void onProviderEnabled(String provider) {
		// do nothing

	}

	@Override
	public void onProviderDisabled(String provider) {
		// do nothing

	}

}
