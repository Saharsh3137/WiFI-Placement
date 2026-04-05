package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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

        setupChart(rssiChart, -100f, -15f); initChartData(rssiChart);
        setupChart(latencyChart, 0f, 150f); initChartData(latencyChart);
        setupChart(packetChart, 0f, 20f); initChartData(packetChart);

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
                Toast.makeText(MainActivity.this, "Connect to hardware first!", Toast.LENGTH_SHORT).show(); return;
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
                uiHandler.postDelayed(this, 400);
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
                else if (sec > 8) calibStatusText.setText("Mapping Node 2 Network... (" + sec + "s)");
                else if (sec > 3) calibStatusText.setText("Mapping Node 3 Network... (" + sec + "s)");
                else calibStatusText.setText("Triangulating Router... (" + sec + "s)");

                calibStatusText.setTextColor(Color.parseColor("#FFD740"));

                if (sec == 17 && !demoModeSwitch.isChecked()) {
                    try {
                        outputStream.write("CALIBRATE\n".getBytes());
                        outputStream.flush();
                    } catch (Exception e) {}
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
        rssiChart.clear(); initChartData(rssiChart);
        latencyChart.clear(); initChartData(latencyChart);
        packetChart.clear(); initChartData(packetChart);
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
                        for(int i = 0; i < 3; i++) {
                            demoRssi[i] += (random.nextFloat() - 0.5f) * 4f;
                            demoRssi[i] = Math.max(-95f, Math.min(-35f, demoRssi[i]));
                            demoLatency[i] += (random.nextFloat() - 0.5f) * 5f;
                            demoLatency[i] = Math.max(5f, Math.min(120f, demoLatency[i]));

                            int s = 25;
                            float v = 3.6f + (random.nextFloat() * 0.2f);
                            float l = random.nextFloat() < 0.05 ? 2.0f : 0.0f;
                            int j = random.nextInt(5);

                            String fakeData = String.format(Locale.US, "%d,%d,%d,%.2f,%.2f,%d,%d\n",
                                    i + 1, (int)demoRssi[i], s, v, l, j, (int)demoLatency[i]);
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
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
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
            Toast.makeText(this, "SSID and Pass cannot be empty", Toast.LENGTH_SHORT).show(); return;
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

                    heatmapView.updateMeshGeometry((float)d12, (float)d13, (float)d23, (float)r1, (float)r2, (float)r3);

                    runOnUiThread(() -> {
                        calibStatusText.setText("Mesh Complete! Spatial Matrix built.");
                        calibStatusText.setTextColor(Color.parseColor("#00E5FF"));
                        distanceReadoutText.setText(String.format(Locale.US, "Router Distances -> N1: %.1fm | N2: %.1fm | N3: %.1fm", r1, r2, r3));
                    });
                }
                return;
            }
            else if (clean.startsWith("MESH_FAIL")) {
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
            if(id >= 0 && id < 3) {
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
        } catch (Exception ignored) {}
    }

    private void updateUI() {
        NodeData avg = getAverages();
        rssiText.setText(String.format(Locale.US, "Avg RSSI: %.1f dBm", avg.rssi));
        latencyText.setText(String.format(Locale.US, "Avg Ping: %.1f ms", avg.latency));
        packetLossText.setText(String.format(Locale.US, "Avg Loss: %.1f %%", avg.loss));

        float finalScore = 0;
        if(avg.rssi != 0) {
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
                if (nodes[i].rssiCount > 0) { runningAvgRssi = (float) (nodes[i].totalRssiSum / nodes[i].rssiCount); }
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

            rssiData.notifyDataChanged(); latencyData.notifyDataChanged(); packetData.notifyDataChanged();
            rssiChart.notifyDataSetChanged(); latencyChart.notifyDataSetChanged(); packetChart.notifyDataSetChanged();

            rssiChart.setVisibleXRangeMaximum(50f); latencyChart.setVisibleXRangeMaximum(50f); packetChart.setVisibleXRangeMaximum(50f);
            rssiChart.moveViewToX(graphIndex); latencyChart.moveViewToX(graphIndex); packetChart.moveViewToX(graphIndex);

            rssiChart.invalidate(); latencyChart.invalidate(); packetChart.invalidate();
        }
    }

    private NodeData getAverages() {
        NodeData a = new NodeData();
        int c = 0;
        for (NodeData n : nodes) {
            if (System.currentTimeMillis() - n.lastUpdate < 5000 && n.lastUpdate != 0) {
                a.rssi += n.rssi; a.snr += n.snr; a.latency += n.latency;
                a.jitter += n.jitter; a.loss += n.loss; c++;
            }
        }
        if (c > 0) {
            a.rssi /= c; a.snr /= c; a.latency /= c; a.jitter /= c; a.loss /= c;
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
            set.setColor(colors[i]); set.setDrawCircles(false); set.setLineWidth(2f);
            data.addDataSet(set);
        }
        LineDataSet avg = new LineDataSet(new ArrayList<>(), "Average");
        avg.setColor(Color.WHITE); avg.setDrawCircles(false); avg.setLineWidth(3f); avg.enableDashedLine(10f, 5f, 0f);
        data.addDataSet(avg);
        chart.setData(data);
    }

    // =========================================================================
    // GPU ACCELERATED MESH ENGINE (SCREEN BLENDING & INTERACTIVITY)
    // =========================================================================
    class HeatmapView extends View {

        private Paint nodeDotPaint, textPaint, linePaint;
        private Paint routerDotPaint, routerRingPaint;
        private Paint heatCellPaint, legendTextPaint, legendBarPaint, gridPaint;

        private float d12 = 1f, d13 = 1f, d23 = 1f, r1 = 0f, r2 = 0f, r3 = 0f;
        private float lastGood_d12 = 1f, lastGood_d13 = 1f, lastGood_d23 = 1f;

        private float liveRssi1 = -99f, liveRssi2 = -99f, liveRssi3 = -99f;

        private Matrix transformMatrix = new Matrix();
        private ScaleGestureDetector scaleDetector;
        private GestureDetector gestureDetector;
        private boolean isInitialCenterDone = false;

        // Router smoothing
        private float smoothRx = 0f, smoothRy = 0f;
        private boolean routerInitialized = false;

        public HeatmapView(Context context) {
            super(context);

            nodeDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            nodeDotPaint.setColor(Color.WHITE);
            nodeDotPaint.setStyle(Paint.Style.FILL);

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(28f);
            textPaint.setFakeBoldText(true);

            linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.argb(160, 180, 180, 180));
            linePaint.setStrokeWidth(3f);
            linePaint.setStyle(Paint.Style.STROKE);

            routerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            routerDotPaint.setColor(Color.WHITE);
            routerDotPaint.setStyle(Paint.Style.FILL);

            routerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            routerRingPaint.setColor(Color.parseColor("#00FF66"));
            routerRingPaint.setStyle(Paint.Style.STROKE);
            routerRingPaint.setStrokeWidth(4f);

            heatCellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            heatCellPaint.setMaskFilter(new BlurMaskFilter(25, BlurMaskFilter.Blur.NORMAL));

            legendTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            legendTextPaint.setColor(Color.WHITE);
            legendTextPaint.setTextSize(24f);
            legendTextPaint.setFakeBoldText(true);

            legendBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            legendBarPaint.setStyle(Paint.Style.FILL);

            gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridPaint.setColor(Color.argb(35, 255, 255, 255));
            gridPaint.setStrokeWidth(1f);

            setupTouchListeners(context);
        }

        private void setupTouchListeners(Context context) {
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float scaleFactor = detector.getScaleFactor();
                            transformMatrix.postScale(scaleFactor, scaleFactor,
                                    detector.getFocusX(), detector.getFocusY());
                            postInvalidateOnAnimation();
                            return true;
                        }
                    });

            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                float distanceX, float distanceY) {
                            transformMatrix.postTranslate(-distanceX, -distanceY);
                            postInvalidateOnAnimation();
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
            postInvalidateOnAnimation();
        }

        public void updateMeshGeometry(float d12, float d13, float d23, float r1, float r2, float r3) {
            d12 = Math.max(0.1f, d12);
            d13 = Math.max(0.1f, d13);
            d23 = Math.max(0.1f, d23);

            if ((d12 + d13 > d23) && (d12 + d23 > d13) && (d13 + d23 > d12)) {
                lastGood_d12 = d12;
                lastGood_d13 = d13;
                lastGood_d23 = d23;
                this.d12 = d12;
                this.d13 = d13;
                this.d23 = d23;
            } else {
                this.d12 = lastGood_d12;
                this.d13 = lastGood_d13;
                this.d23 = lastGood_d23;
            }

            this.r1 = r1;
            this.r2 = r2;
            this.r3 = r3;

            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            if (width == 0 || height == 0) return;

            canvas.drawColor(Color.parseColor("#0B0F1A"));

            if (r1 == 0f && r2 == 0f && r3 == 0f) {
                drawLegend(canvas, width, height);
                return;
            }

            float ppm = 70f;

            // Logical node layout
            float n1x = 0f, n1y = 0f;
            float n2x = d12 * ppm, n2y = 0f;

            float logicalX3 = (d12 * d12 + d13 * d13 - d23 * d23) / (2f * d12);
            float inner = d13 * d13 - logicalX3 * logicalX3;
            float logicalY3 = inner > 0 ? (float) Math.sqrt(inner) : 0f;

            float n3x = logicalX3 * ppm;
            float n3y = logicalY3 * ppm;

            // Hybrid router distances
            float liveD1 = rssiToDistance(liveRssi1);
            float liveD2 = rssiToDistance(liveRssi2);
            float liveD3 = rssiToDistance(liveRssi3);

            float finalR1 = 0.75f * r1 + 0.25f * liveD1;
            float finalR2 = 0.75f * r2 + 0.25f * liveD2;
            float finalR3 = 0.75f * r3 + 0.25f * liveD3;

            float[] routerPos = trilaterate(n1x, n1y, n2x, n2y, n3x, n3y, finalR1, finalR2, finalR3);
            float rx = routerPos[0];
            float ry = routerPos[1];

            // EMA smoothing
            float alpha = 0.15f;
            if (!routerInitialized) {
                smoothRx = rx;
                smoothRy = ry;
                routerInitialized = true;
            } else {
                smoothRx = alpha * rx + (1f - alpha) * smoothRx;
                smoothRy = alpha * ry + (1f - alpha) * smoothRy;
            }
            rx = smoothRx;
            ry = smoothRy;

            // Initial centering
            if (!isInitialCenterDone) {
                float centerX = (n1x + n2x + n3x + rx) / 4f;
                float centerY = (n1y + n2y + n3y + ry) / 4f;
                transformMatrix.postTranslate((width / 2f) - centerX, (height / 2f) - centerY);
                isInitialCenterDone = true;
            }

            canvas.save();
            canvas.concat(transformMatrix);

            // Draw subtle grid
            drawGrid(canvas, width, height);

            // Smooth heatmap
            drawSmoothHeatmap(canvas, width, height, n1x, n1y, n2x, n2y, n3x, n3y, rx, ry);

            // Geometry triangle
            Path path = new Path();
            path.moveTo(n1x, n1y);
            path.lineTo(n2x, n2y);
            path.lineTo(n3x, n3y);
            path.close();
            canvas.drawPath(path, linePaint);

            // Scale compensation
            float[] values = new float[9];
            transformMatrix.getValues(values);
            float currentScale = values[Matrix.MSCALE_X];
            if (currentScale == 0f) currentScale = 1f;

            float nodeRadius = 10f / currentScale;
            float routerDotRadius = 9f / currentScale;
            float routerRingRadius = 18f / currentScale;

            textPaint.setTextSize(22f / currentScale);
            linePaint.setStrokeWidth(2f / currentScale);
            routerRingPaint.setStrokeWidth(3f / currentScale);

            // Nodes
            canvas.drawCircle(n1x, n1y, nodeRadius, nodeDotPaint);
            canvas.drawCircle(n2x, n2y, nodeRadius, nodeDotPaint);
            canvas.drawCircle(n3x, n3y, nodeRadius, nodeDotPaint);

            canvas.drawText("N1", n1x - 18f / currentScale, n1y - 14f / currentScale, textPaint);
            canvas.drawText("N2", n2x - 18f / currentScale, n2y - 14f / currentScale, textPaint);
            canvas.drawText("N3", n3x - 18f / currentScale, n3y - 14f / currentScale, textPaint);

            // Router
            canvas.drawCircle(rx, ry, routerDotRadius, routerDotPaint);
            canvas.drawCircle(rx, ry, routerRingRadius, routerRingPaint);
            canvas.drawText("ROUTER", rx - 42f / currentScale, ry - 18f / currentScale, textPaint);

            canvas.restore();

            // Fixed legend on screen
            drawLegend(canvas, width, height);
        }

        private void drawSmoothHeatmap(Canvas canvas, int viewWidth, int viewHeight,
                                       float n1x, float n1y, float n2x, float n2y,
                                       float n3x, float n3y, float rx, float ry) {

            float[] inverse = new float[9];
            Matrix inverseMatrix = new Matrix();
            transformMatrix.invert(inverseMatrix);
            inverseMatrix.getValues(inverse);

            float[] values = new float[9];
            transformMatrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];

            int cellSize = (int)(18 / scale);  // 🔥 dynamic resolution
            cellSize = Math.max(6, Math.min(cellSize, 25));
            int alpha = 150;

            for (int sx = 0; sx < viewWidth; sx += cellSize) {
                for (int sy = 0; sy < viewHeight; sy += cellSize) {

                    float[] pts = {
                            sx, sy,
                            sx + cellSize, sy,
                            sx, sy + cellSize,
                            sx + cellSize, sy + cellSize,
                            sx + cellSize / 2f, sy + cellSize / 2f
                    };

                    inverseMatrix.mapPoints(pts);

                    float total = 0f;

                    for (int i = 0; i < pts.length; i += 2) {
                        float wx = pts[i];
                        float wy = pts[i + 1];
                        // slight router contribution for realism
                        float dr = distance(wx, wy, rx, ry);
// Router should dominate (WiFi source)
                        float sr = 6.5f / (1f + 0.0005f * dr * dr);
                        total += sr;
                    }

                    total /= 5f;

                    int color = getHeatmapColor(total, alpha);
                    float radius = cellSize * 4.5f;
                    heatCellPaint.setColor(color);
                    canvas.drawCircle(pts[8], pts[9], radius, heatCellPaint);
                }
            }
        }

        private void drawGrid(Canvas canvas, int width, int height) {
            int grid = 1000; // large because drawing in transformed world coordinates
            for (int x = -4000; x <= 4000; x += grid) {
                canvas.drawLine(x, -4000, x, 4000, gridPaint);
            }
            for (int y = -4000; y <= 4000; y += grid) {
                canvas.drawLine(-4000, y, 4000, y, gridPaint);
            }
        }

        private void drawLegend(Canvas canvas, int width, int height) {
            float boxLeft = 24f;
            float boxTop = height - 150f;
            float boxRight = width - 24f;
            float boxBottom = height - 55f;

            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.argb(180, 20, 28, 45));
            canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 18f, 18f, bgPaint);

            legendTextPaint.setTextSize(24f);
            canvas.drawText("Signal Strength", boxLeft + 20f, boxTop + 28f, legendTextPaint);

            float barLeft = boxLeft + 20f;
            float barTop = boxTop + 40f;
            float barRight = boxRight - 20f;
            float barBottom = boxTop + 70f;

            LinearGradient gradient = new LinearGradient(
                    barLeft, barTop, barRight, barTop,
                    new int[]{
                            Color.parseColor("#8B0000"), // weak
                            Color.RED,
                            Color.parseColor("#FF8C00"),
                            Color.YELLOW,
                            Color.parseColor("#7CFC00"),
                            Color.parseColor("#006400")  // strong
                    },
                    null,
                    Shader.TileMode.CLAMP
            );
            legendBarPaint.setShader(gradient);
            canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 12f, 12f, legendBarPaint);
            legendBarPaint.setShader(null);

            Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
            smallText.setColor(Color.WHITE);
            smallText.setTextSize(20f);

            canvas.drawText("Weak", barLeft, barBottom + 22f, smallText);
            canvas.drawText("Medium", (barLeft + barRight) / 2f - 35f, barBottom + 22f, smallText);
            canvas.drawText("Strong", barRight - 55f, barBottom + 22f, smallText);
        }

        private float[] trilaterate(float x1, float y1, float x2, float y2, float x3, float y3,
                                    float r1, float r2, float r3) {

            float A = 2f * (x2 - x1);
            float B = 2f * (y2 - y1);
            float C = r1 * r1 - r2 * r2 - x1 * x1 + x2 * x2 - y1 * y1 + y2 * y2;

            float D = 2f * (x3 - x1);
            float E = 2f * (y3 - y1);
            float F = r1 * r1 - r3 * r3 - x1 * x1 + x3 * x3 - y1 * y1 + y3 * y3;

            float denom = (A * E - B * D);

            if (Math.abs(denom) < 1e-6f) {
                return new float[]{(x1 + x2 + x3) / 3f, (y1 + y2 + y3) / 3f};
            }

            float x = (C * E - B * F) / denom;
            float y = (A * F - C * D) / denom;
            return new float[]{x, y};
        }

        private float rssiToDistance(float rssi) {
            float A = -40f;
            float n = 2.5f;
            return (float) Math.pow(10f, (A - rssi) / (10f * n));
        }

        private float distance(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private float signalStrength(float d, float rssi) {

            d = Math.max(d, 1f);

            // Normalize RSSI (-100 to -30 → 0 to 1)
            float base = (rssi + 100f) / 70f;
            base = Math.max(0.05f, Math.min(1f, base));

            // Better decay (slower near source, faster far away)
            return base / (1f + 0.0005f * d * d);
        }
        private int getHeatmapColor(float value, int alpha) {
            float v = (float) Math.pow(Math.min(1f, value * 2.8f), 0.15);

            int[] colors = new int[]{
                    Color.parseColor("#8B0000"), // dark red
                    Color.RED,
                    Color.parseColor("#FF8C00"), // orange
                    Color.YELLOW,
                    Color.parseColor("#7CFC00"), // light green
                    Color.parseColor("#006400")  // dark green
            };

            float scaled = v * (colors.length - 1);
            int index = (int) scaled;
            float fraction = scaled - index;

            if (index >= colors.length - 1) {
                int c = colors[colors.length - 1];
                return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
            }

            int c1 = colors[index];
            int c2 = colors[index + 1];

            int r = (int) (Color.red(c1) + fraction * (Color.red(c2) - Color.red(c1)));
            int g = (int) (Color.green(c1) + fraction * (Color.green(c2) - Color.green(c1)));
            int b = (int) (Color.blue(c1) + fraction * (Color.blue(c2) - Color.blue(c1)));

            return Color.argb(alpha, r, g, b);
        }
    }
}