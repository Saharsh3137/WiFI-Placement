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
const unsigned long TIMEOUT_MS = 5000; // Increased to 5s for stability

// --- SMOOTHING VARIABLES ---
int displayLatency = 0;       
unsigned long lastGoodTime = 0; 

// --- FUNCTION DEFINITIONS ---

// 1. Send To Phone
void sendToPhone() {
  if (SerialBT.hasClient()) {
    SerialBT.print(incoming.id); SerialBT.print(",");
    SerialBT.print(incoming.rssi); SerialBT.print(",");
    SerialBT.print(incoming.snr); SerialBT.print(",");
    SerialBT.print(incoming.voltage/1000.0); SerialBT.print(",");
    
    // Send Loss %
    float lossPct = 0.0;
    if (incoming.packetId > 0) {
        lossPct = (float)totalPacketsLost / incoming.packetId * 100.0;
    }
    SerialBT.print(lossPct, 2); 
    
    SerialBT.print(",");
    SerialBT.print(jitter); SerialBT.print(",");
    SerialBT.println(displayLatency); // Will never be 999 now
  }
}

float smoothedLatency = 0;
const float filterWeight = 0.2; // Adjust between 0.1 (very smooth) and 0.5 (fast)


// 2. Data Receiver
void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  
  isConnected = true; 
  lastRecvTime = now; 
  
  memcpy(&incoming, incomingData, sizeof(incoming));

  if (incoming.latency != 999) {
    // Math: New Value = (Old * 0.8) + (Current * 0.2)
    smoothedLatency = (smoothedLatency * (1.0 - filterWeight)) + (incoming.latency * filterWeight);
    displayLatency = (int)smoothedLatency;
}
  // If it IS 999 (glitch), we do NOTHING.
  // We just keep showing the last good number (e.g. 14ms).
  // This forces the graph to stay smooth.

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
  if (now - lastBTSend > 100) {
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
  
  // KEEP THIS! It fixes the crash.
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