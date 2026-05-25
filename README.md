# PrivateCallingApp: Serverless P2P VoIP & Video for Android

<div align="center">

[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.24-blue.svg)](https://kotlinlang.org/)
[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![WebRTC](https://img.shields.io/badge/WebRTC-P2P-orange.svg)](https://webrtc.org/)

🔐 **End-to-end encrypted, serverless, peer-to-peer calling for Android**

> No central server. No intermediary. No data collection. Just direct, encrypted calls over your Tailscale mesh network.

**[Quick Start](#-quick-start--building) | [Architecture](ARCHITECTURE.md) | [Contributing](CONTRIBUTING.md) | [Key Features](#-key-features) | [FAQ](#-faq)**

</div>

---

A secure, serverless peer-to-peer (P2P) camera and voice-over-IP (VoIP) application built for Android. The application enables direct, encrypted device-to-device communication over a Tailscale overlay network (or local IPv6 network) without depending on centralized signaling or coordination servers.

---

## ✨ Key Features

- 🔒 **End-to-End Encrypted**: DTLS-SRTP media channel + secp256r1 asymmetric keys.
- 🌐 **Serverless P2P**: No central server required; each device runs an embedded Ktor WebSocket signaling server.
- 🆔 **Identity Verification**: Short Authentication Strings (SAS) computed as `SHA-256` of concatenated public keys for out-of-band verification.
- 🔐 **Hardware Security**: Asymmetric identity keys generated and stored inside the Android Keystore.
- 📱 **Full HD Video**: Side-by-side local and remote video rendering with hardware acceleration.
- 🔊 **Crystal Audio**: Acoustic Echo Cancellation (AEC), Automatic Gain Control (AGC), and Noise Suppression (NS) dynamically adjusted based on hardware.
- 🚀 **Performance Optimized**: Low-latency event streaming via `MutableSharedFlow` (with `DROP_OLDEST` overflow strategy) and leak-proof coroutine lifecycle management.
- 🛡️ **Privacy First**: No telemetry, no cloud storage, local-only processing.

---

## 🏗️ Architecture & Core Modules

The application is designed using a decoupled, multi-module architecture to enforce separation of concerns, improve build caching, and ensure codebase maintainability:

```
                  ┌───────────────────────────────────────────────┐
                  │                    :app                       │
                  │  (MainActivity, Compose Theme, Navigation)    │
                  └───────┬───────────────────────────────┬───────┘
                          │                               │
                          ▼                               ▼
              ┌───────────────────────┐       ┌───────────────────────┐
              │    :feature-call      │       │   :feature-contacts   │
              │  (Call Screen UI/VM)  │       │ (Peer List & Add Peer)│
              └───────────┬───────────┘       └───────────┬───────────┘
                          │                               │
                          └───────────────┬───────────────┘
                                          │
                                          ▼
                               ┌───────────────────────┐
                               │     :core-storage     │
                               │  (Room DB, Peers Entity)│
                               └───────────┬───────────┘
                                           │
                                           ▼
                               ┌───────────────────────┐
                               │     :core-crypto      │
                               │  (SEC256R1, SAS Keys) │
                               └───────────┬───────────┘
                                           │
                                           ▼
                               ┌───────────────────────┐
                               │     :core-media       │
                               │ (WebRTC, AEC/AGC/NS)  │
                               └───────────┬───────────┘
                                           │
                                           ▼
                               ┌───────────────────────┐
                               │    :core-network      │
                               │(Ktor Server/WS Client)│
                               └───────────────────────┘
```

### 📦 Module Directory
*   **`:app`**: The application entry point (`MainActivity`), global Dependency Injection graph (Hilt), custom Material 3 Dark theme, and the lightweight navigation backstack.
*   **`:core-network`**:
    *   `TailscaleDetector`: Automated network interface scanner to locate local Tailscale IPv4 (`100.64.0.0/10`) and IPv6 (`fd7a:115c:a1e0::/48`) addresses.
    *   `SignalingServer` / `SignalingClient`: Runs an embedded Ktor HTTP + WebSocket server on port **55500** (safely isolated from WireGuard's default port 51820) and handles peer WebSocket clients.
    *   `SignalingService`: A `START_STICKY` foreground service managing the signaling lifecycle to ensure incoming calls are received.
*   **`:core-media`**: Manages WebRTC factories, hardware video capture rendering via `Camera2Enumerator`, and audio tracks configured with hardware Acoustic Echo Cancellation (AEC), Automatic Gain Control (AGC), and Noise Suppression (NS).
*   **`:core-crypto`**: Integrated with Android Keystore. Generates an elliptic curve `secp256r1` keypair for user identity pinning, and generates Short Authentication Strings (SAS) computed as `SHA-256` of concatenated public keys for secure MITM verification.
*   **`:core-storage`**: Room-based database layer storing peer directories, hostnames, and verified identities.
*   **`:feature-contacts`**: Direct contact listing and entry manager with integrated input validation.
*   **`:feature-call`**: Call state manager and 1:1 call rendering interface (renders local and remote video pipelines side-by-side).

---

## 🛠️ Toolchain & Tech Stack

Built with stable, industry-standard Android tools:

| Component | Technology | Version |
|-----------|------------|---------|
| **Language** | Kotlin | `1.9.24` |
| **Android Build** | AGP & Gradle | AGP `8.7.3` / Gradle `8.10` |
| **UI Framework** | Jetpack Compose | `1.5.14` (Compose BOM `2024.02.02`) |
| **Media** | WebRTC (Stream) | `1.1.2` |
| **Networking** | Ktor Server & Client | `2.3.12` |
| **Database** | Room | `2.6.1` |
| **DI** | Hilt | `2.51.1` |
| **Logging** | Timber | `5.0.1` |

---

## 💎 Design Decisions

### 📡 Serverless WebSocket Signaling
Unlike traditional WebRTC architectures that rely on centralized signaling servers, this application turns every device into an independent server.
1. When the app launches, it starts `SignalingService` as a persistent foreground service.
2. The service binds an embedded Ktor WebSocket server to port `55500` on the device's Tailscale IP interface.
3. When you dial a peer, the client opens a WebSocket connection directly to the peer's Ktor server.
4. Session Description Protocol (SDP) and ICE candidates are exchanged over this direct WebSocket connection, which is closed immediately after the WebRTC PeerConnection transitions to the connected state.

### 🧭 Pure Compose Backstack Navigation
To maintain build toolchain compatibility (and avoid the AGP 8.9.1+ requirements introduced by experimental Jetpack `Navigation3`'s transitive activity packages), the navigation system uses a pure Compose-state backstack:
*   A `mutableStateListOf` list of keys (`Contacts`, `Call(peerAddress)`) serves as the backstack.
*   Back-press handling is explicitly managed via Jetpack Compose's `BackHandler`, assuring clean pops and graceful call termination when backing out of active calling states.

### 🔒 Cryptographic MITM Prevention (SAS)
Since Signaling is serverless and occurs over open tunnels, verifying the identity of the peer is crucial.
*   Each device generates an asymmetric `secp256r1` keypair in the hardware-backed Android Keystore.
*   During the signaling phase, public keys are exchanged.
*   A **Short Authentication String (SAS)** is generated by hashing the concatenated public keys using SHA-256 and converting it into a readable 5-digit verification code.
*   Users verify this code out-of-band (e.g., via chat, voice comparison, or physical verification) to guarantee that no attacker is intercepting the WebRTC connection.

---

## 🔋 OEM Battery Saver Mitigations

Certain Android manufacturers (e.g., Xiaomi, Samsung, OPPO, Vivo, Huawei) employ aggressive power-saving protocols that will terminate background TCP/UDP sockets, stopping incoming calls.

To ensure your device is always available to receive incoming calls, configure these settings:
1.  **Auto-start**: Enable for this app in **Settings > Apps > Manage Apps > PrivateCallingApp**.
2.  **Battery Saver**: Select **No Restrictions** under battery optimization options.
3.  **Recents Lock**: Swipedown/long-press the app card in your device's recent apps list and tap the lock icon to prevent system sweep kills.
4.  For detailed device-specific walkthroughs, visit [Don't kill my app!](https://dontkillmyapp.com/).

---

## 🚀 Quick Start & Building

### Prerequisites
- Android SDK (API Level 24+)
- Tailscale Android client installed, authenticated, and running on both target devices.

### Setup & Installation (60 seconds)

1. **Clone the Repository**
   ```bash
   git clone https://github.com/imviren/PrivateCallingApp.git
   cd PrivateCallingApp
   ```

2. **Compile and Build APK**
   To compile the project and generate a debug APK, run:
   ```powershell
   # On Windows
   .\gradlew.bat assembleDebug
   
   # On Linux/macOS
   ./gradlew assembleDebug
   ```
   The output APK is generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

3. **Install APK**
   Install the build onto your devices:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📞 Call Setup Walkthrough

1.  **Initialize Tunnel**: Verify that Tailscale is running and displays your overlay address (usually a `100.x.y.z` range address).
2.  **Launch App**: Open the app and grant requested permissions (Camera, Microphone, Notifications). Verify that the signaling service status notification is visible in the status bar.
3.  **Add Peer**: On the contacts screen, add your peer's Tailscale IP address or their MagicDNS hostname.
4.  **Initiate Call**: Tap the Call button. The signaling pipeline will:
    *   Establish a direct Ktor WebSocket link on port `55500`.
    *   Negotiate local and remote SDP offers/answers.
    *   Gather ICE candidates (using direct/TURN fallback pathways).
    *   Initialize WebRTC media loops (AEC/AGC/NS) and render video feeds side-by-side.
5.  **Verify Integrity**: Compare the 5-digit SAS security code visible on both screens to verify the connection's integrity.

---

## ❓ FAQ

**Q: Why Tailscale instead of a traditional STUN/TURN/Signaling server?**  
A: Traditional WebRTC apps require STUN, TURN, and Signaling servers, representing infrastructure costs and privacy bottlenecks. Tailscale builds a secure WireGuard mesh overlay network, allowing direct IP-to-IP signaling and media streams without complex firewall traversal or intermediate servers.

**Q: Can I use this without Tailscale?**  
A: Yes, as long as both devices can reach each other directly over IP (e.g., local Wi-Fi, private IPv6, or alternative VPN tunnels). You simply enter the direct IP address of the peer.

**Q: Is there an iOS version?**  
A: Not currently. This app is natively optimized for Android, Compose, and Android Keystore.

**Q: Can I use this for group calling?**  
A: The current release is strictly optimized for private 1:1 calling.

---

## 👥 Contributing
We welcome contributions! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on code style, testing requirements, commit message formats, and branching strategies.

---

## 📞 Support & Contact
If you encounter any bugs or have feature suggestions, please open a ticket in our [GitHub Issues](https://github.com/imviren/PrivateCallingApp/issues) tracker.

---

## 🙏 Acknowledgments
- **Tailscale**: Secure mesh networking VPN.
- **WebRTC**: Real-time media communication framework.
- **Google**: Android Jetpack, Hilt, Room, and Compose libraries.
- **JetBrains**: Kotlin language and Netty engine.

---

## 📜 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
