package com.example.alexzhao.wifiandroid;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
//import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
//import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

//import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "StackWifi";
    private static String INTRO_STRING;

    boolean showDebug = false;

    Button button;
    TextView wifiView;
    TextView locView;

    LocationManager loc;
    WifiManager wifi;

    AlertDialog alert;

    IntentFilter mIntentFilter;
    BroadcastReceiver mReceiver;

    String lastWifi = "";
    String lastLoc = "";

    TestService testService;
    boolean isBound = false;
    boolean isServiceOn = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TestService.MyBinder binder = (TestService.MyBinder) service;
            testService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            testService = null;
            isBound = false;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.e(TAG, "onCreate");
        isBound = false;
        showDebug = false;
        isServiceOn = false;

        loc = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        INTRO_STRING = getString(R.string.intro_string) + getString(R.string.android_string);

        /*
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getString("UUID", "").equals("")) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("UUID", UUID.randomUUID().toString());
            editor.commit();
        }
        */

        // request location permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0x12345);
            } else {
                continueOnCreate();
            }
        } else {
            continueOnCreate();
        }

        // the permission callback handles the rest of onCreate()
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (alert != null) {
                    alert.dismiss();
                    alert = null;
                }

                if (!loc.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    showGPSDisabledAlert();
                } else {
                    if (!wifi.isScanAlwaysAvailable() /*!wifi.isWifiEnabled()*/) {
                        showScanningAlert();
                        //showWifiDisabledAlert();
                    } else {
                        if (!isServiceOn/*!isBound*/) {
                            continueOnCreate();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 0x12345: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission granted, continue onCreate() things
                    continueOnCreate();

                } else {
                    // permission denied, try again haha
                    Toast.makeText(this, "Please allow the permission; we require it for the app to work!", Toast.LENGTH_LONG).show();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0x12345);
                    }
                }
                return;
            }
        }
    }

    private void continueOnCreate() {
        if (!loc.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGPSDisabledAlert();
        } else {
            if (!wifi.isScanAlwaysAvailable() /*!wifi.isWifiEnabled()*/) {
                showScanningAlert();
                //showWifiDisabledAlert();
            } else {
                // Initialize activity
                button = (Button) findViewById(R.id.button);
                wifiView = (TextView) findViewById(R.id.wifiView);
                locView = (TextView) findViewById(R.id.locView);
                wifiView.setTextSize(16);
                wifiView.setText(INTRO_STRING);
                wifiView.setMovementMethod(new ScrollingMovementMethod());

                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showDebug = !showDebug;
                        button.setText(showDebug ? "Hide data" : "Show data");
                        if (!showDebug) {
                            locView.setText("");
                            wifiView.setTextSize(16);
                            wifiView.setText(INTRO_STRING);
                        } else {
                            locView.setText(lastLoc.equals("") ? "Waiting for next location update..." : lastLoc);
                            wifiView.setTextSize(14);
                            wifiView.setText(lastWifi.equals("") ? "Waiting for next wifi update..." : lastWifi);
                        }
                    }
                });

                mIntentFilter = new IntentFilter();
                mIntentFilter.addAction("wifiintent");
                mIntentFilter.addAction("locintent");
                mIntentFilter.addAction("stopintent");
                mIntentFilter.addAction("gpsdisabledintent");
                mIntentFilter.addAction("gpsenabledintent");

                // Initialize receiver
                mReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getAction().equals("wifiintent")) {
                            lastWifi = intent.getStringExtra("networks");
                            if (showDebug) wifiView.setText(lastWifi);
                        } else if (intent.getAction().equals("locintent")) {
                            lastLoc = intent.getStringExtra("loc");
                            if (showDebug) locView.setText(lastLoc);
                        } else if (intent.getAction().equals("stopintent")) {
                            //Intent stopIntent = new Intent(MainActivity.this, TestService.class);
                            //stopService(stopIntent);
                            finish();
                        } else if (intent.getAction().equals("gpsdisabledintent")) {
                            showGPSDisabledAlert();
                        } else if (intent.getAction().equals("gpsenabledintent")) {
                            if (alert != null) {
                                alert.dismiss();
                                alert = null;
                            }
                        }
                    }
                };

                registerReceiver(mReceiver, mIntentFilter);

                // Begin service
                Intent intent = new Intent(this, TestService.class);
                startService(intent);
                bindService(intent, serviceConnection, 0);
                isBound = true;
                isServiceOn = true;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "Activity onDestroy");
        unregisterReceiver(mReceiver);
        stopService(new Intent(this, TestService.class));
        /*
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        */
        if (alert != null) {
            alert.dismiss();
            alert = null;
        }
        unbindService(serviceConnection);
        isServiceOn = false;
    }

    private void showGPSDisabledAlert() {
        if (alert == null) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
            alertDialogBuilder
                    .setMessage("GPS is disabled in your device. Please enable it using the settings menu, as we need GPS enabled for our app to work!")
                    .setCancelable(false)
                    .setPositiveButton("Settings",
                            new DialogInterface.OnClickListener(){
                                public void onClick(DialogInterface dialog, int id){
                                    Intent callGPSSettingIntent = new Intent(
                                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                    startActivity(callGPSSettingIntent);
                                }
                            });
            alert = alertDialogBuilder.create();
            alert.show();
        } else {
            Log.e(TAG, "gps: alert nonnull");
        }
    }

    private void showScanningAlert() {
        if (alert == null) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
            alertDialogBuilder
                    .setMessage("It looks like the option to allow detection of wifi networks at any time is off. Please enable it using the settings menu, as we need this option enabled for our app to work. " +
                            "Once you enable this option, you won't have to keep wifi on for us to collect data!")
                    .setCancelable(false)
                    .setPositiveButton("Settings",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    startActivityForResult(new Intent(wifi.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE), 111);
                                }
                            });
            alert = alertDialogBuilder.create();
            alert.show();
        } else {
            Log.e(TAG, "wifi: alert nonnull");
        }
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case 111:
                if (resultCode == RESULT_OK) {
                    continueOnCreate();
                } else {
                    Toast.makeText(this, "Please allow the permission; we require it for the app to work!", Toast.LENGTH_LONG).show();
                    startActivityForResult(new Intent(wifi.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE), 111);
                }
                break;
        }
    }

    /*
    private void showWifiDisabledAlert() {
        if (alert == null) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
            alertDialogBuilder
                    .setMessage("Wifi is disabled in your device. Please enable it using the settings menu, as we need Wifi enabled for our app to work!")
                    .setCancelable(false)
                    .setPositiveButton("Settings",
                            new DialogInterface.OnClickListener(){
                                public void onClick(DialogInterface dialog, int id){
                                    Intent callWifiSettingIntent = new Intent(
                                            Settings.ACTION_WIFI_SETTINGS);
                                    startActivity(callWifiSettingIntent);
                                }
                            });
            alert = alertDialogBuilder.create();
            alert.show();
        } else {
            Log.e(TAG, "wifi: alert nonnull");
        }
    }*/
}
