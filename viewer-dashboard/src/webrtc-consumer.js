/**
 * WebRTC Consumer module for the Viewer Dashboard.
 *
 * Logs diagnostics to RTDB /diagnostics/viewer for cross-platform debugging.
 * When stream_status is "live", initializes a browser RTCPeerConnection,
 * reads the SDP Offer from /signaling/offer, generates an SDP Answer,
 * and exchanges ICE candidates via the RTDB to establish a direct P2P tunnel.
 */
import { ref, onValue, off, set, push, child, get, serverTimestamp } from 'firebase/database';
import { getFirebaseDatabase } from './firebase-init.js';
import { hideConnectionStatus } from './telemetry.js';

// STUN servers (public Google STUN)
const ICE_SERVERS = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ],
};

const liveVideo = document.getElementById('live-video');

let peerConnection = null;
let signalingOfferRef = null;
let signalingAnswerRef = null;
let signalingCandidatesRef = null;
let offerUnsubscribe = null;
let broadcastIceUnsubscribe = null;
let stateUnsubscribe = null;
let pendingCandidates = [];
let offerProcessed = false;  // Guard against re-processing the same offer
let lastStreamStatus = null; // Track previous stream status to avoid duplicate transitions

// ─── Diagnostics logging helper ───────────────────────────────────────────
let diagnosticsRef = null;

function viewerLog(step, message, data = {}) {
  const db = getFirebaseDatabase();
  if (!diagnosticsRef) {
    diagnosticsRef = ref(db, 'diagnostics/viewer');
  }
  const entry = {
    timestamp: new Date().toISOString(),
    step,
    message,
    ...data,
  };
  console.log(`[ViewerDiag] ${step}: ${message}`, data);
  // Write to RTDB (non-blocking, fire-and-forget)
  set(ref(db, `diagnostics/viewer/${step}`), entry).catch((err) => {
    console.warn('Failed to write viewer diagnostic:', err);
  });
}

function viewerLogError(step, errorMessage, data = {}) {
  viewerLog(step, `ERROR: ${errorMessage}`, { ...data, level: 'error' });
}
// ───────────────────────────────────────────────────────────────────────────

/**
 * Initialize the WebRTC consumer.
 * Listens to state changes and automatically connects/disconnects.
 */
export function initWebRTC() {
  const database = getFirebaseDatabase();
  const stateRef = ref(database, 'state');

  viewerLog('INIT_WEBRTC', 'WebRTC consumer initialized, listening to /state');

  stateUnsubscribe = onValue(stateRef, async (snapshot) => {
    const data = snapshot.val();
    const status = data ? data.stream_status : 'unknown';
    // Guard: skip if status hasn't actually changed (onValue fires on every /state update including last_updated)
    if (status === lastStreamStatus) {
      return;
    }
    lastStreamStatus = status;
    viewerLog('STATE_CHANGE', `Stream status changed to: ${status}`);
    if (data && data.stream_status === 'live') {
      viewerLog('STATE_LIVE', 'Stream is live — connecting to broadcaster');
      await connectToBroadcaster(database);
    } else if (data && data.stream_status === 'offline') {
      viewerLog('STATE_OFFLINE', 'Stream is offline — disconnecting');
      disconnectFromBroadcaster();
    }
  });
}

/**
 * Connect to the broadcaster by reading its SDP offer and creating an answer.
 */
async function connectToBroadcaster(database) {
  // Clean up any existing connection first
  disconnectFromBroadcaster();

  signalingOfferRef = ref(database, 'signaling/offer');
  signalingAnswerRef = ref(database, 'signaling/answer');
  signalingCandidatesRef = ref(database, 'signaling/candidates');

  viewerLog('CONNECT_START', 'Connecting to broadcaster — reading offer');

  try {
    // Read the offer from signaling
    const offerSnapshot = await get(signalingOfferRef);
    const offerData = offerSnapshot.val();

    if (!offerData || !offerData.sdp) {
      viewerLog('CONNECT_NO_OFFER', 'No SDP offer available yet — waiting for persistent listener');
      // Persistent listener for when offer becomes available.
      // CRITICAL: Do NOT use { onlyOnce: true } — onValue with onlyOnce fires
      // immediately with current (null) data and unsubscribes before the offer arrives.
      // Use a persistent onValue with an offerProcessed guard flag instead.
      if (offerUnsubscribe) {
        offerUnsubscribe(); // remove previous listener if any
      }
      offerUnsubscribe = onValue(signalingOfferRef, async (snap) => {
        const data = snap.val();
        if (data && data.sdp && !offerProcessed) {
          offerProcessed = true;
          viewerLog('CONNECT_OFFER_LATE', 'SDP offer arrived via persistent listener');
          await createAnswerAndConnect(data);
        }
      });
      return;
    }

    viewerLog('CONNECT_HAS_OFFER', `SDP offer found, type=${offerData.type}, length=${offerData.sdp.length}`);
    await createAnswerAndConnect(offerData);
  } catch (error) {
    viewerLogError('CONNECT_FAIL', 'Error connecting to broadcaster', { error: error.message });
  }
}

/**
 * Create a peer connection, set the remote offer, create and send an answer.
 */
async function createAnswerAndConnect(offerData) {
  viewerLog('ANSWER_START', 'Creating RTCPeerConnection and answering');
  try {
    // Create RTCPeerConnection
    peerConnection = new RTCPeerConnection(ICE_SERVERS);
    viewerLog('ANSWER_PC', 'RTCPeerConnection created');

    // Handle ICE candidates from the broadcaster
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        viewerLog('ANSWER_ICE_SEND', 'Viewer generated ICE candidate',
          { sdpMLineIndex: event.candidate.sdpMLineIndex, sdpMid: event.candidate.sdpMid });
        sendViewerIceCandidate(event.candidate);
      }
    };

    peerConnection.onconnectionstatechange = () => {
      viewerLog('CONNECTION_STATE', `Connection state: ${peerConnection.connectionState}`);
      if (peerConnection.connectionState === 'connected') {
        viewerLog('P2P_CONNECTED', 'WebRTC P2P connection established! 🎉');
        hideConnectionStatus();
      } else if (peerConnection.connectionState === 'failed' || peerConnection.connectionState === 'disconnected') {
        viewerLogError('CONNECTION_LOST', `Connection lost: ${peerConnection.connectionState}`);
      }
    };

    peerConnection.ontrack = (event) => {
      viewerLog('ON_TRACK', `Received ${event.track.kind} track`, { streams: event.streams.length });
      if (event.track.kind === 'video') {
        liveVideo.srcObject = event.streams[0];
        viewerLog('VIDEO_ATTACHED', 'Video track attached to <video> element');
      }
    };

    peerConnection.oniceconnectionstatechange = () => {
      viewerLog('ICE_STATE', `ICE connection state: ${peerConnection.iceConnectionState}`);
    };

    // Set remote description (the broadcaster's offer)
    viewerLog('ANSWER_SET_REMOTE', 'Setting remote description from broadcaster offer',
      { type: offerData.type, sdpLength: offerData.sdp.length });
    const remoteOffer = new RTCSessionDescription({
      type: offerData.type,
      sdp: offerData.sdp,
    });
    await peerConnection.setRemoteDescription(remoteOffer);
    viewerLog('ANSWER_REMOTE_OK', 'Remote description set successfully');

    // Create answer
    viewerLog('ANSWER_CREATE', 'Creating SDP answer');
    const answer = await peerConnection.createAnswer();
    viewerLog('ANSWER_CREATED', `SDP answer created, type=${answer.type}, length=${answer.sdp.length}`);

    await peerConnection.setLocalDescription(answer);
    viewerLog('ANSWER_LOCAL_OK', 'Local description set successfully');

    // Send the answer to RTDB
    await set(signalingAnswerRef, {
      type: answer.type,
      sdp: answer.sdp,
    });
    viewerLog('ANSWER_SENT', 'Answer written to RTDB /signaling/answer');

    // Listen for ICE candidates from the broadcaster
    viewerLog('ANSWER_ICE_LISTEN', 'Starting listener for broadcaster ICE candidates');
    listenForBroadcasterIceCandidates();

    // Process any pending candidates
    const pending = pendingCandidates.length;
    if (pending > 0) {
      viewerLog('ANSWER_PENDING_ICE', `Processing ${pending} pending ICE candidates`);
      while (pendingCandidates.length > 0) {
        const candidate = pendingCandidates.shift();
        await peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
      }
    }

    viewerLog('ANSWER_COMPLETE', 'WebRTC answer complete, waiting for P2P connection');
  } catch (error) {
    viewerLogError('ANSWER_FAIL', 'Error in WebRTC negotiation', { error: error.message, stack: error.stack });
    cleanup();
  }
}

/**
 * Listen for ICE candidates coming from the broadcaster via RTDB.
 */
function listenForBroadcasterIceCandidates() {
  const database = getFirebaseDatabase();
  const broadcasterCandidatesRef = ref(database, 'signaling/candidates/broadcaster');

  broadcastIceUnsubscribe = onValue(broadcasterCandidatesRef, (snapshot) => {
    const data = snapshot.val();
    if (!data || !peerConnection) return;

    Object.values(data).forEach((candidateData) => {
      if (candidateData && candidateData.candidate) {
        const candidate = new RTCIceCandidate({
          candidate: candidateData.candidate,
          sdpMLineIndex: candidateData.sdpMLineIndex || 0,
          sdpMid: candidateData.sdpMid || 'video',
        });

        if (peerConnection.remoteDescription) {
          peerConnection.addIceCandidate(candidate).catch((err) => {
            console.warn('Error adding ICE candidate:', err);
          });
        } else {
          // Queue candidate if remote description not yet set
          pendingCandidates.push(candidate);
        }
      }
    });
  });
}

/**
 * Send the viewer's ICE candidate to RTDB for the broadcaster to consume.
 */
async function sendViewerIceCandidate(candidate) {
  try {
    const database = getFirebaseDatabase();
    const viewerCandidateRef = child(
      ref(database, 'signaling/candidates'),
      'viewer'
    );
    await push(viewerCandidateRef, {
      candidate: candidate.candidate,
      sdpMLineIndex: candidate.sdpMLineIndex,
      sdpMid: candidate.sdpMid,
    });
  } catch (error) {
    console.error('Error sending ICE candidate:', error);
  }
}

/**
 * Disconnect from the broadcaster and clean up all resources.
 */
function disconnectFromBroadcaster() {
  cleanup();
  offerProcessed = false;

  // Reset video element
  liveVideo.src = '';
  liveVideo.classList.add('hidden');
}

/**
 * Clean up WebRTC peer connection and RTDB listeners.
 */
function cleanup() {
  if (peerConnection) {
    peerConnection.close();
    peerConnection = null;
  }

  if (offerUnsubscribe) {
    offerUnsubscribe();
    offerUnsubscribe = null;
  }

  if (broadcastIceUnsubscribe) {
    broadcastIceUnsubscribe();
    broadcastIceUnsubscribe = null;
  }

  pendingCandidates = [];
}

/**
 * Stop the WebRTC consumer and clean up all state.
 */
export function stopWebRTC() {
  if (stateUnsubscribe) {
    stateUnsubscribe();
    stateUnsubscribe = null;
  }
  cleanup();
}
