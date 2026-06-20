/**
 * Telemetry module for the Viewer Dashboard.
 *
 * Listens to Firebase RTDB /telemetry and /state nodes in real-time.
 * Renders battery percentage, temperature, stream status, and last updated time.
 * Applies conditional formatting when temperature exceeds 38.0°C.
 */
import { ref, onValue, off } from 'firebase/database';
import { getFirebaseDatabase } from './firebase-init.js';

// DOM elements
const batteryValue = document.getElementById('battery-value');
const batteryBar = document.getElementById('battery-bar');
const temperatureValue = document.getElementById('temperature-value');
const temperatureBar = document.getElementById('temperature-bar');
const streamStatus = document.getElementById('stream-status');
const statusBadge = document.getElementById('status-badge');
const statusText = document.getElementById('status-text');
const lastUpdated = document.getElementById('last-updated');
const staticImage = document.getElementById('static-image');
const liveVideo = document.getElementById('live-video');
const offlineOverlay = document.getElementById('offline-overlay');
const connectionStatus = document.getElementById('connection-status');

// Temperature threshold for warning (38.0°C in tenths = 380)
const TEMP_WARNING_THRESHOLD = 380;
const TEMP_CRITICAL_THRESHOLD = 400;

let telemetryUnsubscribe = null;
let stateUnsubscribe = null;

/**
 * Start listening to telemetry and state data from Firebase.
 * Called after auth-ready event.
 */
export function startTelemetry() {
  const database = getFirebaseDatabase();

  // Listen to /telemetry node
  const telemetryRef = ref(database, 'telemetry');
  telemetryUnsubscribe = onValue(telemetryRef, (snapshot) => {
    const data = snapshot.val();
    if (data) {
      updateTelemetryUI(data);
    }
  });

  // Listen to /state node
  const stateRef = ref(database, 'state');
  stateUnsubscribe = onValue(stateRef, (snapshot) => {
    const data = snapshot.val();
    if (data) {
      updateStateUI(data);
    }
  });
}

/**
 * Stop listening to telemetry and state data.
 */
export function stopTelemetry() {
  if (telemetryUnsubscribe) {
    telemetryUnsubscribe();
    telemetryUnsubscribe = null;
  }
  if (stateUnsubscribe) {
    stateUnsubscribe();
    stateUnsubscribe = null;
  }
}

/**
 * Update the telemetry UI elements with new data.
 */
function updateTelemetryUI(data) {
  const { battery_level, temperature, last_updated } = data;

  // Battery
  if (battery_level !== undefined) {
    batteryValue.textContent = `${battery_level}%`;
    batteryBar.style.width = `${battery_level}%`;

    // Color battery bar based on level
    batteryBar.style.backgroundColor = battery_level > 20 ? '#4CAF50' : '#f44336';
  }

  // Temperature (stored in tenths of °C, convert to °C)
  if (temperature !== undefined) {
    const celsius = temperature / 10;
    temperatureValue.textContent = `${celsius.toFixed(1)}°C`;

    // Temperature bar (max scale at 50°C)
    const barPercent = Math.min(100, (temperature / 500) * 100);
    temperatureBar.style.width = `${barPercent}%`;

    // Conditional formatting based on temperature
    if (temperature >= TEMP_CRITICAL_THRESHOLD) {
      // Critical: > 40.0°C — red
      temperatureValue.style.color = '#f44336';
      temperatureBar.style.backgroundColor = '#f44336';
      temperatureValue.classList.add('temp-critical');
    } else if (temperature >= TEMP_WARNING_THRESHOLD) {
      // Warning: 38.0°C - 40.0°C — orange
      temperatureValue.style.color = '#FFA000';
      temperatureBar.style.backgroundColor = '#FFA000';
      temperatureValue.classList.remove('temp-critical');
    } else {
      // Normal: < 38.0°C — green
      temperatureValue.style.color = '#4CAF50';
      temperatureBar.style.backgroundColor = '#4CAF50';
      temperatureValue.classList.remove('temp-critical');
    }
  }

  // Last updated
  if (last_updated) {
    lastUpdated.textContent = formatTimestamp(last_updated);
  }
}

/**
 * Update the UI based on stream state changes.
 */
function updateStateUI(data) {
  const { stream_status, latest_image, last_updated } = data;

  // Update status badge
  if (stream_status === 'live') {
    statusBadge.className = 'status-badge status-live';
    statusText.textContent = 'LIVE';
    streamStatus.innerHTML = `
      <span class="status-dot status-dot-large status-dot-live"></span>
      <span>Live</span>
    `;

     // Show video, hide static image and overlay
     staticImage.classList.add('hidden');
     liveVideo.classList.remove('hidden');
     offlineOverlay.classList.add('hidden');
     // Don't touch connectionStatus here — WebRTC controls it
  } else {
    statusBadge.className = 'status-badge status-offline';
    statusText.textContent = 'Offline';
    streamStatus.innerHTML = `
      <span class="status-dot status-dot-large status-dot-offline"></span>
      <span>Offline</span>
    `;

    // Show static image from URL if available, hide video
    if (latest_image) {
      staticImage.src = latest_image;
      staticImage.classList.remove('hidden');
    }
    liveVideo.classList.add('hidden');
    liveVideo.src = '';
    offlineOverlay.classList.remove('hidden');
    connectionStatus.classList.add('hidden');
  }
}

/**
 * Hide the connecting spinner once WebRTC connection is established.
 */
export function hideConnectionStatus() {
  connectionStatus.classList.add('hidden');
}

/**
 * Format a Unix timestamp (milliseconds) to a readable date/time string.
 */
function formatTimestamp(timestamp) {
  const date = new Date(timestamp);
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}
