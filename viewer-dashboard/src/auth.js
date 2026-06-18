/**
 * Authentication module for the Viewer Dashboard.
 *
 * Manages Firebase Email/Password authentication flow:
 * - Listens for auth state changes (onAuthStateChanged)
 * - Renders login form when unauthenticated
 * - Handles sign-in and sign-out
 */
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { getFirebaseAuth } from './firebase-init.js';

// DOM elements
const authContainer = document.getElementById('auth-container');
const dashboardContainer = document.getElementById('dashboard-container');
const loginForm = document.getElementById('login-form');
const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const loginBtn = document.getElementById('login-btn');
const authError = document.getElementById('auth-error');
const logoutBtn = document.getElementById('logout-btn');
const userEmailSpan = document.getElementById('user-email');

let currentUser = null;
let authReadyDispatched = false;

/**
 * Initialize authentication system.
 * Must be called after initFirebase().
 */
export function initAuth() {
  const auth = getFirebaseAuth();

  // Listen for auth state changes
  onAuthStateChanged(auth, (user) => {
    currentUser = user;
    if (user) {
      showDashboard(user);
    } else {
      authReadyDispatched = false;
      showLogin();
    }
  });

  // Login form submission
  loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    await handleLogin();
  });

  // Logout button
  logoutBtn.addEventListener('click', async () => {
    await handleLogout();
  });
}

/**
 * Handle user login with email and password.
 */
async function handleLogin() {
  const email = emailInput.value.trim();
  const password = passwordInput.value.trim();

  if (!email || !password) {
    showAuthError('Please enter email and password');
    return;
  }

  setLoginLoading(true);
  hideAuthError();

  try {
    const auth = getFirebaseAuth();
    await signInWithEmailAndPassword(auth, email, password);
    // onAuthStateChanged will handle the UI transition
  } catch (error) {
    handleAuthError(error);
  } finally {
    setLoginLoading(false);
  }
}

/**
 * Handle user logout.
 */
async function handleLogout() {
  try {
    const auth = getFirebaseAuth();
    await signOut(auth);
    // onAuthStateChanged will handle the UI transition
  } catch (error) {
    console.error('Logout error:', error);
  }
}

/**
 * Show the dashboard view for authenticated users.
 */
function showDashboard(user) {
  authContainer.classList.add('hidden');
  dashboardContainer.classList.remove('hidden');
  userEmailSpan.textContent = user.email;

  // Dispatch event so other modules know we're authenticated.
  // Guard against onAuthStateChanged firing multiple times (cached token + refresh).
  if (!authReadyDispatched) {
    authReadyDispatched = true;
    window.dispatchEvent(new CustomEvent('auth-ready', { detail: { user } }));
  }
}

/**
 * Show the login view for unauthenticated users.
 */
function showLogin() {
  dashboardContainer.classList.add('hidden');
  authContainer.classList.remove('hidden');
  emailInput.value = '';
  passwordInput.value = '';

  // Dispatch event so other modules know we're logged out
  window.dispatchEvent(new Event('auth-logout'));
}

/**
 * Set the login button to loading state.
 */
function setLoginLoading(loading) {
  loginBtn.disabled = loading;
  loginBtn.textContent = loading ? 'Signing In...' : 'Sign In';
}

/**
 * Show authentication error message.
 */
function showAuthError(message) {
  authError.textContent = message;
  authError.classList.remove('hidden');
}

/**
 * Hide authentication error message.
 */
function hideAuthError() {
  authError.classList.add('hidden');
}

/**
 * Handle Firebase auth errors with user-friendly messages.
 */
function handleAuthError(error) {
  let message = 'Authentication failed';

  switch (error.code) {
    case 'auth/user-not-found':
    case 'auth/wrong-password':
    case 'auth/invalid-credential':
      message = 'Invalid email or password';
      break;
    case 'auth/invalid-email':
      message = 'Invalid email format';
      break;
    case 'auth/too-many-requests':
      message = 'Too many attempts. Please try again later.';
      break;
    case 'auth/user-disabled':
      message = 'This account has been disabled.';
      break;
    default:
      message = error.message || 'Authentication failed';
  }

  showAuthError(message);
}

/**
 * Get the current authenticated user.
 */
export function getCurrentUser() {
  return currentUser;
}
