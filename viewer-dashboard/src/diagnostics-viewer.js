/**
 * Viewer Diagnostics Panel
 *
 * Reads diagnostics from RTDB /diagnostics/android (batch format from
 * broadcaster) and displays in the diagnostics panel.
 *
 * Viewer-side diagnostics are console-only on this version, so the
 * viewer column shows a brief message.
 */

import { ref, onValue, off } from 'firebase/database';
import { getFirebaseDatabase } from './firebase-init.js';

let androidUnsub = null;
let panelVisible = false;
let lastUpdateTime = 0;

const ANDROID_LOG = document.getElementById('diag-android-log');
const VIEWER_LOG = document.getElementById('diag-viewer-log');
const DIAG_PANEL = document.getElementById('diagnostics-panel');
const DIAG_TOGGLE = document.getElementById('diag-toggle');
const ANDROID_AGE = document.getElementById('diag-android-age');
const VIEWER_AGE = document.getElementById('diag-viewer-age');

let ageInterval = null;

function formatEntry(entry) {
  const ts = entry.ts || '';
  const step = entry.step || '';
  const msg = entry.msg || '';
  const level = entry.level || 'info';

  const d = new Date(ts);
  const time = d instanceof Date && !isNaN(d) ? d.toISOString().replace('T', ' ').substring(0, 19) + 'Z' : '';

  // Extra data beyond ts/step/msg/level
  const extras = {};
  Object.entries(entry).forEach(([k, v]) => {
    if (!['ts', 'step', 'msg', 'level'].includes(k) && v !== undefined && v !== null) {
      extras[k] = typeof v === 'string' && v.length > 60 ? v.substring(0, 60) + '\u2026' : v;
    }
  });

  let line = `${time} [${step}] ${msg}`;
  if (Object.keys(extras).length > 0) {
    line += ` ${JSON.stringify(extras)}`;
  }

  let cls = 'diag-entry';
  if (level === 'error') cls += ' diag-error';
  if (level === 'warn') cls += ' diag-warn';

  return `<div class="${cls}">${escapeHtml(line)}</div>`;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

export function initDiagnosticsViewer() {
  const db = getFirebaseDatabase();

  // Listen to Android diagnostics (batch format)
  androidUnsub = onValue(ref(db, 'diagnostics/android'), (snapshot) => {
    const data = snapshot.val();
    lastUpdateTime = Date.now();
    if (!data) {
      ANDROID_LOG.innerHTML = '<div class="diag-entry diag-muted">(no data from Android broadcaster yet)</div>';
      return;
    }

    // New format: { log: [...entries], count, flushed_at }
    let entries = [];
    if (Array.isArray(data.log)) {
      entries = data.log;
    } else {
      // Fallback: treat data as keyed map
      entries = Object.values(data).filter(v => v && typeof v === 'object' && v.step);
    }

    if (entries.length === 0) {
      ANDROID_LOG.innerHTML = '<div class="diag-entry diag-muted">(no entries)</div>';
      return;
    }

    // Sort by ts ascending
    entries.sort((a, b) => (a.ts || 0) - (b.ts || 0));

    // Show last 20 entries
    const recent = entries.slice(-20);
    ANDROID_LOG.innerHTML = recent.map(formatEntry).join('\n');
    ANDROID_LOG.scrollTop = ANDROID_LOG.scrollHeight;
  });

  // Viewer diagnostics (console-only now)
  VIEWER_LOG.innerHTML = '<div class="diag-entry diag-muted">Viewer diagnostics are logged to browser console (F12).</div>';

  // Age updater
  if (ageInterval) clearInterval(ageInterval);
  ageInterval = setInterval(() => {
    if (lastUpdateTime === 0) {
      if (ANDROID_AGE) ANDROID_AGE.textContent = '';
      return;
    }
    const elapsed = Math.floor((Date.now() - lastUpdateTime) / 1000);
    const ageStr = elapsed < 60 ? `${elapsed}s ago` : `${Math.floor(elapsed / 60)}m ${elapsed % 60}s ago`;
    if (ANDROID_AGE) ANDROID_AGE.textContent = `Last update: ${ageStr}`;
  }, 1000);

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
    ? '\u{1F6E0} Hide Diagnostics'
    : '\u{1F6E0} Diagnostics (D)';
}

export function stopDiagnosticsViewer() {
  if (ageInterval) {
    clearInterval(ageInterval);
    ageInterval = null;
  }
  if (androidUnsub) {
    off(ref(getFirebaseDatabase(), 'diagnostics/android'));
    androidUnsub = null;
  }
}
