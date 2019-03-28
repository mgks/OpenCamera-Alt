package net.sourceforge.opencamera.Remotecontrol;

import android.Manifest;
import android.app.Activity;
import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;

import java.util.ArrayList;

/**
 * Preference fragment for scanning and displaying available Bluetooth LE devices.
 * The result is saved in app preferences
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class DeviceScanner extends ListActivity {
    private static final String TAG = "OC-BLEScanner";
    private LeDeviceListAdapter mLeDeviceListAdapter;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;
    private SharedPreferences mSharedPreferences;


    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_LOCATION_PERMISSIONS = 2;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_select);
        mHandler = new Handler();

        if( !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if( mBluetoothAdapter == null ) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Button startScanningButton = findViewById(R.id.StartScanButton);
        startScanningButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startScanning();
            }
        });

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        String preference_remote_device_name = PreferenceKeys.RemoteName;
        String remote_name = mSharedPreferences.getString(preference_remote_device_name, "none");
        if( MyDebug.LOG )
            Log.d(TAG, "preference_remote_device_name: " + remote_name);

        TextView currentRemote = findViewById(R.id.currentRemote);
        currentRemote.setText("Current remote: " + remote_name);

    }


    private void startScanning() {

        if( MyDebug.LOG )
            Log.d(TAG, "Start scanning");

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        // Initializes list view adapter.
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        setListAdapter(mLeDeviceListAdapter);

        // In real life most of bluetooth LE devices associated with location, so without this
        // permission the sample shows nothing in most cases
        int permissionCoarse = Build.VERSION.SDK_INT >= 23 ?
                ContextCompat
                        .checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) :
                PackageManager.PERMISSION_GRANTED;

        if (permissionCoarse == PackageManager.PERMISSION_GRANTED) {
            scanLeDevice(true);
        } else {
            askForCoarseLocationPermission();
        }
    }

    private void askForCoarseLocationPermission() {
        if( MyDebug.LOG )
            Log.d(TAG, "askForCoarseLocationPermission");
        // n.b., we only need ACCESS_COARSE_LOCATION, but it's simpler to request both to be consistent with Open Camera's
        // location permission requests in PermissionHandler. If we only request ACCESS_COARSE_LOCATION here, and later the
        // user enables something that needs ACCESS_FINE_LOCATION, Android ends up showing the "rationale" dialog - and once
        // that's dismissed, the permission seems to be granted without showing the permission request dialog (so it works,
        // but is confusing for the user)
        // Also note that if we did want to only request ACCESS_COARSE_LOCATION here, we'd need to declare that permission
        // explicitly in the AndroidManifest.xml, otherwise the dialog to request permission is never shown (and the permission
        // is denied automatically).
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if( MyDebug.LOG )
            Log.d(TAG, "onRequestPermissionsResult: requestCode " + requestCode);
        switch (requestCode) {
            case REQUEST_LOCATION_PERMISSIONS: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "location permission granted");
                    scanLeDevice(true);
                }
                else {
                    if( MyDebug.LOG )
                        Log.d(TAG, "location permission denied");
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if( MyDebug.LOG )
            Log.d(TAG, "onActivityResult");
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        if( MyDebug.LOG )
            Log.d(TAG, "pause...");
        super.onPause();
        if (mScanning) {
            scanLeDevice(false);
            mLeDeviceListAdapter.clear();
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
        if (device == null) return;
        if( MyDebug.LOG ) {
            Log.d(TAG, "onListItemClick");
            Log.d(TAG, device.getAddress());
        }
        String preference_remote_device_name = PreferenceKeys.RemoteName;
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(preference_remote_device_name, device.getAddress());
        editor.apply();
        scanLeDevice(false);
        finish();

        // intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_NAME, device.getName());
    }

    private void scanLeDevice(final boolean enable) {
        if( MyDebug.LOG )
            Log.d(TAG, "scanLeDevice: " + enable);
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    invalidateOptionsMenu();
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
        invalidateOptionsMenu();
    }

    private class LeDeviceListAdapter extends BaseAdapter {
        private final ArrayList<BluetoothDevice> mLeDevices;
        private final LayoutInflater mInflator;

        LeDeviceListAdapter() {
            super();
            mLeDevices = new ArrayList<>();
            mInflator = DeviceScanner.this.getLayoutInflater();
        }

        void addDevice(BluetoothDevice device) {
            if(!mLeDevices.contains(device)) {
                mLeDevices.add(device);
            }
        }

        BluetoothDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        void clear() {
            mLeDevices.clear();
        }

        @Override
        public int getCount() {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            // General ListView optimization code.
            if (view == null) {
                view = mInflator.inflate(R.layout.listitem_device, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = view.findViewById(R.id.device_address);
                viewHolder.deviceName = view.findViewById(R.id.device_name);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BluetoothDevice device = mLeDevices.get(i);
            final String deviceName = device.getName();
            if (deviceName != null && deviceName.length() > 0)
                viewHolder.deviceName.setText(deviceName);
            else
                viewHolder.deviceName.setText(R.string.unknown_device);
            viewHolder.deviceAddress.setText(device.getAddress());

            return view;
        }
    }

    // Device scan callback.
    private final BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLeDeviceListAdapter.addDevice(device);
                            mLeDeviceListAdapter.notifyDataSetChanged();
                        }
                    });
                }
            };

    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
    }
}