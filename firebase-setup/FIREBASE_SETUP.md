# WaspStream - Firebase Setup Guide

This document walks through the manual Firebase Console configuration required to get the WaspStream system running.

## Prerequisites

- A Google account
- Access to the [Firebase Console](https://console.firebase.google.com)

## Step 1: Create a Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click **Create a project**
3. Enter a project name (e.g., `waspstream`)
4. Disable Google Analytics (optional, but recommended for free tier)
5. Click **Create project**

## Step 2: Enable Authentication

1. In the Firebase Console, navigate to **Authentication** > **Sign-in method**
2. Click **Email/Password**
3. Toggle **Enable** to ON
4. Click **Save**
5. Go to the **Users** tab and click **Add user**
6. Create an admin account (e.g., `admin@waspstream.local`)
7. Store this password securely — it's your login to the Viewer Dashboard

## Step 3: Configure Realtime Database

1. Navigate to **Realtime Database**
2. Click **Create Database**
3. Select a location closest to your region
4. Choose **Start in test mode** (we'll lock it down next)
5. Copy the database URL (looks like `https://waspstream-default-rtdb.firebaseio.com`)

### Apply Security Rules

Copy the contents of `database.rules.json` into the **Rules** tab and click **Publish**:

```json
{
  "rules": {
    "state": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "telemetry": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "signaling": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

These rules ensure:
- Only authenticated users can read/write any node
- The Broadcaster (authenticated via anonymous auth) and Viewer Dashboard (authenticated via Email/Password) both work

## Step 4: Configure Cloud Storage

1. Navigate to **Storage**
2. Click **Get started**
3. Select your location
4. Choose **Start in test mode**
5. Click **Done**

### Storage Security Rules

Switch to the **Rules** tab and set:

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /snapshots/{allPaths=**} {
      allow read, write: if request.auth != null;
    }
  }
}
```

## Step 5: Register Android App

1. Navigate to **Project Settings** > **General**
2. Under **Your apps**, click **Android icon** (or **Add app** > **Android**)
3. Package name: `com.waspstream.broadcaster`
4. App nickname: `WaspStream Broadcaster`
5. Debug signing certificate SHA-1 (optional for now)
6. Click **Register app**
7. Download `google-services.json`
8. Place it at: `android-broadcaster/app/google-services.json`

## Step 6: Register Web App (Viewer Dashboard)

1. Click **Add app** > **Web**
2. App nickname: `WaspStream Viewer Dashboard`
3. Click **Register app**
4. Copy the `firebaseConfig` object values
5. Create `viewer-dashboard/.env` with:

```env
VITE_FIREBASE_API_KEY=AIzaSy...
VITE_FIREBASE_AUTH_DOMAIN=waspstream.firebaseapp.com
VITE_FIREBASE_DATABASE_URL=https://waspstream-default-rtdb.firebaseio.com
VITE_FIREBASE_PROJECT_ID=waspstream
VITE_FIREBASE_STORAGE_BUCKET=waspstream.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=123456789
VITE_FIREBASE_APP_ID=1:123456789:web:abc123
```

## Step 7: Enable Anonymous Auth (for Broadcaster)

1. Navigate to **Authentication** > **Sign-in method**
2. Enable **Anonymous** sign-in
3. Click **Save**

## Step 8: Set Up GitHub Secrets (for CI/CD)

In your GitHub repository, go to **Settings** > **Secrets and variables** > **Actions** and add:

| Secret Name | Value |
|---|---|
| `VITE_FIREBASE_API_KEY` | From Step 6 |
| `VITE_FIREBASE_AUTH_DOMAIN` | From Step 6 |
| `VITE_FIREBASE_DATABASE_URL` | From Step 3 |
| `VITE_FIREBASE_PROJECT_ID` | From Step 1 |
| `VITE_FIREBASE_STORAGE_BUCKET` | From Step 6 |
| `VITE_FIREBASE_MESSAGING_SENDER_ID` | From Step 6 |
| `VITE_FIREBASE_APP_ID` | From Step 6 |

## Verification

After completing all steps:

1. **Android Broadcaster**: Build and install on a device. It should anonymously authenticate and begin writing telemetry data.
2. **Viewer Dashboard**: Run `npm run dev` in `viewer-dashboard/`. Open the browser, log in with the admin email/password from Step 2, and verify telemetry appears.
3. **RTDB**: Check the Firebase Console > Realtime Database to verify data is flowing under `/state`, `/telemetry`, and `/signaling`.
