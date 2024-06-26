package com.emanuelef.pcap_receiver;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.IpV4Packet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Observable;
import java.util.Observer;

public class MainActivity extends AppCompatActivity implements Observer {
    static final String PCAPDROID_PACKAGE = "com.emanuelef.remote_capture"; // add ".debug" for the debug build of PCAPdroid
    static final String CAPTURE_CTRL_ACTIVITY = "com.emanuelef.remote_capture.activities.CaptureCtrl";
    static final String CAPTURE_STATUS_ACTION = "com.emanuelef.remote_capture.CaptureStatus";
    static final String TAG = "PCAP Receiver";
    private static final int PCAPDROID_TRAILER_SIZE = 32;
    private static final int PCAPDROID_MAGIC = 0x01072021;
    private static final int FCS_SIZE = 4;

    Button mStart;
    CaptureThread mCapThread;
    TextView mLog;
    boolean mCaptureRunning = false;

    private final ActivityResultLauncher<Intent> captureStartLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleCaptureStartResult);
    private final ActivityResultLauncher<Intent> captureStopLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleCaptureStopResult);
    private final ActivityResultLauncher<Intent> captureStatusLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleCaptureStatusResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mLog = findViewById(R.id.pkts_log);
        mStart = findViewById(R.id.start_btn);
        mStart.setOnClickListener(v -> {
            if(!mCaptureRunning)
                startCapture();
            else
                stopCapture();
        });

        if((savedInstanceState != null) && savedInstanceState.containsKey("capture_running"))
            setCaptureRunning(savedInstanceState.getBoolean("capture_running"));
        else
            queryCaptureStatus();

        // will call the "update" method when the capture status changes
        MyBroadcastReceiver.CaptureObservable.getInstance().addObserver(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MyBroadcastReceiver.CaptureObservable.getInstance().deleteObserver(this);
        stopCaptureThread();
    }

    @Override
    public void update(Observable o, Object arg) {
        boolean capture_running = (boolean)arg;
        Log.d(TAG, "capture_running: " + capture_running);
        setCaptureRunning(capture_running);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle bundle) {
        bundle.putBoolean("capture_running", mCaptureRunning);
        super.onSaveInstanceState(bundle);
    }

    /**
     * Called when a packet is received from PCAPdroid.
     *
     * @param pkt The Ethernet packet received (if pcapdroid_trailer is set, the packet is wrapped in an Ethernet frame).
     *            If pcapdroid_trailer is not set, the packet is an IpV4Packet, so the code can be simplified to:
     *
     *                void onPacketReceived(IpV4Packet pkt) {
     *                  IpV4Packet.IpV4Header hdr = pkt.getHeader();
     *                  // logging etc.
     *                }
     */
    void onPacketReceived(EthernetPacket pkt) {
        int ethPayloadLength = pkt.length();
        if (ethPayloadLength < PCAPDROID_TRAILER_SIZE) {
            Log.w(TAG, "Packet too short to contain trailer");
            return;
        }

        byte[] trailer = Arrays.copyOfRange(pkt.getRawData(), ethPayloadLength - PCAPDROID_TRAILER_SIZE, ethPayloadLength - FCS_SIZE);

        ByteBuffer trailerBuffer = ByteBuffer.wrap(trailer);

        int magic = trailerBuffer.getInt(0);
        int uid = trailerBuffer.getInt(4);
        byte[] appNameBytes = new byte[20];
        trailerBuffer.position(8);
        trailerBuffer.get(appNameBytes, 0, 20);
        String appName = new String(appNameBytes, StandardCharsets.UTF_8).trim();

        if (magic != PCAPDROID_MAGIC) {
            Log.w(TAG, "Invalid magic number: " + Integer.toHexString(magic));
            return;
        }

        if (pkt.getPayload() instanceof IpV4Packet) {
            IpV4Packet ipV4Packet = (IpV4Packet) pkt.getPayload();
            IpV4Packet.IpV4Header hdr = ipV4Packet.getHeader();
            mLog.append(String.format("[%s] %s -> %s [%d B] (App: %s, UID: %d)\n",
                    hdr.getProtocol(),
                    hdr.getSrcAddr().getHostAddress(), hdr.getDstAddr().getHostAddress(),
                    ipV4Packet.length(), appName, uid));
        } else {
            Log.w(TAG, "Received non-IPv4 packet");
        }
    }

    void queryCaptureStatus() {
        Log.d(TAG, "Querying PCAPdroid");

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PCAPDROID_PACKAGE, CAPTURE_CTRL_ACTIVITY);
        intent.putExtra("action", "get_status");

        try {
            captureStatusLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "PCAPdroid package not found: " + PCAPDROID_PACKAGE, Toast.LENGTH_LONG).show();
        }
    }

    void startCapture() {
        Log.d(TAG, "Starting PCAPdroid");

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PCAPDROID_PACKAGE, CAPTURE_CTRL_ACTIVITY);

        intent.putExtra("action", "start");
        intent.putExtra("broadcast_receiver", "com.emanuelef.pcap_receiver.MyBroadcastReceiver");
        intent.putExtra("pcap_dump_mode", "udp_exporter");
        intent.putExtra("collector_ip_address", "127.0.0.1");
        intent.putExtra("collector_port", "5123");
        intent.putExtra("pcapdroid_trailer", "true");
        //intent.putExtra("app_filter", "org.mozilla.firefox");

        captureStartLauncher.launch(intent);
    }

    void stopCapture() {
        Log.d(TAG, "Stopping PCAPdroid");

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PCAPDROID_PACKAGE, CAPTURE_CTRL_ACTIVITY);
        intent.putExtra("action", "stop");

        captureStopLauncher.launch(intent);
    }

    void setCaptureRunning(boolean running) {
        mCaptureRunning = running;
        mStart.setText(running ? "Stop Capture" : "Start Capture");

        if(mCaptureRunning && (mCapThread == null)) {
            mCapThread = new CaptureThread(this);
            mCapThread.start();
        } else if(!mCaptureRunning)
            stopCaptureThread();
    }

    void stopCaptureThread() {
        if(mCapThread == null)
            return;

        mCapThread.stopCapture();
        mCapThread.interrupt();
        mCapThread = null;
    }

    void handleCaptureStartResult(final ActivityResult result) {
        Log.d(TAG, "PCAPdroid start result: " + result);

        if(result.getResultCode() == RESULT_OK) {
            Toast.makeText(this, "Capture started!", Toast.LENGTH_SHORT).show();
            setCaptureRunning(true);
            mLog.setText("");
        } else
            Toast.makeText(this, "Capture failed to start", Toast.LENGTH_SHORT).show();
    }

    void handleCaptureStopResult(final ActivityResult result) {
        Log.d(TAG, "PCAPdroid stop result: " + result);

        if(result.getResultCode() == RESULT_OK) {
            Toast.makeText(this, "Capture stopped!", Toast.LENGTH_SHORT).show();
            setCaptureRunning(false);
        } else
            Toast.makeText(this, "Could not stop capture", Toast.LENGTH_SHORT).show();

        Intent intent = result.getData();
        if((intent != null) && (intent.hasExtra("bytes_sent")))
            logStats(intent);
    }

    void handleCaptureStatusResult(final ActivityResult result) {
        Log.d(TAG, "PCAPdroid status result: " + result);

        if((result.getResultCode() == RESULT_OK) && (result.getData() != null)) {
            Intent intent = result.getData();
            boolean running = intent.getBooleanExtra("running", false);
            int verCode = intent.getIntExtra("version_code", 0);
            String verName = intent.getStringExtra("version_name");

            if(verName == null)
                verName = "<1.4.6";

            Log.d(TAG, "PCAPdroid " + verName + "(" + verCode + "): running=" + running);
            setCaptureRunning(running);
        }
    }

    void logStats(Intent intent) {
        String stats = "*** Stats ***" +
                "\nBytes sent: " +
                intent.getLongExtra("bytes_sent", 0) +
                "\nBytes received: " +
                intent.getLongExtra("bytes_rcvd", 0) +
                "\nPackets sent: " +
                intent.getIntExtra("pkts_sent", 0) +
                "\nPackets received: " +
                intent.getIntExtra("pkts_rcvd", 0) +
                "\nPackets dropped: " +
                intent.getIntExtra("pkts_dropped", 0) +
                "\nPCAP dump size: " +
                intent.getLongExtra("bytes_dumped", 0);

        Log.i("stats", stats);
    }
}