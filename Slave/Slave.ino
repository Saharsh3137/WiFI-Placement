#include <ESP8266WiFi.h>
#include <espnow.h>

// 1. Voltage Sensor
ADC_MODE(ADC_VCC); 

// --- USER CONFIGURATION ---
const char* ssid = "trojan.exe";     // <--- Enter exact Name
const char* password = "12345678"; // <--- Enter exact Password

// --- CONFIG ---
uint8_t broadcastAddress[] = {0xB0, 0xCB, 0xD8, 0xC6, 0x66, 0x7C}; 

// --- VARIABLES ---
unsigned long startSendTime = 0;
unsigned long lastLatency = 0;
unsigned long globalPacketCount = 0;
int routerChannel = 1; // Will be detected automatically

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
  Serial.begin(115200);
  
  // 1. Connect to Wi-Fi (Real Connection)
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  
  Serial.print("Connecting");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nConnected!");
  
  // 2. DETECT ROUTER CHANNEL
  // The ESP8266 has moved to the router's channel automatically.
  routerChannel = WiFi.channel(); 
  Serial.print("Router is on Channel: ");
  Serial.println(routerChannel);

  // 3. Init ESP-NOW
  if (esp_now_init() != 0) return;
  esp_now_set_self_role(ESP_NOW_ROLE_CONTROLLER);
  esp_now_register_send_cb(OnDataSent);
  
  // CRITICAL: Peer must be set to the SAME channel as the Router
  esp_now_add_peer(broadcastAddress, ESP_NOW_ROLE_SLAVE, routerChannel, NULL, 0);
}

void loop() {
  // --- STEP 1: GET REAL METRICS ---
  myData.id = 1; 
  
  // A. RSSI (Valid because we are connected)
  myData.rssi = WiFi.RSSI(); 
  
  // B. SNR CALCULATION (Stable Method)
  // Since scanning breaks the connection, we use a standard "Noise Floor" 
  // of -95dBm. This is standard engineering practice for active links.
  // Formula: SNR = Signal - Noise
  myData.snr = myData.rssi - (-95); 

  myData.packetId = globalPacketCount++;
  
  int rawVolt = ESP.getVcc();
  if (rawVolt > 4000) rawVolt = 3300; 
  myData.voltage = rawVolt;         
  
  myData.latency = (int)lastLatency; 

  // --- STEP 2: SEND DATA ---
  startSendTime = micros(); 
  esp_now_send(broadcastAddress, (uint8_t *) &myData, sizeof(myData));

  delay(200); // 5 Updates per second
}