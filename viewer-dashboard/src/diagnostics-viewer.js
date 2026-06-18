/**
 * Viewer Diagnostics Panel
 *
 * Reads from RTDB /diagnostics/{android,viewer} and displays the
 * latest handshake events in the diagnostics panel.
 *
 * Toggle visibility with the "Diagnostics" button or pressing D.
 */

import { ref, onValue, off } from 'firebase/database';
import { getFirebaseDatabase } from './firebase-init.js';

let androidUnsub = null;
let viewerUnsub = null;
let panelVisible = false;

const ANDROID_LOG = document.getElementById('diag-android-log');
const VIEWER_LOG = document.getElementById('diag-viewer-log');
const DIAG_PANEL = document.getElementById('diagnostics-panel');
const DIAG_TOGGLE = document.getElementById('diag-toggle');

export function initDiagnosticsViewer() {
  const db = getFirebaseDatabase();

  // Listen to Android diagnostics
  const androidRef = ref(db, 'diagnostics/android');
  androidUnsub = onValue(androidRef, (snapshot) => {
    const data = snapshot.val();
    if (!data) {
      ANDROID_LOG.textContent = '(no data from Android broadcaster yet)';
      return;
    }
    // Format the last 10 entries as a readable log
    const lines = [];
    Object.entries(data)
      .sort(([, a], [, b]) => (a?.timestamp || 0) - (b?.timestamp || 0))
      .slice(-10)
      .forEach(([key, entry]) => {
        const time = entry?.timestamp
          ? new Date(entry.timestamp).toLocaleTimeString()
          : '';
        const msg = entry?.message || key;
        const step = entry?.step || key;
        lines.push(`${time} [${step}] ${msg}`);
      });
    ANDROID_LOG.textContent = lines.join('\n') || '(empty)';
  });

  // Listen to Viewer diagnostics (our own logs)
  const viewerRef = ref(db, 'diagnostics/viewer');
  viewerUnsub = onValue(viewerRef, (snapshot) => {
    const data = snapshot.val();
    if (!data) {
      VIEWER_LOG.textContent = '(no data from viewer yet)';
      return;
    }
    const lines = [];
    Object.entries(data)
      .sort(([, a], [, b]) => {
        // Sort by timestamp (ISO string) or by key
        const tA = a?.timestamp || '';
        const tB = b?.timestamp || '';
        if (tA < tB) return -1;
        if (tA > tB) return 1;
        return 0;
      })
      .slice(-10)
      .forEach(([key, entry]) => {
        const time = entry?.timestamp || '';
        const msg = entry?.message || key;
        const step = entry?.step || key;
        lines.push(`${time} [${step}] ${msg}`);
      });
    VIEWER_LOG.textContent = lines.join('\n') || '(empty)';
  });

  // Toggle button
  DIAG_TOGGLE.addEventListener('click', togglePanel);

  // Keyboard shortcut: D key toggles
  document.addEventListener('keydown', (e) => {
    if (e.key === 'd' || e.key === 'D') {
      if (document.activeElement?.tagName !== 'INPUT') {
        togglePanel();
      }
    }
  });
}

function togglePanel() {
  panelVisible = !panelVisible;
  DIAG_PANEL.classList.toggle('hidden', !panelVisible);
  DIAG_TOGGLE.textContent = panelVisible
    ? '🛠 Hide Diagnostics'
    : '🛠 Diagnostics (D)';
}

export function stopDiagnosticsViewer() {
  if (androidUnsub) {
    off(ref(getFirebaseDatabase(), 'diagnostics/android'));
    androidUnsub = null;
  }
  if (viewerUnsub) {
    off(ref(getFirebaseDatabase(), 'diagnostics/viewer'));
    viewerUnsub = null;
  }
}
