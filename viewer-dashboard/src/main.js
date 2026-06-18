/**
 * WaspStream Viewer Dashboard - Application Entry Point
 *
 * Initializes Firebase, auth, telemetry, WebRTC, and the diagnostics viewer.
 */
import { initFirebase } from './firebase-init.js';
import { initAuth } from './auth.js';
import { startTelemetry } from './telemetry.js';
import { initWebRTC } from './webrtc-consumer.js';
import { initDiagnosticsViewer } from './diagnostics-viewer.js';

function bootstrap() {
  console.log('WaspStream SCADA starting...');
  initFirebase();
  initAuth();
  window.addEventListener('auth-ready', () => {
    console.log('User authenticated, initializing data streams...');
    startTelemetry();
    initWebRTC();
    initDiagnosticsViewer();
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrap);
} else {
  bootstrap();
}
