package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // UI Elements
    TextView statusText, nodeStatusText, qualityText;
    TextView rssiText, latencyText, packetLossText;
    Button connectButton, disconnectButton, sendWifiBtn, toggleWifiBtn, resetStatsBtn;
    EditText ssidInput, passInput;
    View wifiCard, tabDashboard, tabAnalytics, tabPlacement;
    Button navDash, navAnalytics, navPlacement;
    Switch demoModeSwitch;
    LineChart rssiChart, latencyChart, packetChart;

    // Dynamic Mesh UI
    Button startCalibBtn;
    TextView calibStatusText, distanceReadoutText;
    FrameLayout heatmapContainer;
    HeatmapView heatmapView;

    // Bluetooth
    BluetoothAdapter bluetoothAdapter;
    BluetoothSocket socket;
    InputStream inputStream;
    OutputStream outputStream;
    String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E"; // Update this to your Master's MAC!
    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    boolean isProvisioning = false;

    // Data Model
    class NodeData {
        float rssi, snr, voltage, loss, latency, jitter;
        long lastUpdate;
        double totalRssiSum = 0;
        long rssiCount = 0;
    }

    NodeData[] nodes = new NodeData[3];
    int graphIndex = 0;

    // Handlers
    Handler uiHandler = new Handler(Looper.getMainLooper());
    Runnable uiRefreshRunnable;
    Handler demoHandler = new Handler(Looper.getMainLooper());
    Runnable demoRunnable;
    Random random = new Random();

    float[] demoRssi = {-45f, -60f, -75f};
    float[] demoLatency = {12f, 25f, 45f};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // Bind UI
        statusText = findViewById(R.id.statusText);
        nodeStatusText = findViewById(R.id.nodeStatusText);
        qualityText = findViewById(R.id.qualityText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        sendWifiBtn = findViewById(R.id.sendWifiBtn);
        toggleWifiBtn = findViewById(R.id.toggleWifiBtn);
        resetStatsBtn = findViewById(R.id.resetStatsBtn);

        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        wifiCard = findViewById(R.id.wifiCard);
        demoModeSwitch = findViewById(R.id.demoModeSwitch);

        tabDashboard = findViewById(R.id.tabDashboard);
        tabAnalytics = findViewById(R.id.tabAnalytics);
        tabPlacement = findViewById(R.id.tabPlacement);
        navDash = findViewById(R.id.navDash);
        navAnalytics = findViewById(R.id.navAnalytics);
        navPlacement = findViewById(R.id.navPlacement);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        startCalibBtn = findViewById(R.id.startCalibBtn);
        calibStatusText = findViewById(R.id.calibStatusText);
        distanceReadoutText = findViewById(R.id.distanceReadoutText);
        heatmapContainer = findViewById(R.id.heatmapContainer);

        // Inject the custom Heatmap engine
        heatmapView = new HeatmapView(this);
        heatmapContainer.addView(heatmapView);

        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();

        setupChart(rssiChart, -100f, -15f);
        initChartData(rssiChart);
        setupChart(latencyChart, 0f, 150f);
        initChartData(latencyChart);
        setupChart(packetChart, 0f, 20f);
        initChartData(packetChart);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
        }

        connectButton.setOnClickListener(v -> connectBT());
        disconnectButton.setOnClickListener(v -> disconnectBT());
        sendWifiBtn.setOnClickListener(v -> sendWiFiCredentials());

        toggleWifiBtn.setOnClickListener(v -> {
            boolean isConnected = (socket != null && socket.isConnected());
            if (!isConnected && !demoModeSwitch.isChecked()) {
                Toast.makeText(MainActivity.this, "Connect to hardware first!", Toast.LENGTH_SHORT).show();
                return;
            }
            wifiCard.setVisibility(wifiCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        resetStatsBtn.setOnClickListener(v -> resetSessionData());
        startCalibBtn.setOnClickListener(v -> triggerMeshSweep());

        setupNavigation();
        setupDemoMode();

        uiRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                uiHandler.postDelayed(this, 200);
            }
        };
        uiHandler.post(uiRefreshRunnable);
    }

    private void triggerMeshSweep() {
        boolean isConnected = (socket != null && socket.isConnected());
        if (!isConnected && !demoModeSwitch.isChecked()) {
            Toast.makeText(this, "Connect Bluetooth First", Toast.LENGTH_SHORT).show();
            return;
        }

        startCalibBtn.setEnabled(false);
        startCalibBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.GRAY));

        new CountDownTimer(18000, 1000) {
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                if (sec > 13) calibStatusText.setText("Mapping Node 1 Network... (" + sec + "s)");
                else if (sec > 8)
                    calibStatusText.setText("Mapping Node 2 Network... (" + sec + "s)");
                else if (sec > 3)
                    calibStatusText.setText("Mapping Node 3 Network... (" + sec + "s)");
                else calibStatusText.setText("Triangulating Router... (" + sec + "s)");

                calibStatusText.setTextColor(Color.parseColor("#FFD740"));

                if (sec == 17 && !demoModeSwitch.isChecked()) {
                    try {
                        outputStream.write("CALIBRATE\n".getBytes());
                        outputStream.flush();
                    } catch (Exception e) {
                    }
                }
            }

            public void onFinish() {
                if (demoModeSwitch.isChecked()) {
                    process("MESH,-45.0,-55.0,-62.0,-40.5,-51.0,-65.0\n");
                }
                startCalibBtn.setEnabled(true);
                startCalibBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            }
        }.start();
    }

    private void resetSessionData() {
        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();
        graphIndex = 0;
        rssiChart.clear();
        initChartData(rssiChart);
        latencyChart.clear();
        initChartData(latencyChart);
        packetChart.clear();
        initChartData(packetChart);
        qualityText.setText("--/100");
        distanceReadoutText.setText("Awaiting mesh data...");
        Toast.makeText(this, "Session Data Cleared", Toast.LENGTH_SHORT).show();
        updateUI();
    }

    private void setupNavigation() {
        navDash.setOnClickListener(v -> switchTab(tabDashboard, navDash));
        navAnalytics.setOnClickListener(v -> switchTab(tabAnalytics, navAnalytics));
        navPlacement.setOnClickListener(v -> switchTab(tabPlacement, navPlacement));
    }

    private void switchTab(View activeTab, Button activeBtn) {
        tabDashboard.setVisibility(View.GONE);
        tabAnalytics.setVisibility(View.GONE);
        tabPlacement.setVisibility(View.GONE);
        navDash.setTextColor(Color.parseColor("#888888"));
        navAnalytics.setTextColor(Color.parseColor("#888888"));
        navPlacement.setTextColor(Color.parseColor("#888888"));

        activeTab.setVisibility(View.VISIBLE);
        activeBtn.setTextColor(Color.parseColor("#00E5FF"));
    }

    private void setupDemoMode() {
        demoModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                disconnectBT();
                statusText.setText("Demo Mode Active");
                statusText.setTextColor(Color.parseColor("#FFD740"));
                wifiCard.setVisibility(View.GONE);

                demoRunnable = new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < 3; i++) {
                            demoRssi[i] += (random.nextFloat() - 0.5f) * 4f;
                            demoRssi[i] = Math.max(-95f, Math.min(-35f, demoRssi[i]));
                            demoLatency[i] += (random.nextFloat() - 0.5f) * 5f;
                            demoLatency[i] = Math.max(5f, Math.min(120f, demoLatency[i]));

                            int s = 25;
                            float v = 3.6f + (random.nextFloat() * 0.2f);
                            float l = random.nextFloat() < 0.05 ? 2.0f : 0.0f;
                            int j = random.nextInt(5);

                            String fakeData = String.format(Locale.US, "%d,%d,%d,%.2f,%.2f,%d,%d\n",
                                    i + 1, (int) demoRssi[i], s, v, l, j, (int) demoLatency[i]);
                            process(fakeData);
                        }
                        demoHandler.postDelayed(this, 1000);
                    }
                };
                demoHandler.post(demoRunnable);
            } else {
                demoHandler.removeCallbacks(demoRunnable);
                statusText.setText("Disconnected");
                statusText.setTextColor(Color.parseColor("#FF5252"));
                resetSessionData();
            }
        });
    }

    private void connectBT() {
        if (demoModeSwitch.isChecked()) return;
        statusText.setText("Connecting...");
        statusText.setTextColor(Color.YELLOW);

        new Thread(() -> {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS);
                bluetoothAdapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                runOnUiThread(() -> {
                    statusText.setText("Connected to Master");
                    statusText.setTextColor(Color.GREEN);
                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);
                });
                readData();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Connection Failed");
                    statusText.setTextColor(Color.RED);
                });
            }
        }).start();
    }

    private void disconnectBT() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        statusText.setText("Disconnected");
        statusText.setTextColor(Color.RED);
        connectButton.setVisibility(View.VISIBLE);
        disconnectButton.setVisibility(View.GONE);
        wifiCard.setVisibility(View.GONE);
    }

    private void sendWiFiCredentials() {
        if (socket == null || !socket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = ssidInput.getText().toString().trim();
        String pass = passInput.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "SSID and Pass cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        String payload = "WIFI:" + ssid + "," + pass + "\n";
        new Thread(() -> {
            try {
                isProvisioning = true;
                outputStream.write(payload.getBytes());
                outputStream.flush();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Deploying WiFi...", Toast.LENGTH_SHORT).show();
                    wifiCard.setVisibility(View.GONE);
                    statusText.setText("Deploying...");
                    statusText.setTextColor(Color.CYAN);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void readData() {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    process(line);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isProvisioning) {
                        statusText.setText("Rebooting ESP32...");
                        statusText.setTextColor(Color.YELLOW);
                    } else {
                        disconnectBT();
                    }
                });
                if (isProvisioning) reconnectBluetooth();
            }
        }).start();
    }

    private void reconnectBluetooth() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            statusText.setText("Reconnecting...");
            statusText.setTextColor(Color.YELLOW);
            connectBT();
            isProvisioning = false;
        }, 4000);
    }

    private void process(String d) {
        try {
            String clean = d.replaceAll("[\\n\\r]", "").trim();

            if (clean.startsWith("MESH,")) {
                String[] parts = clean.split(",");
                if (parts.length >= 7) {
                    float rssi12 = Float.parseFloat(parts[1]);
                    float rssi13 = Float.parseFloat(parts[2]);
                    float rssi23 = Float.parseFloat(parts[3]);
                    float rssiR1 = Float.parseFloat(parts[4]);
                    float rssiR2 = Float.parseFloat(parts[5]);
                    float rssiR3 = Float.parseFloat(parts[6]);

                    double d12 = rssi12 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi12) / 25.0);
                    double d13 = rssi13 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi13) / 25.0);
                    double d23 = rssi23 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi23) / 25.0);

                    double r1 = rssiR1 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR1) / 31.9);
                    double r2 = rssiR2 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR2) / 31.9);
                    double r3 = rssiR3 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR3) / 31.9);

                    heatmapView.updateMeshGeometry((float) d12, (float) d13, (float) d23, (float) r1, (float) r2, (float) r3);

                    runOnUiThread(() -> {
                        calibStatusText.setText("Mesh Complete! Spatial Matrix built.");
                        calibStatusText.setTextColor(Color.parseColor("#00E5FF"));
                        distanceReadoutText.setText(String.format(Locale.US, "Router Distances -> N1: %.1fm | N2: %.1fm | N3: %.1fm", r1, r2, r3));
                    });
                }
                return;
            } else if (clean.startsWith("MESH_FAIL")) {
                runOnUiThread(() -> {
                    calibStatusText.setText("Mesh Calibration Failed. Try again.");
                    calibStatusText.setTextColor(Color.RED);
                    startCalibBtn.setEnabled(true);
                    startCalibBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
                });
                return;
            }

            String[] p = clean.split(",");
            if (p.length < 7) return;

            int id = Integer.parseInt(p[0]) - 1;
            if (id >= 0 && id < 3) {
                nodes[id].rssi = Float.parseFloat(p[1]);
                nodes[id].snr = Float.parseFloat(p[2]);
                nodes[id].voltage = Float.parseFloat(p[3]);
                nodes[id].loss = Float.parseFloat(p[4]);
                nodes[id].jitter = Float.parseFloat(p[5]);
                nodes[id].latency = Float.parseFloat(p[6]);
                nodes[id].lastUpdate = System.currentTimeMillis();

                nodes[id].totalRssiSum += nodes[id].rssi;
                nodes[id].rssiCount++;
            }
        } catch (Exception ignored) {
        }
    }

    private void updateUI() {
        NodeData avg = getAverages();
        rssiText.setText(String.format(Locale.US, "Avg RSSI: %.1f dBm", avg.rssi));
        latencyText.setText(String.format(Locale.US, "Avg Ping: %.1f ms", avg.latency));
        packetLossText.setText(String.format(Locale.US, "Avg Loss: %.1f %%", avg.loss));

        float finalScore = 0;
        if (avg.rssi != 0) {
            float rssiScore = Math.max(0, Math.min(100, (avg.rssi + 85) * (100f / 45f)));
            finalScore = rssiScore - (avg.latency > 30 ? 15 : 0) - (avg.loss * 2);
            finalScore = Math.max(0, finalScore);
            qualityText.setText(String.format(Locale.US, "%.0f/100", finalScore));
        }

        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            boolean isOnline = (System.currentTimeMillis() - nodes[i].lastUpdate < 5000) && nodes[i].lastUpdate != 0;
            s.append("Node ").append(i + 1).append(": ");
            if (isOnline) {
                float runningAvgRssi = 0;
                if (nodes[i].rssiCount > 0) {
                    runningAvgRssi = (float) (nodes[i].totalRssiSum / nodes[i].rssiCount);
                }
                s.append("🟢 ")
                        .append(String.format(Locale.US, "%.0fms", nodes[i].latency))
                        .append("  |  🔋 ")
                        .append(String.format(Locale.US, "%.1fV", nodes[i].voltage))
                        .append("  |  Avg: ")
                        .append(String.format(Locale.US, "%.1f dBm", runningAvgRssi));
            } else {
                s.append("🔴 Offline");
            }
            if (i < 2) s.append("\n");
        }
        nodeStatusText.setText(s.toString());

        updateGraphs(avg);

        // Push live signals to engine
        heatmapView.updateLiveSignals(nodes[0].rssi, nodes[1].rssi, nodes[2].rssi);
    }

    private void updateGraphs(NodeData avg) {
        LineData rssiData = rssiChart.getData();
        LineData latencyData = latencyChart.getData();
        LineData packetData = packetChart.getData();
        int activeNodes = 0;

        for (int i = 0; i < 3; i++) {
            NodeData n = nodes[i];
            if (System.currentTimeMillis() - n.lastUpdate < 5000 && n.lastUpdate != 0) {
                rssiData.addEntry(new Entry(graphIndex, n.rssi), i);
                latencyData.addEntry(new Entry(graphIndex, n.latency), i);
                packetData.addEntry(new Entry(graphIndex, n.loss), i);
                activeNodes++;
            }
        }

        if (activeNodes > 0) {
            rssiData.addEntry(new Entry(graphIndex, avg.rssi), 3);
            latencyData.addEntry(new Entry(graphIndex, avg.latency), 3);
            packetData.addEntry(new Entry(graphIndex, avg.loss), 3);
            graphIndex++;

            rssiData.notifyDataChanged();
            latencyData.notifyDataChanged();
            packetData.notifyDataChanged();
            rssiChart.notifyDataSetChanged();
            latencyChart.notifyDataSetChanged();
            packetChart.notifyDataSetChanged();

            rssiChart.setVisibleXRangeMaximum(50f);
            latencyChart.setVisibleXRangeMaximum(50f);
            packetChart.setVisibleXRangeMaximum(50f);
            rssiChart.moveViewToX(graphIndex);
            latencyChart.moveViewToX(graphIndex);
            packetChart.moveViewToX(graphIndex);

            rssiChart.invalidate();
            latencyChart.invalidate();
            packetChart.invalidate();
        }
    }

    private NodeData getAverages() {
        NodeData a = new NodeData();
        int c = 0;
        for (NodeData n : nodes) {
            if (System.currentTimeMillis() - n.lastUpdate < 5000 && n.lastUpdate != 0) {
                a.rssi += n.rssi;
                a.snr += n.snr;
                a.latency += n.latency;
                a.jitter += n.jitter;
                a.loss += n.loss;
                c++;
            }
        }
        if (c > 0) {
            a.rssi /= c;
            a.snr /= c;
            a.latency /= c;
            a.jitter /= c;
            a.loss /= c;
        }
        return a;
    }

    private void setupChart(LineChart chart, float minY, float maxY) {
        chart.setData(new LineData());
        chart.getLegend().setTextColor(Color.WHITE);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.getAxisRight().setEnabled(false);
        chart.getXAxis().setDrawGridLines(false);
        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(Color.parseColor("#2C3A5A"));
        chart.getAxisLeft().setTextColor(Color.GRAY);
        chart.getAxisLeft().setAxisMinimum(minY);
        chart.getAxisLeft().setAxisMaximum(maxY);
        chart.setTouchEnabled(true);
        chart.setBackgroundColor(Color.parseColor("#1A2235"));
    }

    private void initChartData(LineChart chart) {
        LineData data = new LineData();
        int[] colors = {Color.RED, Color.GREEN, Color.YELLOW};
        for (int i = 0; i < 3; i++) {
            LineDataSet set = new LineDataSet(new ArrayList<>(), "Node " + (i + 1));
            set.setColor(colors[i]);
            set.setDrawCircles(false);
            set.setLineWidth(2f);
            data.addDataSet(set);
        }
        LineDataSet avg = new LineDataSet(new ArrayList<>(), "Average");
        avg.setColor(Color.WHITE);
        avg.setDrawCircles(false);
        avg.setLineWidth(3f);
        avg.enableDashedLine(10f, 5f, 0f);
        data.addDataSet(avg);
        chart.setData(data);
    }

    // =========================================================================
    // GPU ACCELERATED MESH ENGINE (SCREEN BLENDING & INTERACTIVITY)
    // =========================================================================

// =========================================================================
    // DROP-IN REPLACEMENT: HeatmapView inner class
    // Replace your entire existing HeatmapView class with this one.
    // =========================================================================

    class HeatmapView extends View {


        // Heatmap Grid variables
// True Heatmap Grid Engine Variables
        private android.graphics.Bitmap heatMapBitmap;
        private int[] heatPixels;
        private final int GRID_RES_X = 45; // Low-res calculation grid, GPU stretches it smooth


        // --- Paints ---
        Paint textPaint, linePaint, heatPaint, contourPaint;
        Paint optimalRingPaint, optimalLinePaint;

        // --- Geometry ---
        float d12 = 1f, d13 = 1f, d23 = 1f, r1 = 0f, r2 = 0f, r3 = 0f;
        float lastGood_d12 = 1f, lastGood_d13 = 1f, lastGood_d23 = 1f;

        // --- Live RSSI ---
        float liveRssi1 = -65f, liveRssi2 = -72f, liveRssi3 = -80f;

        // --- Touch / Pan / Zoom ---
        Matrix transformMatrix = new Matrix();
        ScaleGestureDetector scaleDetector;
        GestureDetector gestureDetector;
        boolean isInitialCenterDone = false;

        // --- Animation ticker ---
        private long animStartTime = System.currentTimeMillis();

        public HeatmapView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_HARDWARE, null);

            heatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            heatPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));

            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.argb(160, 100, 180, 255));
            linePaint.setStrokeWidth(3f);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{18f, 10f}, 0f));

            contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            contourPaint.setStyle(Paint.Style.STROKE);
            contourPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6f, 10f}, 0f));

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(40f);
            textPaint.setFakeBoldText(true);
            textPaint.setShadowLayer(8f, 0f, 2f, Color.BLACK);

            optimalRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            optimalRingPaint.setColor(Color.CYAN);
            optimalRingPaint.setStyle(Paint.Style.STROKE);
            optimalRingPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 12f}, 0f));

            optimalLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            optimalLinePaint.setColor(Color.argb(130, 0, 220, 255));
            optimalLinePaint.setStyle(Paint.Style.STROKE);
            optimalLinePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8f, 10f}, 0f));

            setupTouchListeners(context);
        }

        private void setupTouchListeners(Context context) {
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float sf = detector.getScaleFactor();
                            transformMatrix.postScale(sf, sf, detector.getFocusX(), detector.getFocusY());
                            invalidate();
                            return true;
                        }
                    });

            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                            transformMatrix.postTranslate(-dX, -dY);
                            invalidate();
                            return true;
                        }
                    });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }

        public void updateLiveSignals(float rssi1, float rssi2, float rssi3) {
            this.liveRssi1 = rssi1;
            this.liveRssi2 = rssi2;
            this.liveRssi3 = rssi3;
            invalidate();
        }

        public void updateMeshGeometry(float d12, float d13, float d23, float r1, float r2, float r3) {
            // 1. Enforce minimum physical distance so nodes aren't inside each other
            this.d12 = Math.max(0.1f, d12);
            this.d13 = Math.max(0.1f, d13);
            this.d23 = Math.max(0.1f, d23);

            // 2. THE PHYSICS ENFORCER — Proportional Stretching
            // If RF noise makes the triangle impossible, dynamically stretch the short legs
            // until they connect, guaranteeing we always have a 2D mesh plane.
            if (this.d12 > this.d13 + this.d23) {
                float scale = this.d12 / (this.d13 + this.d23) * 1.01f; // 1% buffer to force a 2D peak
                this.d13 *= scale;
                this.d23 *= scale;
            } else if (this.d13 > this.d12 + this.d23) {
                float scale = this.d13 / (this.d12 + this.d23) * 1.01f;
                this.d12 *= scale;
                this.d23 *= scale;
            } else if (this.d23 > this.d12 + this.d13) {
                float scale = this.d23 / (this.d12 + this.d13) * 1.01f;
                this.d12 *= scale;
                this.d13 *= scale;
            }

            this.r1 = r1;
            this.r2 = r2;
            this.r3 = r3;
            invalidate();
        }


        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth(), height = getHeight();
            if (width == 0 || height == 0 || (r1 == 0 && r2 == 0 && r3 == 0)) return;

            canvas.drawColor(Color.parseColor("#1A0000"));

            final float PPM = 150f;

            // 1. Mesh Physics
            float safe_d12 = Math.max(0.1f, d12);
            float safe_d13 = Math.max(0.1f, d13);
            float safe_d23 = Math.max(0.1f, d23);

            if (safe_d12 > safe_d13 + safe_d23) {
                float scale = safe_d12 / (safe_d13 + safe_d23) * 1.05f;
                safe_d13 *= scale; safe_d23 *= scale;
            } else if (safe_d13 > safe_d12 + safe_d23) {
                float scale = safe_d13 / (safe_d12 + safe_d23) * 1.05f;
                safe_d12 *= scale; safe_d23 *= scale;
            } else if (safe_d23 > safe_d12 + safe_d13) {
                float scale = safe_d23 / (safe_d12 + safe_d13) * 1.05f;
                safe_d12 *= scale; safe_d13 *= scale;
            }

            float m_n1x = 0f, m_n1y = 0f;
            float m_n2x = safe_d12, m_n2y = 0f;
            float m_lx3 = (safe_d12 * safe_d12 + safe_d13 * safe_d13 - safe_d23 * safe_d23) / (2 * safe_d12);
            float inner = safe_d13 * safe_d13 - m_lx3 * m_lx3;
            float m_n3y = inner > 0 ? (float) Math.sqrt(inner) : 0.1f;
            float m_n3x = m_lx3;

            float[] rPosMeters = estimateRouterPosition(m_n1x, m_n1y, m_n2x, m_n2y, m_n3x, m_n3y);
            float m_rx = rPosMeters[0];
            float m_ry = rPosMeters[1];

            float n1x = m_n1x * PPM, n1y = m_n1y * PPM;
            float n2x = m_n2x * PPM, n2y = m_n2y * PPM;
            float n3x = m_n3x * PPM, n3y = m_n3y * PPM;
            float rx = m_rx * PPM, ry = m_ry * PPM;

            float optX = (n1x + n2x + n3x) / 3f;
            float optY = (n1y + n2y + n3y) / 3f;

            if (!isInitialCenterDone) {
                float cx = (n1x + n2x + n3x) / 3f;
                float cy = (n1y + n2y + n3y) / 3f;
                transformMatrix.postTranslate((width / 2f) - cx, (height / 2f) - cy);
                isInitialCenterDone = true;
            }

            float[] vals = new float[9];
            transformMatrix.getValues(vals);
            float currentScale = vals[Matrix.MSCALE_X];
            if (currentScale < 0.01f) currentScale = 1f;

            float pulse = (float) (0.5 + 0.5 * Math.sin(((System.currentTimeMillis() - animStartTime) / 1000f) * Math.PI));

            canvas.save();
            canvas.concat(transformMatrix);

            // =========================================================================
            // 2. TRUE ENVIRONMENTAL FLUID HEATMAP (Massive 160m Virtual Floor)
            // =========================================================================

            // Create a 160x160 physical meter drawing area, shifted back by 80 meters
            // This is so massive it guarantees no node can ever fall outside the painted area
            float physicalAreaMeters = 160f;
            float drawStartX = -(physicalAreaMeters / 2f) * PPM;
            float drawStartY = -(physicalAreaMeters / 2f) * PPM;
            float drawWidth = physicalAreaMeters * PPM;
            float drawHeight = physicalAreaMeters * PPM;

            int GRID_RES = 60; // Slightly higher res grid for the massive area

            if (heatMapBitmap == null || heatMapBitmap.getWidth() != GRID_RES || heatMapBitmap.getHeight() != GRID_RES) {
                heatMapBitmap = Bitmap.createBitmap(GRID_RES, GRID_RES, Bitmap.Config.ARGB_8888);
                heatPixels = new int[GRID_RES * GRID_RES];
            }

            float stepX = drawWidth / GRID_RES;
            float stepY = drawHeight / GRID_RES;

            // LOWERED EXPONENT: 10f instead of 15f.
            // This causes the signal to decay much slower, creating huge green and yellow zones.
            float pathLossExp = 10f;

            float expectedAtN1 = -35f - (pathLossExp * (float) Math.log10(Math.max(0.1f, r1)));
            float expectedAtN2 = -35f - (pathLossExp * (float) Math.log10(Math.max(0.1f, r2)));
            float expectedAtN3 = -35f - (pathLossExp * (float) Math.log10(Math.max(0.1f, r3)));

            float warp1 = liveRssi1 - expectedAtN1;
            float warp2 = liveRssi2 - expectedAtN2;
            float warp3 = liveRssi3 - expectedAtN3;

            for (int i = 0; i < heatPixels.length; i++) {
                int gridX = i % GRID_RES;
                int gridY = i / GRID_RES;

                float px = drawStartX + (gridX * stepX);
                float py = drawStartY + (gridY * stepY);

                float dRouter = Math.max(0.1f, (float) Math.sqrt(Math.pow(px - rx, 2) + Math.pow(py - ry, 2)) / PPM);
                float d1 = Math.max(0.1f, (float) Math.sqrt(Math.pow(px - n1x, 2) + Math.pow(py - n1y, 2)) / PPM);
                float d2 = Math.max(0.1f, (float) Math.sqrt(Math.pow(px - n2x, 2) + Math.pow(py - n2y, 2)) / PPM);
                float d3 = Math.max(0.1f, (float) Math.sqrt(Math.pow(px - n3x, 2) + Math.pow(py - n3y, 2)) / PPM);

                float theoreticalRssi = -35f - (pathLossExp * (float) Math.log10(dRouter));

                float w1 = 1f / (d1 * d1);
                float w2 = 1f / (d2 * d2);
                float w3 = 1f / (d3 * d3);
                float totalWeight = w1 + w2 + w3;

                float interpolatedWarp = (warp1 * w1 + warp2 * w2 + warp3 * w3) / totalWeight;
                float finalRssi = theoreticalRssi + interpolatedWarp;

                // Apply RAG Palette with 200 alpha to blend slightly with the background void
                heatPixels[i] = getColorForRssi(finalRssi, 200);
            }

            heatMapBitmap.setPixels(heatPixels, 0, GRID_RES, 0, 0, GRID_RES, GRID_RES);

            Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(heatMapBitmap, null, new android.graphics.RectF(drawStartX, drawStartY, drawStartX + drawWidth, drawStartY + drawHeight), bmpPaint);
            // =========================================================================

            // 3. OVERLAYS & UI ELEMENTS
            float[] ringMeters = {2.0f, 5.0f, 10.0f};
            int[] ringAlpha = {100, 60, 30};
            for (int i = 0; i < ringMeters.length; i++) {
                contourPaint.setColor(Color.argb(ringAlpha[i], 255, 255, 255));
                contourPaint.setStrokeWidth(2.5f / currentScale);
                canvas.drawCircle(rx, ry, ringMeters[i] * PPM, contourPaint);
            }

            linePaint.setStrokeWidth(4f / currentScale);
            Path tri = new Path();
            tri.moveTo(n1x, n1y);
            tri.lineTo(n2x, n2y);
            tri.lineTo(n3x, n3y);
            tri.close();
            canvas.drawPath(tri, linePaint);

            optimalLinePaint.setStrokeWidth(3f / currentScale);
            canvas.drawLine(rx, ry, optX, optY, optimalLinePaint);

            drawGlowingNode(canvas, n1x, n1y, liveRssi1, "N1", currentScale, pulse);
            drawGlowingNode(canvas, n2x, n2y, liveRssi2, "N2", currentScale, pulse);
            drawGlowingNode(canvas, n3x, n3y, liveRssi3, "N3", currentScale, pulse);

            drawRouter(canvas, rx, ry, currentScale, pulse);
            drawTarget(canvas, optX, optY, currentScale, pulse);

            canvas.restore();
            postInvalidateDelayed(32);
        }

        // --- DRAWING HELPERS ---

        private void drawNodePull(Canvas canvas, float nx, float ny, float rssi, float radius) {
            // Get the color for this node's actual signal strength, but softer (160 alpha)
            int nodeColor = getColorForRssi(rssi, 160);
            int midColor = Color.argb(60, Color.red(nodeColor), Color.green(nodeColor), Color.blue(nodeColor));

            int[] pullColors = {nodeColor, midColor, Color.TRANSPARENT};
            float[] pullStops = {0f, 0.4f, 1.0f};

            heatPaint.setShader(new RadialGradient(nx, ny, radius, pullColors, pullStops, Shader.TileMode.CLAMP));
            canvas.drawCircle(nx, ny, radius, heatPaint);
        }

        private void drawCleanNode(Canvas canvas, float cx, float cy, float rssi, String label, float scale) {
            int peakColor = getColorForRssi(rssi, 255);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, 14f / scale, p);

            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(peakColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5f / scale);
            canvas.drawCircle(cx, cy, 22f / scale, ring);

            textPaint.setTextSize(36f / scale);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(label, cx - (18f / scale), cy - (35f / scale), textPaint);
        }

        private void drawRouter(Canvas canvas, float rx, float ry, float scale, float pulse) {
            final int GREEN = Color.parseColor("#00FF96");
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);

            p.setColor(Color.WHITE);
            canvas.drawCircle(rx, ry, 16f / scale, p);

            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(GREEN);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(8f / scale);
            canvas.drawCircle(rx, ry, (25f + pulse * 10f) / scale, ring);

            textPaint.setTextSize(34f / scale);
            textPaint.setColor(GREEN);
            canvas.drawText("ROUTER", rx - (52f / scale), ry - (50f / scale), textPaint);
        }

        private void drawGlowingNode(Canvas canvas, float cx, float cy, float rssi, String label, float scale, float pulse) {
            int peakColor = getColorForRssi(rssi, 255);
            int r = Color.red(peakColor), g = Color.green(peakColor), b = Color.blue(peakColor);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);

            // Outer soft glow — pulses gently
            int outerAlpha = (int) (20 + pulse * 18);
            p.setColor(Color.argb(outerAlpha, r, g, b));
            canvas.drawCircle(cx, cy, 70f / scale, p);

            // Mid glow
            p.setColor(Color.argb(55, r, g, b));
            canvas.drawCircle(cx, cy, 44f / scale, p);

            // Inner glow
            p.setColor(Color.argb(100, r, g, b));
            canvas.drawCircle(cx, cy, 26f / scale, p);

            // Solid white core
            p.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, 14f / scale, p);

            // Colored ring
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(peakColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4f / scale);
            canvas.drawCircle(cx, cy, 22f / scale, ring);

            // Node label above
            textPaint.setTextSize(38f / scale);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(label, cx - (18f / scale), cy - (35f / scale), textPaint);

            // RSSI value below in the node's signal color
            textPaint.setTextSize(26f / scale);
            textPaint.setColor(peakColor);
            canvas.drawText(String.format(Locale.US, "%.0fdBm", rssi), cx - (36f / scale), cy + (52f / scale), textPaint);
        }

        private void drawTarget(Canvas canvas, float optX, float optY, float scale, float pulse) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.CYAN);
            canvas.drawCircle(optX, optY, 8f / scale, p);

            optimalRingPaint.setStrokeWidth(4f / scale);
            optimalRingPaint.setColor(Color.CYAN);
            canvas.drawCircle(optX, optY, 30f / scale, optimalRingPaint);

            textPaint.setTextSize(30f / scale);
            textPaint.setColor(Color.CYAN);
            canvas.drawText("TARGET", optX - (50f / scale), optY - (45f / scale), textPaint);
        }

        // --- MATH UTILS ---

        public float[] estimateRouterPosition(float m_n1x, float m_n1y, float m_n2x, float m_n2y, float m_n3x, float m_n3y) {

            // 1. MATCH THE PATH LOSS FORMULA TO YOUR TEXT READOUT
            // This ensures the visual map and the text distance readout are perfectly synced
            float rr1 = liveRssi1 <= -90 ? 20.0f : (float) Math.pow(10, (-46.5f - liveRssi1) / 31.9f);
            float rr2 = liveRssi2 <= -90 ? 20.0f : (float) Math.pow(10, (-46.5f - liveRssi2) / 31.9f);
            float rr3 = liveRssi3 <= -90 ? 20.0f : (float) Math.pow(10, (-46.5f - liveRssi3) / 31.9f);

            // 2. ITERATIVE LEAST SQUARES SOLVER
            // Start the router dead center in the mesh
            float rx = (m_n1x + m_n2x + m_n3x) / 3f;
            float ry = (m_n1y + m_n2y + m_n3y) / 3f;

            float learningRate = 0.05f;

            // Run 50 quick simulation steps to let the "rubber bands" pull the router into place
            for (int i = 0; i < 50; i++) {
                // Current guessed distances
                float d1 = (float) Math.sqrt(Math.pow(rx - m_n1x, 2) + Math.pow(ry - m_n1y, 2));
                float d2 = (float) Math.sqrt(Math.pow(rx - m_n2x, 2) + Math.pow(ry - m_n2y, 2));
                float d3 = (float) Math.sqrt(Math.pow(rx - m_n3x, 2) + Math.pow(ry - m_n3y, 2));

                // Prevent division by zero
                d1 = Math.max(0.01f, d1);
                d2 = Math.max(0.01f, d2);
                d3 = Math.max(0.01f, d3);

                // Error = Actual physical distance - Target calculated radius
                float err1 = d1 - rr1;
                float err2 = d2 - rr2;
                float err3 = d3 - rr3;

                // Move the X/Y coordinates toward the point of least tension
                rx -= learningRate * ((err1 / d1) * (rx - m_n1x) + (err2 / d2) * (rx - m_n2x) + (err3 / d3) * (rx - m_n3x));
                ry -= learningRate * ((err1 / d1) * (ry - m_n1y) + (err2 / d2) * (ry - m_n2y) + (err3 / d3) * (ry - m_n3y));
            }

            return new float[]{rx, ry};
        }

        private int getColorForRssi(float rssi, int alpha) {
            // Anything stronger than -55dBm is now PURE GREEN
            float min = -90f, max = -55f;
            float t = (rssi - min) / (max - min);
            t = Math.max(0f, Math.min(1f, t));

            int[] palette = {
                    Color.parseColor("#1A0000"),   // -90 (Dark Red Void)
                    Color.parseColor("#FF0000"),   // -81 (Red)
                    Color.parseColor("#FF8800"),   // -72 (Orange)
                    Color.parseColor("#FFFF00"),   // -63 (Yellow)
                    Color.parseColor("#00FF00")    // -55 and above (Pure Green)
            };

            float scaled = t * (palette.length - 1);
            int idx = (int) scaled;
            float frac = scaled - idx;

            if (idx >= palette.length - 1) {
                int c = palette[palette.length - 1];
                return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
            }

            int ca = palette[idx], cb = palette[idx + 1];
            int r = (int) (Color.red(ca) + frac * (Color.red(cb) - Color.red(ca)));
            int g = (int) (Color.green(ca) + frac * (Color.green(cb) - Color.green(ca)));
            int b = (int) (Color.blue(ca) + frac * (Color.blue(cb) - Color.blue(ca)));
            return Color.argb(alpha, r, g, b);
        }
    }
}
// =========================================================================
// END OF REPLACEMENT CLASS
// =========================================================================}
