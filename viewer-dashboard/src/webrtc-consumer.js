/**
 * WebRTC Consumer module for the Viewer Dashboard.
 *
 * Simplified for reliability:
 * - Listens to /state for "live" status
 * - Reads SDP offer from /signaling/offer
 * - Creates RTCPeerConnection, sets remote offer, creates answer
 * - Writes answer to /signaling/answer
 * - Exchanges ICE candidates via RTDB
 * - Console-only diagnostics (no RTDB writes from viewer)
 */
import { ref, onValue, onChildAdded, set, push, get } from 'firebase/database';
import { getFirebaseDatabase } from './firebase-init.js';
import { hideConnectionStatus } from './telemetry.js';

const ICE_SERVERS = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ],
};

const liveVideo = document.getElementById('live-video');

let peerConnection = null;
let stateUnsub = null;
let offerUnsub = null;
let broadcasterIceUnsub = null;
let lastStreamStatus = null;
let offerProcessed = false;
let isConnecting = false; // prevent re-entrant connection attempts

function log(msg, data = {}) {
  console.log(`[Viewer] ${msg}`, data);
}

function logErr(msg, data = {}) {
  console.error(`[Viewer] ERROR: ${msg}`, data);
}

/**
 * Initialize the WebRTC consumer.
 */
export function initWebRTC() {
  const db = getFirebaseDatabase();
  log('WebRTC consumer initialized, listening to /state');

  stateUnsub = onValue(ref(db, 'state'), async (snap) => {
    const data = snap.val();
    const status = data ? data.stream_status : 'unknown';

    if (status === lastStreamStatus) return;
    lastStreamStatus = status;
    log(`Stream status -> ${status}`);

    if (status === 'live') {
      await connectToBroadcaster(db);
    } else if (status === 'offline') {
      disconnectFromBroadcaster();
    }
  });
}

/**
 * Connect to broadcaster via WebRTC.
 */
async function connectToBroadcaster(db) {
  if (isConnecting) {
    log('Already connecting — skipping re-entrant call');
    return;
  }
  isConnecting = true;
  disconnectFromBroadcaster();
  offerProcessed = false;
  log('Connecting to broadcaster');

  const offerR = ref(db, 'signaling/offer');
  const answerR = ref(db, 'signaling/answer');

  try {
    // First try a one-time read
    const offerSnap = await get(offerR);
    const offerData = offerSnap.val();

    if (offerData && offerData.sdp) {
      log('Offer found via get()');
      await createAnswerAndConnect(db, offerData, answerR);
      isConnecting = false;
      return;
    }

    // No offer yet — subscribe persistently
    log('No offer yet, subscribing...');
    offerUnsub = onValue(offerR, async (snap) => {
      const data = snap.val();
      if (data && data.sdp && !offerProcessed) {
        offerProcessed = true;
        log('Offer arrived via onValue');
        offerUnsub(); // unsubscribe immediately
        offerUnsub = null;
        await createAnswerAndConnect(db, data, answerR);
      }
    });
  } catch (err) {
    logErr('Connection failed', { error: err.message });
  }
}

/**
 * Create answer and exchange ICE candidates.
 */
async function createAnswerAndConnect(db, offerData, answerR) {
  try {
    peerConnection = new RTCPeerConnection(ICE_SERVERS);
    log('RTCPeerConnection created');

    // ICE candidate -> RTDB
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        push(ref(db, 'signaling/candidates/viewer'), {
          candidate: event.candidate.candidate,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
          sdpMid: event.candidate.sdpMid,
        }).catch(() => {});
      }
    };

    peerConnection.oniceconnectionstatechange = () => {
      log(`ICE: ${peerConnection.iceConnectionState}`);
    };

    peerConnection.onconnectionstatechange = () => {
      log(`Connection: ${peerConnection.connectionState}`);
      if (peerConnection.connectionState === 'connected') {
        hideConnectionStatus();
      }
    };

    let videoTrackAssigned = false;

    peerConnection.ontrack = (event) => {
      log(`Track: ${event.track.kind}`, { trackId: event.track.id });
      if (event.track.kind === 'video' && !videoTrackAssigned) {
        videoTrackAssigned = true;
        const stream = event.streams?.[0] || new MediaStream([event.track]);
        liveVideo.srcObject = stream;
        liveVideo.classList.remove('hidden');
        // Only call play() if not already playing — prevents
        // "interrupted by a new load request" error.
        if (liveVideo.paused) {
          liveVideo.muted = true; // always muted to satisfy autoplay policy
          liveVideo.play().catch((err) => {
            logErr('play() failed despite mute', { error: err.message });
          });
        }
        log('Video attached');
      }
    };

    // Set remote offer
    await peerConnection.setRemoteDescription(
      new RTCSessionDescription({ type: offerData.type, sdp: offerData.sdp })
    );
    log('Remote description set');

    // Create + set local answer
    const answer = await peerConnection.createAnswer();
    await peerConnection.setLocalDescription(answer);
    log('Answer created');

    // Send answer to RTDB
    await set(answerR, { type: answer.type, sdp: answer.sdp });
    log('Answer sent');

    // Listen for broadcaster ICE candidates
    listenForBroadcasterIceCandidates(db);

  } catch (err) {
    logErr('Answer failed', { error: err.message });
    cleanup();
  }
}

/**
 * Listen for ICE candidates from the broadcaster.
 * Uses onChildAdded so each candidate is processed exactly once,
 * preventing InvalidStateError from re-injecting stale candidates.
 */
function listenForBroadcasterIceCandidates(db) {
  const candidatesRef = ref(db, 'signaling/candidates/broadcaster');
  broadcasterIceUnsub = onChildAdded(candidatesRef, (snap) => {
    const c = snap.val();
    if (!c || !c.candidate || !peerConnection) return;

    peerConnection.addIceCandidate(
      new RTCIceCandidate({
        candidate: c.candidate,
        sdpMLineIndex: c.sdpMLineIndex || 0,
        sdpMid: c.sdpMid || 'video',
      })
    ).catch((err) => logErr('Add ICE candidate failed', { error: err.message }));
  });
}

function disconnectFromBroadcaster() {
  cleanup();
  liveVideo.src = '';
  liveVideo.classList.add('hidden');
  log('Disconnected');
}

function cleanup() {
  if (peerConnection) { peerConnection.close(); peerConnection = null; }
  if (offerUnsub) { offerUnsub(); offerUnsub = null; }
  if (broadcasterIceUnsub) { broadcasterIceUnsub(); broadcasterIceUnsub = null; }
}

export function stopWebRTC() {
  log('Stopping WebRTC consumer');
  if (stateUnsub) { stateUnsub(); stateUnsub = null; }
  cleanup();
}
