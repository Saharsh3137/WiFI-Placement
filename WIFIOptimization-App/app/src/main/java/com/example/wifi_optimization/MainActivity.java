package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.View;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends ComponentActivity {

    TextView statusText, nodeStatusText, rssiText, latencyText, packetLossText, qualityText;
    Button connectButton, disconnectButton, sendWifiBtn;
    EditText ssidInput, passInput;
    LinearLayout wifiLayout;

    LineChart rssiChart, latencyChart, packetChart;

    BluetoothAdapter bluetoothAdapter;
    BluetoothSocket socket;
    InputStream inputStream;
    OutputStream outputStream;

    String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E";
    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    class NodeData {
        float rssi, latency, loss;
        long lastUpdate;
    }

    NodeData[] nodes = new NodeData[3];
    int currentView = -1;

    int i1 = 0, i2 = 0, i3 = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        nodeStatusText = findViewById(R.id.nodeStatusText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);
        qualityText = findViewById(R.id.qualityText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        wifiLayout = findViewById(R.id.wifiLayout);
        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        sendWifiBtn = findViewById(R.id.sendWifiBtn);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        setupChart(rssiChart);
        setupChart(latencyChart);
        setupChart(packetChart);

        for (int i = 0; i < 3; i++) nodes[i] = new NodeData();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, 1);
        }

        connectButton.setOnClickListener(v -> connectBT());
        disconnectButton.setOnClickListener(v -> disconnectBT());
        sendWifiBtn.setOnClickListener(v -> sendWiFiCredentials());

        findViewById(R.id.overallBtn).setOnClickListener(v -> currentView = -1);
        findViewById(R.id.node1Btn).setOnClickListener(v -> currentView = 0);
        findViewById(R.id.node2Btn).setOnClickListener(v -> currentView = 1);
        findViewById(R.id.node3Btn).setOnClickListener(v -> currentView = 2);
    }

    // ---------------- BLUETOOTH ----------------

    private void connectBT() {
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
                    statusText.setText("Connected");
                    statusText.setTextColor(Color.GREEN);

                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);

                    wifiLayout.setVisibility(View.VISIBLE);
                });

                readData();

            } catch (Exception e) {
                e.printStackTrace();

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
        } catch (Exception ignored) {}

        statusText.setText("Disconnected");
        statusText.setTextColor(Color.RED);

        connectButton.setVisibility(View.VISIBLE);
        disconnectButton.setVisibility(View.GONE);
        wifiLayout.setVisibility(View.GONE);
    }

    // ---------------- YOUR ORIGINAL WORKING METHOD ----------------

    private void sendWiFiCredentials() {
        if (socket == null || !socket.isConnected() || outputStream == null) {
            Toast.makeText(this, "Bluetooth not connected!", Toast.LENGTH_SHORT).show();
            return;
        }

        String ssid = ssidInput.getText().toString().trim();
        String pass = passInput.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "SSID and Password cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        String payload = "WIFI:" + ssid + "," + pass + "\n";

        new Thread(() -> {
            try {
                outputStream.write(payload.getBytes());
                outputStream.flush();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Credentials Sent!", Toast.LENGTH_LONG).show();

                    // ✅ HIDE WIFI UI AFTER SUCCESS
                    wifiLayout.setVisibility(View.GONE);

                    // OPTIONAL: Clear fields
                    ssidInput.setText("");
                    passInput.setText("");

                    // OPTIONAL: Update status
                    statusText.setText("Deploying to Nodes...");
                    statusText.setTextColor(Color.parseColor("#00E5FF"));
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Failed to send data", Toast.LENGTH_SHORT).show());
                e.printStackTrace();
            }
        }).start();
    }

    // ---------------- DATA ----------------

    private void readData() {
        new Thread(() -> {
            byte[] buf = new byte[1024];

            while (true) {
                try {
                    int n = inputStream.read(buf);
                    process(new String(buf, 0, n));
                } catch (Exception e) {
                    break;
                }
            }
        }).start();
    }

    private void process(String d) {
        runOnUiThread(() -> {
            try {
                String[] p = d.trim().split(",");
                if (p.length < 7) return;

                int id = Integer.parseInt(p[0]) - 1;

                nodes[id].rssi = Float.parseFloat(p[1]);
                nodes[id].loss = Float.parseFloat(p[4]);
                nodes[id].latency = Float.parseFloat(p[6]);
                nodes[id].lastUpdate = System.currentTimeMillis();

                updateUI();

            } catch (Exception ignored) {}
        });
    }

    private void updateUI() {
        NodeData d = (currentView == -1) ? avg() : nodes[currentView];

        rssiText.setText("RSSI: " + d.rssi);
        latencyText.setText("Latency: " + d.latency);
        packetLossText.setText("Loss: " + d.loss);

        updateStatus();

        add(rssiChart, d.rssi, i1++);
        add(latencyChart, d.latency, i2++);
        add(packetChart, d.loss, i3++);
    }

    private NodeData avg() {
        NodeData a = new NodeData();
        int c = 0;

        for (NodeData n : nodes) {
            if (System.currentTimeMillis() - n.lastUpdate < 5000) {
                a.rssi += n.rssi;
                a.latency += n.latency;
                a.loss += n.loss;
                c++;
            }
        }

        if (c > 0) {
            a.rssi /= c;
            a.latency /= c;
            a.loss /= c;
        }

        return a;
    }

    private void updateStatus() {
        String s = "";
        for (int i = 0; i < 3; i++) {
            s += "N" + (i + 1) + ":" +
                    (System.currentTimeMillis() - nodes[i].lastUpdate < 5000 ? "ON " : "OFF ");
        }
        nodeStatusText.setText(s);
    }

    private void setupChart(LineChart c) {
        c.setData(new LineData());
        c.getDescription().setEnabled(false);
        c.getAxisRight().setEnabled(false);
    }

    private void add(LineChart c, float v, int i) {
        LineData d = c.getData();
        LineDataSet s;

        if (d.getDataSetCount() == 0) {
            s = new LineDataSet(null, "data");
            s.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            s.setColor(Color.CYAN);
            s.setDrawCircles(false);
            d.addDataSet(s);
        } else s = (LineDataSet) d.getDataSetByIndex(0);

        d.addEntry(new Entry(i, v), 0);
        d.notifyDataChanged();
        c.notifyDataSetChanged();
        c.moveViewToX(d.getEntryCount());
    }
}