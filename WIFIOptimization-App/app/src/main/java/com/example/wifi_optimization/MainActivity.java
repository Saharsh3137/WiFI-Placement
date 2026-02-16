package com.example.wifi_optimization;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.InputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private TextView statusText, rssiText, latencyText, packetLossText, qualityText;
    private Button connectButton, disconnectButton;

    private LineChart rssiChart, latencyChart, packetChart;
    private LineData rssiData, latencyData, packetData;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;

    private final String DEVICE_ADDRESS = "B0:CB:D8:C6:66:7E";
    private final UUID uuid =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private int rssiIndex = 0;
    private int latencyIndex = 0;
    private int packetIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        rssiText = findViewById(R.id.rssiText);
        latencyText = findViewById(R.id.latencyText);
        packetLossText = findViewById(R.id.packetLossText);
        qualityText = findViewById(R.id.qualityText);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        rssiChart = findViewById(R.id.rssiChart);
        latencyChart = findViewById(R.id.latencyChart);
        packetChart = findViewById(R.id.packetChart);

        setupChart(rssiChart);
        setupChart(latencyChart);
        setupChart(packetChart);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, 1);
        }

        connectButton.setOnClickListener(v -> {
            statusText.setText("Connecting...");
            statusText.setTextColor(Color.WHITE);
            connectBluetooth();
        });

        disconnectButton.setOnClickListener(v -> disconnectBluetooth());
    }

    private void setupChart(LineChart chart) {

        LineData data = new LineData();
        chart.setData(data);

        chart.setBackgroundColor(Color.parseColor("#121826"));
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);

        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);

        chart.getAxisRight().setEnabled(false);

        chart.getXAxis().setDrawGridLines(false);
        chart.getAxisLeft().setDrawGridLines(false);

        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getAxisLeft().setTextColor(Color.GRAY);

        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);
    }


    private void connectBluetooth() {
        new Thread(() -> {
            try {

                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() ->
                            statusText.setText("Permission Denied"));
                    return;
                }

                BluetoothDevice device =
                        bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS);

                bluetoothAdapter.cancelDiscovery();

                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();

                runOnUiThread(() -> {
                    statusText.setText("Status: Connected");
                    statusText.setTextColor(Color.GREEN);
                    connectButton.setVisibility(View.GONE);
                    disconnectButton.setVisibility(View.VISIBLE);
                });

                inputStream = socket.getInputStream();
                readData();

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Connection Failed");
                    statusText.setTextColor(Color.RED);
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void disconnectBluetooth() {
        try {
            if (socket != null) socket.close();

            statusText.setText("Status: Disconnected");
            statusText.setTextColor(Color.RED);

            connectButton.setVisibility(View.VISIBLE);
            disconnectButton.setVisibility(View.GONE);

        } catch (Exception ignored) {}
    }

    private void readData() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];

            while (true) {
                try {
                    int bytes = inputStream.read(buffer);
                    String data = new String(buffer, 0, bytes);
                    processData(data);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected");
                        statusText.setTextColor(Color.RED);
                        connectButton.setVisibility(View.VISIBLE);
                        disconnectButton.setVisibility(View.GONE);
                    });
                    break;
                }
            }
        }).start();
    }

    private void processData(String data) {
        runOnUiThread(() -> {
            try {
                String[] parts = data.trim().split(",");
                if (parts.length < 7) return;

                float rssi = Float.parseFloat(parts[1]);
                float packetLoss = Float.parseFloat(parts[4]);
                float latency = Float.parseFloat(parts[6]);

                rssiText.setText("RSSI: " + rssi + " dBm");
                latencyText.setText("Latency: " + latency + " ms");
                packetLossText.setText("Packet Loss: " + packetLoss + "%");

                qualityText.setText("Signal Quality: " +
                        getSignalQuality((int) rssi));

                addEntry(rssiChart, rssi, rssiIndex++);
                addEntry(latencyChart, latency, latencyIndex++);
                addEntry(packetChart, packetLoss, packetIndex++);

            } catch (Exception ignored) {}
        });
    }

    private void addEntry(LineChart chart, float value, int index) {

        LineData data = chart.getData();
        LineDataSet dataSet;

        if (data.getDataSetCount() == 0) {
            dataSet = new LineDataSet(null, "Live Data");

            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);  // Smooth curves
            dataSet.setColor(Color.parseColor("#00E5FF"));
            dataSet.setLineWidth(2.5f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setDrawFilled(true);
            dataSet.setFillColor(Color.parseColor("#00E5FF"));
            dataSet.setFillAlpha(40);

            data.addDataSet(dataSet);
        } else {
            dataSet = (LineDataSet) data.getDataSetByIndex(0);
        }

        data.addEntry(new Entry(index, value), 0);
        data.notifyDataChanged();

        chart.notifyDataSetChanged();
        chart.setVisibleXRangeMaximum(40);
        chart.moveViewToX(data.getEntryCount());
    }


    private String getSignalQuality(int rssi) {
        if (rssi > -60) return "Excellent";
        if (rssi > -75) return "Good";
        if (rssi > -85) return "Weak";
        return "Poor";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}
