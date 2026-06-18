/**
 * WebRTC Consumer module for the Viewer Dashboard.
 *
 * Listens to Firebase RTDB /state/stream_status and /signaling nodes.
 * When stream_status is "live", initializes a browser RTCPeerConnection,
 * reads the SDP Offer from /signaling/offer, generates an SDP Answer,
 * and exchanges ICE candidates via the RTDB to establish a direct P2P tunnel.
 */
import { ref, onValue, off, set, push, child, get } from 'firebase/database';
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

/**
 * Initialize the WebRTC consumer.
 * Listens to state changes and automatically connects/disconnects.
 */
export function initWebRTC() {
  const database = getFirebaseDatabase();
  const stateRef = ref(database, 'state');

  stateUnsubscribe = onValue(stateRef, async (snapshot) => {
    const data = snapshot.val();
    if (data && data.stream_status === 'live') {
      await connectToBroadcaster(database);
    } else if (data && data.stream_status === 'offline') {
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

  try {
    // Read the offer from signaling
    const offerSnapshot = await get(signalingOfferRef);
    const offerData = offerSnapshot.val();

    if (!offerData || !offerData.sdp) {
      console.warn('No SDP offer available yet. Waiting...');
      // Set up listener for when offer becomes available
      offerUnsubscribe = onValue(signalingOfferRef, async (snap) => {
        const data = snap.val();
        if (data && data.sdp && !peerConnection) {
          await createAnswerAndConnect(data);
        }
      }, { onlyOnce: true });
      return;
    }

    await createAnswerAndConnect(offerData);
  } catch (error) {
    console.error('Error connecting to broadcaster:', error);
  }
}

/**
 * Create a peer connection, set the remote offer, create and send an answer.
 */
async function createAnswerAndConnect(offerData) {
  try {
    // Create RTCPeerConnection
    peerConnection = new RTCPeerConnection(ICE_SERVERS);

    // Handle ICE candidates from the broadcaster
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        sendViewerIceCandidate(event.candidate);
      }
    };

    peerConnection.onconnectionstatechange = () => {
      console.log('Connection state:', peerConnection.connectionState);
      if (peerConnection.connectionState === 'connected') {
        hideConnectionStatus();
      }
    };

    peerConnection.ontrack = (event) => {
      console.log('Received track:', event.track.kind);
      if (event.track.kind === 'video') {
        liveVideo.srcObject = event.streams[0];
      }
    };

    peerConnection.oniceconnectionstatechange = () => {
      console.log('ICE state:', peerConnection.iceConnectionState);
    };

    // Set remote description (the broadcaster's offer)
    const remoteOffer = new RTCSessionDescription({
      type: offerData.type,
      sdp: offerData.sdp,
    });
    await peerConnection.setRemoteDescription(remoteOffer);

    // Create answer
    const answer = await peerConnection.createAnswer();
    await peerConnection.setLocalDescription(answer);

    // Send the answer to RTDB
    await set(signalingAnswerRef, {
      type: answer.type,
      sdp: answer.sdp,
    });

    // Listen for ICE candidates from the broadcaster
    listenForBroadcasterIceCandidates();

    // Process any pending candidates
    while (pendingCandidates.length > 0) {
      const candidate = pendingCandidates.shift();
      await peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
    }

  } catch (error) {
    console.error('Error in WebRTC negotiation:', error);
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
