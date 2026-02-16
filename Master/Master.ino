#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <nvs_flash.h>

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
int currentChannel = 1;
bool isConnected = false;
unsigned long lastPacketId = 0;
int totalPacketsLost = 0;
unsigned long lastArrivalTime = 0;
int jitter = 0;
unsigned long lastRecvTime = 0;      
const unsigned long TIMEOUT_MS = 2000; 

// --- SMOOTHING VARIABLES ---
int displayLatency = 0;       // The "Clean" number we show
unsigned long lastGoodTime = 0; // When did we last get a real number?

// --- FUNCTION DEFINITIONS ---

// 1. Send To Phone (Moved ABOVE OnDataRecv to fix compiler error)
void sendToPhone() {
  if (SerialBT.hasClient()) {
    SerialBT.print(incoming.id); SerialBT.print(",");
    SerialBT.print(incoming.rssi); SerialBT.print(",");
    SerialBT.print(incoming.snr); SerialBT.print(",");
    SerialBT.print(incoming.voltage/1000.0); SerialBT.print(",");
    
    // CALCULATE AND SEND % INSTEAD OF TOTAL COUNT
    float lossPct = 0.0;
    if (incoming.packetId > 0) {
        lossPct = (float)totalPacketsLost / incoming.packetId * 100.0;
    }
    SerialBT.print(lossPct, 2); 
    
    SerialBT.print(",");
    SerialBT.print(jitter); SerialBT.print(",");
    SerialBT.println(displayLatency); // Send the Smooth Latency
  }
}

// 2. Data Receiver
void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  
  // Connection is Alive
  isConnected = true; 
  lastRecvTime = now; 
  
  memcpy(&incoming, incomingData, sizeof(incoming));

  // --- THE FILTER LOGIC ---
  if (incoming.latency != 999) {
      // It's a REAL number (e.g., 12ms). Use it immediately.
      displayLatency = incoming.latency;
      lastGoodTime = now;
  } 
  else {
      // It's a GLITCH (999ms). Ignore it!
      // UNLESS... it's been glitching for > 1 second. Then it's real.
      if (now - lastGoodTime > 1000) {
          displayLatency = 999;
      }
  }

  // Jitter Calc
  if (lastArrivalTime != 0) {
    long diff = now - lastArrivalTime;
    int rawJitter = abs((long)diff - 200); 
    if (rawJitter < 500) jitter = rawJitter;
  }
  lastArrivalTime = now;

  // Loss Calc
  if (incoming.packetId < lastPacketId || (now - lastRecvTime > 3000) || lastPacketId == 0) {
      lastPacketId = incoming.packetId;
      totalPacketsLost = 0; 
  } 
  else {
      if (incoming.packetId > lastPacketId + 1) {
          totalPacketsLost += (incoming.packetId - lastPacketId - 1);
      }
      lastPacketId = incoming.packetId;
  }
  
  // Send to Phone (Rate Limited)
  static unsigned long lastBTSend = 0;
  if (now - lastBTSend > 50) {
     sendToPhone();
     lastBTSend = now;
  }
}

// --- DISPLAY ---
void drawScanningScreen() {
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  display.setCursor(28, 0); display.println("NET ANALYZER"); 
  display.drawLine(0, 8, 128, 8, WHITE);
  
  display.setCursor(20, 25); display.println("AUTO-SCANNING");
  
  display.setCursor(35, 45); 
  display.print("CH: "); display.print(currentChannel);
  
  int barWidth = map(currentChannel, 1, 13, 0, 128);
  display.fillRect(0, 60, barWidth, 4, WHITE);
  
  display.display();
}

void drawMainScreen() {
  display.clearDisplay();
  display.setTextColor(WHITE);
  display.setTextSize(1);
  display.setCursor(28, 0); display.println("NET ANALYZER"); 
  display.drawLine(0, 8, 128, 8, WHITE);
  display.setCursor(0, 12); display.print("RSSI:"); display.print(incoming.rssi); display.print("dBm");  
  display.setCursor(74, 12); display.print("SNR:"); display.print(incoming.snr);
  display.setCursor(0, 24); display.print("PING:"); display.print(displayLatency); display.print("ms");
  display.setCursor(74, 24); display.print("JIT:"); display.print(jitter); display.print("ms");
  
  float per = 0.0;
  if (incoming.packetId > 0) per = (float)totalPacketsLost / incoming.packetId * 100.0;
  display.setCursor(0, 36); display.print("LOSS:"); display.print(per, 1); display.print("%");
  
  display.setCursor(0, 52); display.print("CH:"); display.print(currentChannel);
  
  if (SerialBT.hasClient()) {
    display.setCursor(80, 52); display.print("[BT:ON]");
  } else {
    display.setCursor(80, 52); display.print("[BT:--]");
  }
  display.display();
}

void changeChannel(int ch) {
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
  esp_wifi_set_promiscuous(false);
}

void setup() {
  delay(1000); 
  Serial.begin(115200);

  nvs_flash_erase(); 
  nvs_flash_init();

  SerialBT.begin("ESP32_Scout_Master"); 
  
  if(!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
     if(!display.begin(SSD1306_SWITCHCAPVCC, 0x3D)) for(;;);
  }
  display.clearDisplay();
  display.display();
  
  WiFi.mode(WIFI_STA);
  
  // Power Save Fix: MIN_MODEM allows BT + WiFi coexistence
  esp_wifi_set_ps(WIFI_PS_MIN_MODEM); 
  
  WiFi.disconnect(); 
  
  if (esp_now_init() != ESP_OK) return;
  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
}

void loop() {
  unsigned long now = millis();

  if (now - lastRecvTime > TIMEOUT_MS) {
    isConnected = false;
  }

  if (isConnected) {
    static unsigned long lastDraw = 0;
    if (now - lastDraw > 500) {
      drawMainScreen();
      lastDraw = now;
    }
  } 
  else {
    currentChannel++;
    if (currentChannel > 13) currentChannel = 1;
    changeChannel(currentChannel);
    drawScanningScreen();
    delay(150); 
  }
}