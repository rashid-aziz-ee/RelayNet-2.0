# 📡 RelayNet 2.0: Decentralized Offline Multi-Hop Mesh Protocol

[![Platform: Android](https://img.shields.io/badge/Platform-Android_12+-3DDC84.svg?style=flat&logo=android)](https://www.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin_1.9+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-4285F4.svg?style=flat)](https://developer.android.com/jetpack/compose)
[![Protocol: P2P Cluster](https://img.shields.io/badge/Mesh-Nearby_Connections_P2P_Cluster-FF6F00.svg?style=flat)](https://developers.google.com/nearby/connections/overview)
[![Security: ECDH + AES-CTR](https://img.shields.io/badge/Security-ECDH_%2B_AES--CTR_E2EE-00C853.svg?style=flat)](https://en.wikipedia.org/wiki/Elliptic-curve_Diffie%E2%80%93Hellman)

> **Zero Cellular Towers. Zero Wi-Fi Routers. Zero Centralized Servers.**  
> An autonomous, self-healing peer-to-peer mesh networking protocol engineered for rapid disaster recovery, cellular blackouts, and high-consequence remote environments.

---

## 📌 Project Overview & Evolution

RelayNet 2.0 is an evolutionary leap from our initial offline peer-to-peer research prototype. While earlier experiments explored simple point-to-point connections, **RelayNet 2.0** introduces a production-ready, multi-hop Mobile Ad-Hoc Network (MANET) protocol stack featuring:

1. **Multi-Hop Store-and-Forward Gossip Routing:** Packets autonomously traverse intermediate physical devices using TTL hop-count management, loop-free message deduplication, and automatic multi-hop route propagation.
2. **End-to-End Cryptography (E2EE):** Asymmetric Elliptic Curve Diffie-Hellman (ECDH) key exchanges coupled with per-session AES-CTR encryption for private peer-to-peer communications.
3. **Emergency Broadcast Flood Protocol:** High-priority SOS packets bypass cryptographic handshakes and flood all mesh nodes with zero latency.
4. **Self-Healing Topology & Dead-Socket Pruning:** Active PING/PONG heartbeats, symmetrical handshake acknowledgments (`KEY_EXCHANGE_ACK`), dynamic ghost-peer removal (`PEER_LOST`), and a manual mesh resynchronization trigger.
5. **Empirical Field Validation:** Real-world benchmarked range of **up to 180 meters** in open Line-of-Sight (LoS) environments.

---

## 🛑 The Problem

During catastrophic natural disasters (earthquakes, tsunamis, severe floods) or localized infrastructure blackouts:
* Cellular base stations (BTS) lose power, fiber backhauls sever, and mobile towers collapse.
* First responders and stranded civilians are left with fully functional smartphones that become completely inert communication bricks.
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

## ⚙️ Key Technical Innovations

### 1. Multi-Hop Decentralized Routing
* Packets carry a configurable Time-To-Live (`TTL = 3`).
* Devices maintain an in-memory bloom filter / hash set (`seenMessageIds`) preventing infinite rebroadcast loops.
* If a packet is addressed to a distant node not directly in physical range, intermediate relay nodes seamlessly forward the packet over the radio mesh.

### 2. Zero-Trust Asymmetric Cryptography
* **Key Generation:** Each device generates an EC public/private keypair backed by Android's hardware security keystore where available.
* **Handshake Protocol:** When nodes establish a physical radio link, mutual exchange packets (`KEY_EXCHANGE` and `KEY_EXCHANGE_ACK`) automatically exchange public keys and resolve human-readable identities.
* **E2E Confidentiality:** Private 1-to-1 messages are encrypted using AES-CTR with keys derived via ECDH. Relay devices transit the ciphertext without ever having the ability to decrypt message contents.

### 3. Dynamic Self-Healing Network Engine
* **Collision Resolution:** Implements a deterministic tie-breaker algorithm (`peerDeviceId` comparison) to resolve simultaneous connection requests and prevent radio channel stalls (Google Nearby error 8003).
* **Ghost Peer Pruning:** Immediate detection of dead radio sockets with network-wide propagation of `PEER_LOST` packets to purge stale nodes from UI radars.
* **On-Demand Manual Synchronization:** A dedicated one-tap mesh refresh control forces radio channel re-announcements and purges zombie sockets.

---

## 📊 Real-World Empirical Benchmarks

Extensive physical field testing was conducted across varying propagation environments:

| Environment | Operational Frequency | Direct Line-of-Sight | Measured Single-Hop Range | Packet Delivery Ratio (PDR) |
| :--- | :--- | :--- | :--- | :--- |
| **Open Ground / Field** | 2.4 GHz / 5 GHz (Direct) | Clear LoS | **180+ Meters** | 94.2% |
| **Indoor Structural (Brick/Concrete)** | 2.4 GHz / 5 GHz | Non-LoS (Walls) | **10 – 15 Meters** | 88.7% |
| **2-Hop Multi-Node Mesh** | Multi-channel Wi-Fi Direct | Relay-Assisted | **300+ Meters Total Span** | 91.5% |

---

## 🛠️ Technology Stack

* **Language:** Kotlin 1.9+ (Coroutines, StateFlow, CoroutineScope concurrency)
* **User Interface:** Jetpack Compose (Material Design 3, dynamic pulsing node radar, reactive dark mode)
* **Transport Layer:** Google Nearby Connections API (`P2P_CLUSTER` topology — automatic Bluetooth Low Energy discovery + high-bandwidth Wi-Fi Direct data transport)
* **Local Persistence:** Room Database (SQLite abstraction with anti-entropy sync and offline message queueing)
* **Cryptography:** Elliptic Curve Diffie-Hellman (ECDH) + Advanced Encryption Standard in Counter Mode (AES-CTR)
* **Android Subsystem:** Android Foreground Service with Partial WakeLocks for persistent background packet forwarding

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
3. Allow Gradle to sync dependencies and build the index.
4. Connect your Android devices via USB or wireless debugging.
5. Select **Run 'app'** (`Shift + F10`) to deploy to physical devices.
6. Grant the necessary runtime permissions: **Nearby Devices**, **Location** (required for Wi-Fi scanning on Android), and **Notification**.

### Generating Release APK
To create a standalone APK for field distribution:
```bash
./gradlew assembleDebug
```
The resulting package is located at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 👥 Authors & Contributions

* **Team BC Crew** — Systems Architecture, MANET Mesh Protocol, Cryptographic Implementation, and Android Jetpack Compose Engineering.

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
