package com.example.cpro.tangobot;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashActivity extends AppCompatActivity {

    private static final boolean DO_ARDUINO = true;

    private final String TAG = DashActivity.class.getSimpleName();
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    private static UsbManager mUsbManager;
    private static UsbDevice device;

    private static UsbSerialPort sPort = null;

    //private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    //private SerialInputOutputManager mSerialIoManager;

    private Tango mTango;
    private TangoConfig mConfig;

    private TextView mTextView;

    byte[] botStateCmd = {'S'};
    protected String stateString;
    private long lastStateChangeMillis;
    private static final int stateChangeTimeoutMs = 500;

    FirebaseDatabase database;
    DatabaseReference myRef;

    @Override
    public View findViewById(int id) {
        return super.findViewById(id);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dash);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mTextView = (TextView) findViewById(R.id.textView);
        openDevice(null);
        lastStateChangeMillis = System.currentTimeMillis();
        database = FirebaseDatabase.getInstance();
        myRef = database.getReference("stateString");

        myRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again
                // whenever data at this location is updated.
                String value = dataSnapshot.getValue(String.class);
                if (value != null) {
                    Log.d(TAG, "Got from Firebase: " + value);
                    botStateCmd = value.getBytes();
                    sendCmd(botStateCmd);
                }

            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w(TAG, "Failed to read value from Firebase.", error.toException());
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        //stopIoManager();
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        synchronized (this) {
            try {
                mTango.disconnect();
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Initialize Tango Service as a normal Android Service, since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time when onResume gets called, we
        // should create a new Tango object.
        mTango = new Tango(DashActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready,
            // this Runnable will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only
            // when there is no UI thread changes involved.
            @Override
            public void run() {
                synchronized (DashActivity.this) {
                    mConfig = setupTangoConfig(mTango);

                    try {
                        setTangoListeners();
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                    } catch (SecurityException e) {
                        Log.e(TAG, getString(R.string.permission_motion_tracking), e);
                    }
                    try {
                        mTango.connect(mConfig);
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                    }
                }
            }
        });
        
        try {
            if(sPort == null)
            {openDevice(null);}
        } catch (Exception e)
        {
            Toast.makeText(getApplicationContext(), "Failed to open device!", Toast.LENGTH_LONG).show();
        }
        if (sPort != null) {
            Log.d(TAG, "Resumed, port=" + sPort);
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(sPort.getDriver().getDevice(), mPermissionIntent);


            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                //mTitleTextView.setText("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (Exception e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                //mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            //mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        //onDeviceStateChange();
    }

    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Create a new Tango Configuration and enable the Depth Sensing API.
        TangoConfig config = new TangoConfig();
        config = tango.getConfig(config.CONFIG_TYPE_DEFAULT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        return config;
    }

    /**
     * Set up the callback listeners for the Tango service, then begin using the Motion
     * Tracking API. This is called in response to the user clicking the 'Start' Button.
     */
    private void setTangoListeners() {
        timesSeenObstacle = 0;
        // Lock configuration and connect to Tango.
        // Select coordinate frame pair.
        final ArrayList<TangoCoordinateFramePair> framePairs =
                new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));

        // Listen for new Tango data
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // We are not using TangoPoseData for this application.
            }

            @Override
            public void onXyzIjAvailable(final TangoXyzIjData xyzIjData) {
                updatePosition(xyzIjData);
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    //to filter out false positives
    private int timesSeenObstacle;

    /**
     * Analyze point cloud data and decide if we should turn to avoid collision
     */
    private void updatePosition(TangoXyzIjData xyzIjData) {

        //check for obstacles only if moving forward
        if (botStateCmd[0] != 'F') {
            return;
        }

        //check for obstacles only if at least 1s passed since last state change
        if (System.currentTimeMillis() - lastStateChangeMillis < stateChangeTimeoutMs) {
            return;
        }

        float X, Y, Z;
        float minZ = 100500;
        float minParaZ = 100500;
        int cntPointsTooClose = 0;

        for (int i = 0; i < xyzIjData.xyz.capacity() - 3; i = i + 3) {
            X = xyzIjData.xyz.get(i);
            Y = xyzIjData.xyz.get(i + 1);
            Z = xyzIjData.xyz.get(i + 2);
            minZ = Math.min(minZ, Z);
            if (Z > (6*Math.pow(X,2) + 10*Math.pow(Y+0.1,2))) {
                minParaZ = Math.min(minParaZ, Z);
                if (Z < 0.5) {
                    if (++cntPointsTooClose > 5) {
                        timesSeenObstacle++;
                        break;
                    }
                }
            }
        }
        if(cntPointsTooClose<=5) {
            timesSeenObstacle=0;
        }
        byte[] cmd;

        if (timesSeenObstacle>1 && timesSeenObstacle < 10) {
            cmd = new byte[]{'L'};
            stateString = String.format("Turning left (%.3f,%.3f)", minZ, minParaZ);
        } else if (timesSeenObstacle > 9) {
            cmd = new byte[]{'B'};
            stateString = "Go backward";
        } else  {
                cmd = new byte[]{'F'};
                stateString = String.format("Go forward (%.3f,%.3f)", minZ, minParaZ);
        }
        Log.i(TAG, stateString);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(stateString);
            }
        });
        sendCmd(cmd);
    }



    /*
    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }
    */

    void openDevice(UsbDevice device)
    {
        if(!DO_ARDUINO) {
            return;
        }

        UsbDevice serialDev = null;
        UsbDevice iDev;
        if(device == null) {
            HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
            Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();

            while(deviceIterator.hasNext()){

                iDev = deviceIterator.next();
                if(iDev.getVendorId() == 9025 && iDev.getProductId() == 16)
                {
                    serialDev = iDev;
                    Toast.makeText(this,"Robot found", Toast.LENGTH_LONG).show();
                    break;
                }

            }
        } else
        {
            serialDev = device;
        }

        if(serialDev == null) {
            Toast.makeText(getApplicationContext(), "Robot not found!", Toast.LENGTH_LONG).show();
            sPort = null;
            finish();
        }
        else
        {
            try {
                CdcAcmSerialDriver driver = new CdcAcmSerialDriver(serialDev);
                sPort = driver.getPorts().get(0);
            } catch (Exception e)
            {
                Toast.makeText(getApplicationContext(), "Failed to open port!", Toast.LENGTH_LONG).show();
                sPort = null;
                finish();
            }

        }
    }

    public void  L(View view)
    {
        botStateCmd[0] = 'L';
        sendCmd(botStateCmd);
    }

    public void  R(View view)
    {
        botStateCmd[0] = 'R';
        sendCmd(botStateCmd);
    }

    public void  F(View view)
    {
        botStateCmd[0] = 'F';
        sendCmd(botStateCmd);
    }

    public void  B(View view)
    {
        botStateCmd[0] = 'B';
        sendCmd(botStateCmd);
    }

    public void  S(View view)
    {
        botStateCmd[0] = 'S';
        sendCmd(botStateCmd);
    }

    private void sendCmd(byte[] c)
    {
        try {
            if (sPort != null){
                sPort.write(c, 1000);
                lastStateChangeMillis = System.currentTimeMillis();
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            sPort = null;
        }
    }

}
