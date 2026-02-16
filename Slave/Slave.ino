#include <ESP8266WiFi.h>
#include <espnow.h>

// 1. VOLTAGE SENSOR (Must be top of file)
ADC_MODE(ADC_VCC); 

// --- USER CONFIGURATION ---
const char* ssid = "PK";     
const char* password = "09876543211"; 

// MASTER MAC ADDRESS (From your previous finding)
uint8_t broadcastAddress[] = {0xB0, 0xCB, 0xD8, 0xC6, 0x66, 0x7C}; 

// --- VARIABLES ---
unsigned long startSendTime = 0;
unsigned long lastLatency = 0;
unsigned long globalPacketCount = 0;
int routerChannel = 1; 

typedef struct struct_message {
  int id;
  int rssi;
  int snr;
  unsigned long packetId; 
  int voltage;
  int latency;
} struct_message;

struct_message myData;

void OnDataSent(uint8_t *mac_addr, uint8_t sendStatus) {
  unsigned long endTime = micros();
  if (sendStatus == 0) {
    lastLatency = (endTime - startSendTime) / 1000;
    if (lastLatency == 0) lastLatency = 1;
  } else {
    lastLatency = 999; 
  }
}

void setup() {
  // 1. SAFETY DELAY: Wait 2 seconds for power to stabilize
  // This prevents the "Brown-out" crash on startup
  delay(2000); 

  Serial.begin(115200);
  Serial.println("\n\n--- ESP8266 SLAVE STARTING ---");

  // 2. NUKE OLD SETTINGS (The "Work Every Time" Fix)
  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP); // <--- ADD THIS: Keeps radio awake
  WiFi.persistent(false);      // Do not save credentials to flash
  WiFi.disconnect(true);       // Wipe previous connection state
  delay(100);

  // 3. CONNECT TO WIFI (With Timeout)
  Serial.print("Connecting to: "); Serial.println(ssid);
  WiFi.begin(ssid, password);
  
  unsigned long startAttempt = millis();
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
    // If it takes more than 20 seconds, restart to fix radio glitch
    if (millis() - startAttempt > 20000) {
      Serial.println("\nWifi Failed! Restarting...");
      ESP.restart();
    }
  }
  Serial.println("\nWiFi Connected!");

  // 4. DETECT CHANNEL
  routerChannel = WiFi.channel(); 
  Serial.print("Router Channel: "); Serial.println(routerChannel);

  // 5. INIT ESP-NOW
  if (esp_now_init() != 0) {
    Serial.println("ESP-NOW Init Failed");
    return;
  }
  esp_now_set_self_role(ESP_NOW_ROLE_CONTROLLER);
  esp_now_register_send_cb(OnDataSent);
  
  // 6. ADD PEER (Master)
  int res = esp_now_add_peer(broadcastAddress, ESP_NOW_ROLE_SLAVE, routerChannel, NULL, 0);
  if (res == 0) Serial.println("Master Added Successfully");
  else Serial.println("Failed to Add Master");
}

void loop() {
  // --- RECOVERY: If connection fails (999ms), re-add peer ---
  if (lastLatency == 999) {
     esp_now_del_peer(broadcastAddress);
     esp_now_add_peer(broadcastAddress, ESP_NOW_ROLE_SLAVE, WiFi.channel(), NULL, 0);
  }

  // --- PREPARE DATA ---
  myData.id = 1; 
  myData.rssi = WiFi.RSSI(); 
  myData.snr = myData.rssi - (-95); 
  myData.packetId = globalPacketCount++;
  
  // Safe Voltage Read
  int rawVolt = ESP.getVcc();
  if (rawVolt > 4000) rawVolt = 3300; // Cap errors
  myData.voltage = rawVolt;         
  
  myData.latency = (int)lastLatency; 

  // --- SEND ---
  startSendTime = micros(); 
  esp_now_send(broadcastAddress, (uint8_t *) &myData, sizeof(myData));

  delay(200); 
}