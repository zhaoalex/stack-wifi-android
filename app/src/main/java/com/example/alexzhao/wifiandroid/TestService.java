package com.example.alexzhao.wifiandroid;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
//import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Created by alexzhao on 4/9/18.
 */

public class TestService extends Service {

    private static final String TAG = "StackWifi";
    private static final int TIME_INTERVAL = 1000 * 60 * 1; //1000 * 60 * 5; // get gps location every 1 min
    private static final int DISTANCE = 5; // 5; // set the distance value in meter
    private static final String BASE_URL = "http://flask-env.p8indeshvi.us-west-2.elasticbeanstalk.com/insert";
    //private static final String BASE_URL_2 = "http://stackwifiandroid.000webhostapp.com/insert.php";

    private static final int NOTIFICATION_ID = 0x11111;

    private IBinder myBinder = new MyBinder();
    Handler handler;
    Runnable runnableMain;

    NotificationManager notificationManager;

    WifiManager wifi;
    WifiReceiver receiver;

    LocationManager loc;
    LocationListener GPSListener;
    Location lastLocation;
    ArrayList<WifiReceiver.ScanPair> lastNetworks = new ArrayList<>();
    boolean isOutsideUCLA = true;
    boolean isSittingStill = false;
    boolean getWifi = true;

    RequestQueue queue;

    //String UUID;

    /** The service is starting, due to a call to startService() */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "onStartCommand");

        isOutsideUCLA = true;
        isSittingStill = false;

        notificationManager = (NotificationManager) getSystemService(Service.NOTIFICATION_SERVICE);

        //UUID = PreferenceManager.getDefaultSharedPreferences(this).getString("UUID", "");

        if (intent != null && intent.getAction() != null) {
            if (intent.getAction().equals("STOP_SERVICE")) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        Toast.makeText(this, "Starting StackWifi service...", Toast.LENGTH_SHORT).show();

        // Initialize notification and foreground service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Intent stopSelf = new Intent(this, TestService.class);
        stopSelf.setAction("STOP_SERVICE");
        PendingIntent pStopSelf = PendingIntent.getService(this, 0, stopSelf,PendingIntent.FLAG_CANCEL_CURRENT);

        Notification.Builder builder = new Notification.Builder(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("StackWifi", "StackWifi", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Now collecting data!");

            notificationManager.createNotificationChannel(channel);

            builder.setChannelId("StackWifi");
        } else {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        Notification notification = builder
                .setContentTitle("Now collecting data!")
                .setContentText("To stop the data collection at any time, press the stop button in this notification (or just kill the app)!")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Stop", pStopSelf)
                .setStyle(new Notification.BigTextStyle().bigText("To stop the data collection at any time, press the stop button in this notification (or just kill the app)!"))
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Initialize wifi
        wifi = (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        receiver = new WifiReceiver();
        registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        // Initialize location
        loc = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        GPSListener = new LocationListener() {

            @Override
            public void onLocationChanged(Location location)
            {
                Log.e(TAG, "onLocationChanged: " + locString(location));
                if (isBetterLocation(location)) {
                    lastLocation = location;
                    setLocationTextView(location);
                    isSittingStill = false;
                } else {
                    Log.e(TAG, "New location " + locString(location) + " not better than old location");
                }
            }

            @Override
            public void onProviderDisabled(String provider)
            {
                Log.e(TAG, "onProviderDisabled: " + provider);
                Intent intent = new Intent();
                intent.setAction("gpsdisabledintent");
                sendBroadcast(intent);
            }

            @Override
            public void onProviderEnabled(String provider)
            {
                Log.e(TAG, "onProviderEnabled: " + provider);
                Intent intent = new Intent();
                intent.setAction("gpsenabledintent");
                sendBroadcast(intent);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras)
            {
                Log.e(TAG, "onStatusChanged: " + provider);
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            loc.requestLocationUpdates(LocationManager.GPS_PROVIDER, TIME_INTERVAL, DISTANCE, GPSListener);
            loc.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, TIME_INTERVAL, DISTANCE, GPSListener);
            lastLocation = loc.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } else {
            Log.e(TAG, "Permission not granted..");
            Toast.makeText(this, "Please enable the location permission, then restart the app and try again!", Toast.LENGTH_SHORT).show();
        }

        // Initialize internet
        queue = Volley.newRequestQueue(this);

        // if null location, use a dummy one for now
        if (lastLocation == null) {
            lastLocation = new Location("blah");
            lastLocation.setLatitude(0);
            lastLocation.setLongitude(0);
            lastLocation.setAltitude(0);
            lastLocation.setAccuracy(Integer.MAX_VALUE);
        }

        // Initialize timer
        handler = new Handler();

        runnableMain = new Runnable() {
            @Override
            public void run() {
                //Log.d(TAG, "run");
                // location is done automatically
                if (getWifi) {
                    Log.e(TAG, "thread: get wifi");
                    getWifi = false;
                    wifi.startScan();
                    handler.postDelayed(this, 1000 * 10); // 10 sec
                } else {
                    Log.e(TAG, "thread: send to server");
                    if (isOutsideUCLA) {
                        Log.e(TAG, "thread: outside ucla; don't send");
                    } else {
                        if (lastNetworks.size() == 0) {
                            Log.e(TAG, "thread: no networks (gps off?); don't send");
                        } else {
                            if (isSittingStill) {
                                Log.e(TAG, "thread: sitting still; don't send");
                            } else {
                                sendToServer();
                            }
                        }
                    }
                    getWifi = true;
                    isSittingStill = true;

                    handler.postDelayed(this, (1000 * 60 * 3) - 10); // 3 min - 10 sec;
                }
            }
        };

        handler.post(runnableMain);

        //getLocation();
        //getWifiStrengths();
        // sendToServer();

        return START_STICKY;
    }

    /** A client is binding to the service with bindService() */
    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "onBind");
        return myBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e(TAG, "onUnbind");
        //stopSelf();
        return false;
    }

    public class MyBinder extends Binder {
        public TestService getService() {
            return TestService.this;
        }
    }

    /** Called when the service is no longer used and is being destroyed */
    @Override
    public void onDestroy() {
        Log.e(TAG,"onDestroy Stopping service");
        Toast.makeText(this, "Stopping StackWifi service...", Toast.LENGTH_SHORT).show();
        notificationManager.cancel(NOTIFICATION_ID);
        unregisterReceiver(receiver);
        stopForeground(true);
        handler.removeCallbacks(runnableMain);

        Intent stopIntent = new Intent();
        stopIntent.setAction("stopintent");
        sendBroadcast(stopIntent);
        super.onDestroy();
    }

    // *********************************
    // LOCATION LOGIC
    // *********************************

    private void getLocation() {
        Log.d(TAG, "Begin getting location");

        if (!loc.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please enable GPS!", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "GPS disabled");
        } else {
            Log.e(TAG, "Getting location");
            // lastLocation = loc.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            //locView.setText(locString(lastLocation));
            setLocationTextView(lastLocation);
        }
    }

    private void setLocationTextView(Location location) {
        String output = locString(location);
        if (checkOutsideUCLA(location)) { // is inside UCLA
            output += "\nOutside UCLA!";
        }

        Intent locIntent = new Intent();
        locIntent.setAction("locintent");
        locIntent.putExtra("loc", output);
        sendBroadcast(locIntent);
    }

    // Will also update the global isOutsideUCLA var
    private boolean checkOutsideUCLA(Location loc) {
        if (loc.getLatitude() < 34.06
                || loc.getLatitude() > 34.08
                || loc.getLongitude() < -118.456
                || loc.getLongitude() > -118.437) {
            isOutsideUCLA = true;
            return true;
        } else {
            isOutsideUCLA = false;
            return false;
        }
    }

    private String locString(Location location) {
        return location.getProvider() + " " + location.getLatitude() + ", " + location.getLongitude();
    }

    private boolean isBetterLocation(Location loc) {
        // A new location is always better than no location
        if (lastLocation == null) return true;

        float distDelta = loc.distanceTo(lastLocation);
        Log.i(TAG, "distDelta: " + Float.toString(distDelta));
        boolean isSignificantlyFarther = distDelta > 10;

        if (!isSignificantlyFarther) {
            Log.i(TAG, "is not significantly farther; return false");
            return false;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = loc.getTime() - lastLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > 1000 * 60 * 10;
        boolean isSignificantlyOlder = timeDelta < -1000 * 60 * 3;
        boolean isNewer = timeDelta > 0;

        /*
        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse
        } else */ if (isSignificantlyOlder) {
            Log.i(TAG, "is significantly older; return false");
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (loc.getAccuracy() - lastLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 10;//0;
        boolean isMoreAccurate = accuracyDelta < 10;//10;
        boolean isSignificantlyLessAccurate = accuracyDelta > 20;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(loc.getProvider(),
                lastLocation.getProvider());


        Log.i(TAG, "accuDelta: " + Integer.toString(accuracyDelta));

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            Log.i(TAG, "is more accurate (to +- 10); return true");
            return true;
        } else if (isNewer && !isLessAccurate) {
            Log.i(TAG, "is newer and not less accurate; return true");
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            Log.i(TAG, "is newer and not significantly less accurate and is from same provider; return true");
            return true;
        }
        Log.i(TAG, "all failed; return false");
        return false;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    // *********************************
    // WIFI LOGIC
    // *********************************

    private void getWifiStrengths() {
        Log.e(TAG, "getWifiStrengths");

        wifi.startScan(); // start scanning for wifi networks
    }

    public class WifiReceiver extends BroadcastReceiver {
        public class ScanPair {
            String SSID;
            int level;

            public ScanPair(String SSID, int level) {
                this.SSID = SSID;
                this.level = level;
            }

            public String getSSID() { return SSID; }

            public int getLevel() { return level; }

            @Override
            public boolean equals(Object obj) { // true if SSIDs match
                if (this == obj) return true;
                if (!(obj instanceof ScanPair)) return false;
                ScanPair other = (ScanPair) obj;
                return this.SSID.equals(other.SSID);
            }

            @Override
            public int hashCode() { // hash SSIDs only
                return Objects.hash(SSID);
            }
        }

        @Override
        public void onReceive(Context c, Intent intent)
        {
            List<ScanResult> results = wifi.getScanResults();
            LinkedHashSet<ScanPair> resultSet = new LinkedHashSet<ScanPair>(); // delete dupes, maintain insertion order

            for (int i = 0; i < results.size(); i++) {
                resultSet.add(new ScanPair(results.get(i).SSID, results.get(i).level));
            }

            ArrayList<ScanPair> stripped = new ArrayList<ScanPair>(resultSet);
            lastNetworks = stripped;

            Log.e(TAG, "Wifi scan caught; " + Integer.toString(results.size()) + " networks found, reduced to " + Integer.toString(stripped.size()));

            String output = "Number of networks: " + stripped.size() + " (stripped from " + results.size() + ")\n\n";

            for (int i = 0; i < stripped.size(); i++) {
                output += (stripped.get(i).getSSID() + ": " + stripped.get(i).getLevel() + "\n");
                // Log.d(TAG, stripped.get(i).getSSID() + ": " + stripped.get(i).getLevel());
            }

            // sendToServer();

            Intent wifiIntent = new Intent();
            wifiIntent.setAction("wifiintent");
            wifiIntent.putExtra("networks", output);
            sendBroadcast(wifiIntent);

        };
    }

    // *********************************
    // REQUEST LOGIC
    // *********************************

    private void sendToServer() {
        // do for each wifi
        for (int i = 0; i < lastNetworks.size(); i++) {
            if (!isNotableNetwork(lastNetworks.get(i).getSSID())) {
                Log.d(TAG, "Throw away " + lastNetworks.get(i).getSSID());
                continue;
            }

            String requestURL = Uri.parse(BASE_URL)
                    .buildUpon()
                    .appendQueryParameter("name", lastNetworks.get(i).getSSID())
                    .appendQueryParameter("strength", Integer.toString(lastNetworks.get(i).getLevel()))
                    .appendQueryParameter("latitude", Double.toString(lastLocation.getLatitude()))
                    .appendQueryParameter("longitude", Double.toString(lastLocation.getLongitude()))
                    .appendQueryParameter("elevation", Double.toString(lastLocation.getAltitude()))
                    //.appendQueryParameter("id", UUID)
                    .build()
                    .toString();

            StringRequest stringRequest = new StringRequest(Request.Method.GET, requestURL,
                    new Response.Listener<String>()
                    {
                        @Override
                        public void onResponse(String response) {
                            Log.e(TAG, "Request send successful");
                        }
                    },
                    new Response.ErrorListener()
                    {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "Request failed: " + error.getLocalizedMessage());
                        }
                    }
            );
            stringRequest.setRetryPolicy(new DefaultRetryPolicy(5 * 1000, 3, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

            queue.add(stringRequest);
        }
    }

    private boolean isNotableNetwork(String str) {
        return (str.equals("UCLA_WEB") ||
                str.equals("UCLA_WIFI") ||
                str.equals("eduroam") ||
                str.equals("UCLA_WEB_RES") ||
                str.equals("UCLA_WIFI_RES") ||
                str.equals("UCLA_SECURE_RES"));
    }

}