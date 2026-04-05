package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.*;
import android.content.Context;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Random;
import java.util.Locale;
import java.util.ArrayList;

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

    // Demo Mode Physics
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

        // Bind Dynamic Mesh UI
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

        // Listeners
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

        // --- TRIGGER DYNAMIC MESH SEQUENCE ---
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

    // --- THE DYNAMIC MESH ORCHESTRATOR ---
    private void triggerMeshSweep() {
        boolean isConnected = (socket != null && socket.isConnected());
        if (!isConnected && !demoModeSwitch.isChecked()) {
            Toast.makeText(this, "Connect Bluetooth First", Toast.LENGTH_SHORT).show();
            return;
        }

        startCalibBtn.setEnabled(false);
        startCalibBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.GRAY));

        // 18-Second Orchestrator to match the physics of the ESP hardware
        new CountDownTimer(18000, 1000) {
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                if (sec > 13) calibStatusText.setText("Mapping Node 1 Network... (" + sec + "s)");
                else if (sec > 8) calibStatusText.setText("Mapping Node 2 Network... (" + sec + "s)");
                else if (sec > 3) calibStatusText.setText("Mapping Node 3 Network... (" + sec + "s)");
                else calibStatusText.setText("Triangulating Router... (" + sec + "s)");

                calibStatusText.setTextColor(Color.parseColor("#FFD740"));

                // Fire the trigger exactly when the timer starts
                if (sec == 17 && !demoModeSwitch.isChecked()) {
                    try {
                        outputStream.write("CALIBRATE\n".getBytes());
                        outputStream.flush();
                    } catch (Exception e) {}
                }
            }

            public void onFinish() {
                if (demoModeSwitch.isChecked()) {
                    // Simulate a perfect MESH response for the Demo
                    process("MESH,-50.0,-55.0,-52.0,-48.5,-51.0,-60.0\n");
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
        heatmapView.setMesh(0,0,0,0,0,0); // Clear Radar
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
                        demoHandler.postDelayed(this, 200);
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

    // =========================================================================
    // THE 6-VARIABLE DATA ENGINE
    // =========================================================================
    private void process(String d) {
        try {
            String clean = d.replaceAll("[\\n\\r]", "").trim();

            // --- THE DYNAMIC MESH INTERCEPTOR ---
            if (clean.startsWith("MESH,")) {
                String[] parts = clean.split(",");
                if (parts.length >= 7) {
                    float rssi12 = Float.parseFloat(parts[1]);
                    float rssi13 = Float.parseFloat(parts[2]);
                    float rssi23 = Float.parseFloat(parts[3]);
                    float rssiR1 = Float.parseFloat(parts[4]);
                    float rssiR2 = Float.parseFloat(parts[5]);
                    float rssiR3 = Float.parseFloat(parts[6]);

                    // 1. ESP-TO-ESP MATH (Weaker transmit power, ~ -35dBm baseline)
                    double d12 = rssi12 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi12) / 25.0);
                    double d13 = rssi13 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi13) / 25.0);
                    double d23 = rssi23 <= -90 ? 15.0 : Math.pow(10, (-35.0 - rssi23) / 25.0);

                    // 2. NODE-TO-ROUTER MATH (Your calibrated -46.5 baseline)
                    double r1 = rssiR1 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR1) / 31.9);
                    double r2 = rssiR2 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR2) / 31.9);
                    double r3 = rssiR3 <= -90 ? 20.0 : Math.pow(10, (-46.5 - rssiR3) / 31.9);

                    heatmapView.setMesh((float)d12, (float)d13, (float)d23, (float)r1, (float)r2, (float)r3);

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

            // Normal Dashboard Processing
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

        // Push the live telemetry into the Heatmap graphics engine
        heatmapView.updateLiveSignals(nodes[0].rssi, nodes[1].rssi, nodes[2].rssi, nodes[0].latency, nodes[1].latency, nodes[2].latency,nodes[0].loss, nodes[1].loss, nodes[2].loss);
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
    // THE DYNAMIC MESH ENGINE (LAW OF COSINES & HEATMAP)
    // =========================================================================
    class HeatmapView extends View {
        Paint paintN1, paintN2, paintN3, nodeDotPaint, textPaint, linePaint;
        float d12=0, d13=0, d23=0, r1=0, r2=0, r3=0;

        // Store live signal strength
        float liveRssi1 = -99f, liveRssi2 = -99f, liveRssi3 = -99f;
        float latency1, latency2, latency3;
        float loss1, loss2, loss3;

        public HeatmapView(Context context) {
            super(context);

            paintN1 = new Paint(); paintN1.setColor(Color.RED); paintN1.setAlpha(50); paintN1.setStyle(Paint.Style.FILL);
            paintN2 = new Paint(); paintN2.setColor(Color.GREEN); paintN2.setAlpha(50); paintN2.setStyle(Paint.Style.FILL);
            paintN3 = new Paint(); paintN3.setColor(Color.YELLOW); paintN3.setAlpha(50); paintN3.setStyle(Paint.Style.FILL);

            nodeDotPaint = new Paint(); nodeDotPaint.setColor(Color.WHITE); nodeDotPaint.setStyle(Paint.Style.FILL);
            textPaint = new Paint(); textPaint.setColor(Color.WHITE); textPaint.setTextSize(35f); textPaint.setFakeBoldText(true);

            linePaint = new Paint(); linePaint.setColor(Color.parseColor("#555555")); linePaint.setStrokeWidth(5f); linePaint.setStyle(Paint.Style.STROKE);
        }

        // Method to catch live data from Dashboard
        public void updateLiveSignals(float rssi1, float rssi2, float rssi3, float latency1, float latency2, float latency3, float loss1, float loss2, float loss3) {
            this.liveRssi1 = rssi1;
            this.liveRssi2 = rssi2;
            this.liveRssi3 = rssi3;
            this.latency1 = latency1;
            this.latency2 = latency2;
            this.latency3 = latency3;
            this.loss1 = loss1;
            this.loss2 = loss2;
            this.loss3 = loss3;
            postInvalidate(); // Force a redraw when new data arrives
        }

        public void setMesh(float d12, float d13, float d23, float r1, float r2, float r3) {
            // Failsafe zero protection
            this.d12 = Math.max(0.1f, d12);
            this.d13 = Math.max(0.1f, d13);
            this.d23 = Math.max(0.1f, d23);
            this.r1 = r1; this.r2 = r2; this.r3 = r3;
            postInvalidate();
        }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (r1 == 0 && r2 == 0 && r3 == 0) return;

            int width = getWidth();
            int height = getHeight();

            // ==============================
            // 1. NODE POSITION
            // ==============================
            float logical_x1 = 0, logical_y1 = 0;
            float logical_x2 = d12, logical_y2 = 0;

            float logical_x3 = (d12 * d12 + d13 * d13 - d23 * d23) / (2 * d12);
            float inner = d13 * d13 - logical_x3 * logical_x3;
            float logical_y3 = inner > 0 ? (float) Math.sqrt(inner) : 0f;

            float max_x = Math.max(Math.max(logical_x1, logical_x2), logical_x3);
            float min_x = Math.min(Math.min(logical_x1, logical_x2), logical_x3);
            float max_y = Math.max(Math.max(logical_y1, logical_y2), logical_y3);
            float min_y = Math.min(Math.min(logical_y1, logical_y2), logical_y3);

            float logical_width = max_x - min_x;
            float logical_height = max_y - min_y;

            float max_radius = Math.max(Math.max(r1, r2), r3);

            float total_width = logical_width + (max_radius * 2);
            float total_height = logical_height + (max_radius * 2);

            float ppm = Math.min(width / total_width, height / total_height);

            float offsetX = (width - logical_width * ppm) / 2f - min_x * ppm;
            float offsetY = (height - logical_height * ppm) / 2f - min_y * ppm;

            float n1x = logical_x1 * ppm + offsetX;
            float n1y = logical_y1 * ppm + offsetY;

            float n2x = logical_x2 * ppm + offsetX;
            float n2y = logical_y2 * ppm + offsetY;

            float n3x = logical_x3 * ppm + offsetX;
            float n3y = logical_y3 * ppm + offsetY;
            float[] router = estimateRouterPosition(n1x, n1y, n2x, n2y, n3x, n3y);
            float rx = router[0];
            float ry = router[1];

            // ==============================
            // 2. SMOOTH HEATMAP
            // ==============================
            int scale = 2; // smaller = smoother

            int bmpW = width / scale;
            int bmpH = height / scale;

            Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);

            float maxSignal = -100;
            float maxX = 0, maxY = 0;

            for (int x = 0; x < bmpW; x++) {
                for (int y = 0; y < bmpH; y++) {

                    float realX = x * scale;
                    float realY = y * scale;

                    float finalRssi = computeSignal(realX, realY, rx, ry);

                    if (finalRssi > maxSignal) {
                        maxSignal = finalRssi;
                        maxX = realX;
                        maxY = realY;
                    }

                    bmp.setPixel(x, y, getHeatColor(finalRssi));
                }
            }

            Paint paint = new Paint();
            paint.setFilterBitmap(true);

            canvas.drawBitmap(bmp, null, new Rect(0, 0, width, height), paint);

            Paint routerPaint = new Paint();
            routerPaint.setColor(Color.WHITE);
            routerPaint.setStyle(Paint.Style.FILL);

            canvas.drawCircle(rx, ry, 15f, routerPaint);

            Paint routerRing = new Paint();
            routerRing.setColor(Color.GREEN);
            routerRing.setStyle(Paint.Style.STROKE);
            routerRing.setStrokeWidth(4f);

            canvas.drawCircle(rx, ry, 25f, routerRing);

            canvas.drawText("R", rx - 10, ry - 30, textPaint);

            // ==============================
            // 3. STRONGEST POINT
            // ==============================
            Paint glow = new Paint();
            glow.setShader(new RadialGradient(
                    maxX, maxY,
                    40f,
                    Color.parseColor("#00FF88"),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(maxX, maxY, 40f, glow);

            // ==============================
            // 4. MESH + NODES
            // ==============================
            Path path = new Path();
            path.moveTo(n1x, n1y);
            path.lineTo(n2x, n2y);
            path.lineTo(n3x, n3y);
            path.close();

            canvas.drawPath(path, linePaint);

            canvas.drawCircle(n1x, n1y, 20f, nodeDotPaint);
            canvas.drawCircle(n2x, n2y, 20f, nodeDotPaint);
            canvas.drawCircle(n3x, n3y, 20f, nodeDotPaint);

            canvas.drawText("N1", n1x - 25, n1y - 30, textPaint);
            canvas.drawText("N2", n2x - 25, n2y - 30, textPaint);
            canvas.drawText("N3", n3x - 25, n3y - 30, textPaint);
        }
        private float distance(float x1, float y1, float x2, float y2) {
            return (float) Math.sqrt((x1 - x2)*(x1 - x2) + (y1 - y2)*(y1 - y2));
        }
        private float rssiToDistance(float rssi) {
            float A = -40f; // RSSI at 1 meter (tune)
            float n = 2.5f; // path loss exponent (2–4)

            return (float) Math.pow(10, (A - rssi) / (10 * n));
        }
        private float[] estimateRouterPosition(
                float n1x, float n1y,
                float n2x, float n2y,
                float n3x, float n3y) {

            float r1 = rssiToDistance(liveRssi1);
            float r2 = rssiToDistance(liveRssi2);
            float r3 = rssiToDistance(liveRssi3);

            // Trilateration equations
            float A = 2*(n2x - n1x);
            float B = 2*(n2y - n1y);
            float C = r1*r1 - r2*r2 - n1x*n1x + n2x*n2x - n1y*n1y + n2y*n2y;

            float D = 2*(n3x - n1x);
            float E = 2*(n3y - n1y);
            float F = r1*r1 - r3*r3 - n1x*n1x + n3x*n3x - n1y*n1y + n3y*n3y;

            float denom = (A*E - B*D);

            if (Math.abs(denom) < 1e-6) {
                return new float[]{(n1x+n2x+n3x)/3f, (n1y+n2y+n3y)/3f};
            }

            float x = (C*E - B*F) / denom;
            float y = (A*F - C*D) / denom;

            return new float[]{x, y};
        }
        private float computeSignal(float x, float y, float rx, float ry) {

            float d = distance(x, y, rx, ry);

            float PATH_LOSS = 20f;        // less aggressive decay
            float REFERENCE_RSSI = -30f;  // stronger starting point

            float signal = REFERENCE_RSSI - (PATH_LOSS * (float)Math.log10(d + 1));

            return signal;
        }
        private int getHeatColor(float rssi) {

            // Define visible range
            float min = -90f;
            float max = -30f;

            float t = (rssi - min) / (max - min);
            t = Math.max(0f, Math.min(1f, t));

            return interpolateColor(t);
        }
        private int interpolateColor(float t) {
            int[] colors = {
                    Color.parseColor("#0B0F1A"), // no signal
                    Color.parseColor("#3E2723"), // dark brown/red
                    Color.parseColor("#FF6D00"), // orange
                    Color.parseColor("#FFD600"), // yellow
                    Color.parseColor("#4CAF50"), // green
                    Color.parseColor("#00E676")  // bright green (strongest)
            };

            float scaled = t * (colors.length - 1);
            int index = (int) scaled;
            float fraction = scaled - index;

            if (index >= colors.length - 1) return colors[colors.length - 1];

            return blendColors(colors[index], colors[index + 1], fraction);
        }
        private int blendColors(int c1, int c2, float ratio) {

            int r = (int)(Color.red(c1) + ratio * (Color.red(c2) - Color.red(c1)));
            int g = (int)(Color.green(c1) + ratio * (Color.green(c2) - Color.green(c1)));
            int b = (int)(Color.blue(c1) + ratio * (Color.blue(c2) - Color.blue(c1)));

            return Color.argb(200, r, g, b);
        }
    }
}