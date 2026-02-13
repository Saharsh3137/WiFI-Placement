#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>

// --- CONFIGURATION ---
// SET THIS TO MATCH YOUR ROUTER'S CHANNEL (Check Slave Serial Monitor)
#define WIFI_CHANNEL 11

// --- HARDWARE ---
BluetoothSerial SerialBT;
#define SCREEN_ADDRESS 0x3C 
Adafruit_SSD1306 display(128, 64, &Wire, -1);

// --- DATA STRUCTURE ---
typedef struct struct_message {
  int id;
  int rssi;
  int snr;
  unsigned long packetId; 
  int voltage;
  int latency; 
} struct_message;

struct_message incoming;

// --- STATE VARIABLES ---
unsigned long lastPacketId = 0;
int totalPacketsLost = 0;
unsigned long lastArrivalTime = 0;
int jitter = 0;

// TIMERS FOR UI
unsigned long lastRecvTime = 0;      // When did we last hear from Slave?
const unsigned long TIMEOUT_MS = 3000; // 3 seconds without data = "Lost"

// --- CALLBACK: DATA RECEIVED ---
// --- CORRECTED DATA RECEIVER ---
// --- SMART DATA RECEIVER ---
void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  
  // 1. Check for Session Gaps
  unsigned long timeSinceLast = now - lastRecvTime;
  lastRecvTime = now; 
  
  memcpy(&incoming, incomingData, sizeof(incoming));

  // 2. JITTER CALCULATION
  if (lastArrivalTime != 0) {
    long diff = now - lastArrivalTime;
    int rawJitter = abs((long)diff - 200); 
    if (rawJitter < 500) jitter = rawJitter;
  }
  lastArrivalTime = now;

  // 3. THE "ANTI-34000%" FIX
  // Logic: If the Slave reboots (packetId < last) OR 
  // if we were disconnected for > 3 seconds, just sync and don't count loss.
  if (incoming.packetId < lastPacketId || timeSinceLast > 3000 || lastPacketId == 0) {
      // RESET STATS FOR NEW SESSION
      lastPacketId = incoming.packetId;
      totalPacketsLost = 0; // This clears that 34000% error instantly
  } 
  else {
      // NORMAL OPERATION: Count gaps
      if (incoming.packetId > lastPacketId + 1) {
          totalPacketsLost += (incoming.packetId - lastPacketId - 1);
      }
      lastPacketId = incoming.packetId;
  }

  sendToPhone();
}

void sendToPhone() {
  SerialBT.print(incoming.id); SerialBT.print(",");
  SerialBT.print(incoming.rssi); SerialBT.print(",");
  SerialBT.print(incoming.snr); SerialBT.print(",");
  SerialBT.print(incoming.voltage/1000.0); SerialBT.print(",");
  SerialBT.print(totalPacketsLost); SerialBT.print(",");
  SerialBT.print(jitter); SerialBT.print(",");
  SerialBT.println(incoming.latency); 
}

// --- DISPLAY SCREENS ---

void drawWaitingScreen() {
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  
  // Center: "NET ANALYZER"
  display.setCursor(28, 0); 
  display.println("NET ANALYZER"); 
  display.drawLine(0, 8, 128, 8, WHITE);

  // Animated Loading Text
  display.setCursor(10, 25);
  display.setTextSize(1);
  display.println("WAITING FOR NODE...");
  
  display.setCursor(10, 40);
  // Simple animation based on time
  if ((millis() / 500) % 2 == 0) {
     display.println("   [ SCANNING ]   ");
  } else {
     display.println("   [  ....    ]   ");
  }

  // Channel Info
  display.setCursor(20, 54);
  display.print("CHANNEL: "); display.print(WIFI_CHANNEL);

  display.display();
}

void drawMainScreen() {
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);

  // HEADER
  display.setCursor(28, 0); 
  display.println("NET ANALYZER"); 
  display.drawLine(0, 8, 128, 8, WHITE);

  // ROW 1
  display.setCursor(0, 12);
  display.print("RSSI:"); display.print(incoming.rssi); display.print("dBm");  
  display.setCursor(74, 12);
  display.print("SNR:"); display.print(incoming.snr);

  // ROW 2
  display.setCursor(0, 24);
  display.print("PING:"); display.print(incoming.latency); display.print("ms");
  display.setCursor(74, 24);
  display.print("JIT:"); display.print(jitter); display.print("ms");

  // ROW 3
  float per = 0.0;
  if (incoming.packetId > 0) per = (float)totalPacketsLost / incoming.packetId * 100.0;
  
  display.setCursor(0, 36);
  display.print("LOSS:"); display.print(per, 1); display.print("%");

  // ROW 4
  display.setCursor(0, 52);
  display.print("BAT:"); display.print(incoming.voltage/1000.0, 1); display.print("V");
  
  if (SerialBT.hasClient()) {
    display.setCursor(80, 52);
    display.print("[BT:ON]");
  } else {
    display.setCursor(80, 52);
    display.print("[BT:--]");
  }

  display.display();
}

void setup() {
  Serial.begin(115200);
  
  // 1. Init BT
  SerialBT.begin("ESP32_Scout_Master"); 
  
  // 2. Init Screen
  if(!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
     if(!display.begin(SSD1306_SWITCHCAPVCC, 0x3D)) for(;;);
  }
  display.clearDisplay();
  display.display();
  
  // 3. Init WiFi & ESP-NOW
  WiFi.mode(WIFI_STA);
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);
  esp_wifi_set_promiscuous(false);

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
}

void loop() {
  // LOGIC: Check if we have received data recently
  if (millis() - lastRecvTime > TIMEOUT_MS) {
    // TIMEOUT: We haven't heard from the Slave in > 3 seconds
    drawWaitingScreen();
  } else {
    // ACTIVE: We have fresh data
    // (Only redraw every 500ms to stop flickering)
    static unsigned long lastDraw = 0;
    if (millis() - lastDraw > 500) {
      drawMainScreen();
      lastDraw = millis();
    }
  }
}