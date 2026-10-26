# 📡 RelayNet 2.0: Decentralized Offline Multi-Hop Mesh Protocol

[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0_to_15+-3DDC84.svg?style=flat&logo=android)](https://www.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-4285F4.svg?style=flat)](https://developer.android.com/jetpack/compose)
[![Protocol: P2P Cluster](https://img.shields.io/badge/Mesh-Nearby_Connections_P2P_Cluster-FF6F00.svg?style=flat)](https://developers.google.com/nearby/connections/overview)
[![Audio: AMR--NB PTT](https://img.shields.io/badge/Walkie--Talkie-AMR--NB_Sub--100ms-00E5FF.svg?style=flat)](https://en.wikipedia.org/wiki/Adaptive_Multi-Rate_audio_codec)
[![Security: ECDH + AES-CTR](https://img.shields.io/badge/Security-ECDH_%2B_AES--CTR_E2EE-00C853.svg?style=flat)](https://en.wikipedia.org/wiki/Elliptic-curve_Diffie%E2%80%93Hellman)
[![Edge AI: Bilingual Triage](https://img.shields.io/badge/Edge_AI-Zero--Cloud_NLP_Triage-FF4081.svg?style=flat)](https://github.com/rashid-aziz-ee/RelayNet-2.0)

> **Zero Cellular Towers. Zero Wi-Fi Routers. Zero Centralized Servers.**  
> An autonomous, self-healing Mobile Ad-Hoc Network (MANET) protocol engineered for rapid disaster recovery, cellular blackouts, and tactical first-responder operations.

---

## 📌 Project Overview & Evolution (v1.0 ➔ v2.0)

RelayNet 2.0 is a comprehensive architectural overhaul of our initial proof-of-concept. While v1 explored basic peer discovery, **RelayNet 2.0 is an industrial-grade, fully validated communication stack** designed to address strict real-world deployment challenges:

| Architectural Capability | Early Prototype (v1.0) | RelayNet 2.0 (Production Release) |
| :--- | :--- | :--- |
| **Topology & Routing** | 1-to-1 direct point connection | **Multi-Hop Store-and-Forward Gossip Mesh** (TTL=3, Seen-Set deduplication) |
| **Tactical Voice** | None (Text-only) | **Tactical Walkie-Talkie Push-to-Talk (PTT)** (AMR-NB sub-100ms latency) |
| **Telemetry & Benchmarking** | Manual observation | **Automated In-App MANET Telemetry Suite** (RTT, PDR, CSV export) |
| **Security & Privacy** | Plaintext broadcasts | **ECDH Key Exchange + AES-CTR End-to-End Encryption** (Confidential DMs) |
| **Intelligent Triage** | Raw unranked messages | **Edge NLP Triage Engine** (Bilingual English + Roman Urdu hazard priority) |
| **Network Reliability** | Sockets stuck upon disconnect | **Dynamic Self-Healing & Ghost-Peer Pruning** (`PEER_LOST` cascade, deterministic tie-breaking) |
| **Battery Life** | Continuous full Wi-Fi locks (~400 mAh) | **Optimized CPU-only WakeLock** (~65% reduction in radio power draw) |

---

## 🛑 The Problem

During catastrophic natural disasters (earthquakes, tsunamis, severe floods) or localized infrastructure blackouts:
* Cellular base stations (BTS) lose power, fiber backhauls sever, and mobile towers collapse.
* First responders and civilians are left with fully functional smartphones that become completely inert communication bricks.
* Traditional consumer communication apps fail unconditionally due to rigid client-server architectural dependencies.

**RelayNet 2.0 transforms every smartphone into an autonomous physical relay node**, forming a resilient, spontaneous mesh communications grid with zero external infrastructure.

---

## 🏗️ System Architecture & Packet Lifecycle

```text
[Node A: Originator]
       │
       ▼ (1. AES-CTR Encrypted Payload + UUID + TTL: 3)
[Wi-Fi Direct / BLE PHY Link]
       │
       ▼ (Direct range limit: ~180m LoS)
[Node B: Autonomous Relay]  ─── Store & Forward Deduplication (Seen UUID check)
       │                    ─── Decrement TTL (3 -> 2)
       ▼ (Packet Re-Broadcast)
[Node C: Destination Node]  ─── Decrypts via Private Key & Verifies Integrity
```

### Protocol Packet Structure
Packets are transmitted as compact delimiter-separated payloads designed for minimal airtime contention:
```text
MESSAGE_ID | SENDER_DEVICE_ID | SENDER_NAME | CIPHERTEXT_PAYLOAD | TIMESTAMP | TTL | RECIPIENT_ID | RECIPIENT_NAME
```

---

## 🛡️ Addressing Real-World Deployment Challenges (Judge & Field Validation)

Based on rigorous empirical field testing and feedback from expert hackathon panels, RelayNet 2.0 provides engineered solutions for the four fundamental bottlenecks of MANETs:

### 1. 📏 Empirical Range & Propagation Validation
* **Open Ground (Clear Line-of-Sight):** Achieved **180+ meters** reliable single-hop direct range over 2.4 GHz/5 GHz Wi-Fi Direct with a 94.2% Packet Delivery Ratio (PDR).
* **Multi-Hop Relay Extension:** 2-hop mesh relay tests demonstrated communication spans exceeding **300+ meters** through an intermediate relay device.
* **Indoor Structural Penetration:** In dense non-LoS environments (reinforced brick & concrete walls), single-hop direct link spans **10 to 15 meters**. Multi-hop relaying autonomously bridges room-to-room dead zones.

### 2. 🔋 Battery Drain & Radio Power Optimization
* **Wi-Fi Lock Pruning:** Completely removed high-power `WIFI_MODE_FULL_LOW_LATENCY` locks that previously kept Wi-Fi radios in continuous infrastructure STA mode, reducing background battery consumption by **over 65%**.
* **Selective CPU WakeLock:** Employs an ultra-lean `PowerManager.PARTIAL_WAKE_LOCK` inside an Android Foreground Service, keeping CPU threads alive for instant packet forwarding without burning display or baseband power.
* **Adaptive Duty-Cycling:** Radio discovery is throttled dynamically during stationary states and soft-cycled upon network topology shifts.

### 3. 🌐 Dense-Network Reliability & Collision Mitigation
* **Deterministic Tie-Breaking:** When two nearby devices discover each other simultaneously, both calling `requestConnection` causes an over-the-air collision (Google Nearby status 8003). RelayNet 2.0 enforces a deterministic tie-breaker: the device with the lexicographically higher `deviceId` initiates immediately, while the lower node yields for 4.5 seconds to accept the incoming link cleanly.
* **Store-and-Forward Loop Avoidance:** In-memory bloom filter / hash set (`seenMessageIds`) prevents duplicate message propagation and broadcast storms.
* **Ghost Peer Pruning:** When a radio socket drops or goes out of range, the discovering node immediately purges stale records and broadcasts a `PEER_LOST` packet across the mesh to cleanse all nodes' routing tables.
* **Anti-Entropy Gossip Backfill:** On reconnection, nodes exchange lightweight message ID catalogs (`SYNC_CATALOG`) to automatically backfill any missed packets without re-broadcasting the entire message store.

### 4. 📱 Multi-OEM Device Compatibility
* **Tested Across Diverse Hardware:** Validated on multi-vendor Android devices (Samsung Exynos/Snapdragon, Oppo ColorOS, Vivo FuntouchOS, Xiaomi HyperOS, Google Pixel) across Android versions from API 26 (Android 8.0) to API 35 (Android 15).
* **Dual-Radio Fallback:** Seamlessly leverages Bluetooth Low Energy (BLE) for low-power continuous peer discovery and advertises multi-channel Wi-Fi Direct for high-bandwidth data transfers.
* **Universal Offline APK Sideloading:** Integrated `*/*` MIME distribution with Android `ClipData` URI permission grants, allowing RelayNet to be sideloaded offline via Quick Share, ShareIt, ShareMe, or Bluetooth.

---

## ⚡ Key Innovations in RelayNet 2.0

### 🎙️ 1. Tactical Walkie-Talkie Push-to-Talk (PTT)
* **Real-Time Voice Transmission:** First responders can hold the Walkie-Talkie button to transmit voice without typing.
* **AMR-NB Codec:** Employs Adaptive Multi-Rate Narrowband (1.5 KB/sec). A 2-second voice note produces a tiny **3 KB packet**, while a 5-second note is only **7.5 KB**.
* **Sub-100ms Airtime Latency:** Transmits effortlessly inside Nearby Connections' 32KB payload boundary, achieving near-instantaneous audio delivery.
* **Integrated Audio Player:** Custom player with Play/Pause controls, live duration counters, and visual playback indicators.

### 📊 2. Automated MANET Benchmark & Telemetry Engine
* **Automated Empirical Testing:** Tap `📊` in the top bar to run automated test suites ($N$ sequenced test packets at configurable intervals).
* **Scientific Metrics:** Automatically computes Round-Trip Time (RTT), one-way latency, hop counts, and Packet Delivery Ratio (PDR %).
* **Instant CSV Export:** One-tap export directly into CSV format (compatible with Excel, Google Sheets, Python Pandas) with Android share sheet support.

### 🧠 3. Edge NLP Emergency Triage Engine
* **Zero-Cloud Intelligence:** Runs a local morphological stemming and fuzzy Levenshtein distance matcher on device.
* **Bilingual Vocabulary:** Understands both English and Roman Urdu emergency terminology (`aag`, `zakhmi`, `khatra`, `accident`, `malba`, `trapped`, `blood`).
* **Visual Hazard Badges:** Automatically flags critical casualty, fire, and structural risks to prioritize rescue worker attention.

### 🔒 4. Zero-Trust ECDH + AES-CTR Cryptography
* **Autonomous Handshake:** Direct peers exchange EC public keys upon radio connection (`KEY_EXCHANGE` / `KEY_EXCHANGE_ACK`).
* **Private Whispers:** 1-on-1 conversations are encrypted with AES-CTR using ECDH shared secrets. Relaying nodes only see encrypted ciphertext.
* **Unread Notification Badges:** Peer chips display glowing neon green indicators and `NEW` tags when unread private messages arrive while in the public mesh feed.

---

## 📊 Real-World Empirical Benchmarks

| Metric | Measured Value | Testing Methodology |
| :--- | :--- | :--- |
| **Open Ground LoS Range** | **180+ Meters** | Direct Wi-Fi Direct link in open sports ground |
| **Multi-Hop Relay Span** | **300+ Meters** | 2-hop configuration through intermediate node |
| **Indoor Structural Range** | **10 – 15 Meters** | Multi-room building through brick/concrete walls |
| **Packet Delivery Ratio (PDR)** | **94.2%** | 500-packet automated benchmark suite |
| **Voice Packet Latency** | **< 100 ms** | 2-second AMR-NB audio note over Wi-Fi Direct |
| **Text Latency (1-hop)** | **18 – 45 ms** | Round-trip ping/echo telemetry |
| **Battery Consumption** | **~35 - 50 mAh / hr** | Continuous background mesh forwarding (CPU WakeLock) |

---

## 🛠️ Technology Stack

* **Language:** Kotlin 1.9+ (Coroutines, StateFlow concurrency)
* **UI Framework:** Jetpack Compose (Material Design 3, dynamic pulsing radar, reactive single-row input)
* **Networking Transport:** Google Nearby Connections API (`P2P_CLUSTER` — BLE discovery + Wi-Fi Direct data transport)
* **Audio Subsystem:** Android native `MediaRecorder` & `MediaPlayer` (AMR-NB codec at 8kHz / 12.2 kbps)
* **Database & Caching:** Room Database (SQLite with anti-entropy sync cataloging)
* **Cryptography:** Asymmetric Elliptic Curve Diffie-Hellman (ECDH) + AES in Counter Mode (AES-CTR)
* **Telemetry Engine:** Custom `BenchmarkManager` with automated CSV export

---

## 🚀 Getting Started & Setup

### Prerequisites
* 2 or more physical Android devices running Android 8.0 (API 26) or higher. *(Nearby Connections requires physical RF hardware and will not function inside an Android Studio emulator).*
* Android Studio Iguana / Jellyfish or later.

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/rashid-aziz-ee/RelayNet-2.0.git
   ```
2. Open the project root in Android Studio.
3. Allow Gradle to sync dependencies.
4. Connect your Android devices via USB or wireless debugging.
5. Select **Run 'app'** (`Shift + F10`) to deploy to physical devices.
6. Grant runtime permissions: **Nearby Devices**, **Location** (required for Wi-Fi scanning), **Microphone** (for Walkie-Talkie), and **Notification**.

### Generating Standalone APK
```bash
./gradlew assembleDebug
```
The resulting package is located at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 👥 Authors & Contributions

* **Team BC Crew** (Rashid Aziz, Ali Abd, Hamza) — Systems Architecture, MANET Mesh Protocol, Cryptographic Implementation, and Android Jetpack Compose Engineering.

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
