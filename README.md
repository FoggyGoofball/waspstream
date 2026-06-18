# WaspStream — Vespiculture SCADA & Broadcasting System

A zero-cost, low-latency, WebRTC-based video surveillance and telemetry dashboard for vespiculture (wasp farming) operations.

**Architecture:** Broacaster Node (Android) → Firebase BaaS → Viewer Dashboard (GitHub Pages)

## System Overview

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────┐
│  Android Device  │────▶│  Google Firebase      │◀────│  GitHub Pages     │
│  (Broadcaster)   │     │  (Free Tier)          │     │  (Viewer SPA)     │
│                  │     │                       │     │                  │
│  CameraX         │     │  • Auth (Email/Anon)  │     │  • Login Screen   │
│  Motion Detector │     │  • RTDB (Signaling)   │     │  • Telemetry UI   │
│  WebRTC Peer     │     │  • Cloud Storage      │     │  • WebRTC Cons.   │
│  Battery Monitor │     └──────────────────────┘     └──────────────────┘
└─────────────────┘
```

## Repository Structure

```
waspstream/
├── android-broadcaster/      # Android app (Kotlin + CameraX + WebRTC)
│   ├── app/src/main/java/    # Source code
│   ├── app/build.gradle.kts  # Dependencies
│   └── app/google-services.json  # (place Firebase config here)
├── viewer-dashboard/         # SPA (Vite + Vanilla JS + Firebase SDK)
│   ├── src/                  # JS source modules
│   ├── index.html            # Entry HTML
│   └── .env                  # Firebase config (create from .env.example)
├── firebase-setup/           # Firebase configuration guides
│   ├── FIREBASE_SETUP.md     # Setup instructions
│   └── database.rules.json   # RTDB security rules
└── .github/workflows/        # CI/CD to GitHub Pages
```

## Quick Start

### 1. Firebase Setup

Follow [`firebase-setup/FIREBASE_SETUP.md`](firebase-setup/FIREBASE_SETUP.md) to:
- Create a Firebase project
- Enable Email/Password and Anonymous auth
- Configure Realtime Database with security rules
- Register the Android and Web apps
- Set up Cloud Storage

### 2. Android Broadcaster

```bash
# 1. Place google-services.json in android-broadcaster/app/
# 2. Open in Android Studio
# 3. Build and install on an Android device (API 26+)
```

### 3. Viewer Dashboard

```bash
cd viewer-dashboard

# Copy and fill in Firebase config
cp .env.example .env

# Install dependencies
npm install

# Run locally
npm run dev

# Build for production
npm run build
```

### 4. Deploy to GitHub Pages

```bash
# Push to main branch — the GitHub Action auto-deploys
git push origin main

# Or manually deploy:
npm run deploy
```

## State Machine

| State | Stream Status | WebRTC | Snapshots | Condition |
|---|---|---|---|---|
| **IDLE** | `offline` | Torn down | Every 5 min | No motion detected |
| **ACTIVE** | `live` | Connected | On transition | Motion detected, viewer connects |

- Cooldown: 60 seconds of no motion → return to IDLE
- Temperature alert: > 40°C triggers notification

## Database Schema

```json
{
  "state": {
    "stream_status": "offline | live",
    "latest_image": "https://...",
    "last_updated": 1687023400000
  },
  "telemetry": {
    "battery_level": 85,
    "temperature": 365,
    "last_updated": 1687023400000
  },
  "signaling": {
    "offer": { "type": "offer", "sdp": "..." },
    "answer": { "type": "answer", "sdp": "..." },
    "candidates": {
      "broadcaster": { "pushId": { "candidate": "..." } },
      "viewer": { "pushId": { "candidate": "..." } }
    }
  }
}
```

## Tech Stack

- **Android Broadcaster:** Kotlin, CameraX, WebRTC (google-webrtc), Firebase SDK
- **Backend:** Firebase Authentication, Realtime Database, Cloud Storage (all free tier)
- **Viewer Dashboard:** Vite, Vanilla JavaScript, Firebase JS SDK, GitHub Pages

## License

MIT
