/**
 * Firebase initialization module.
 *
 * Reads configuration from environment variables (VITE_FIREBASE_*) 
 * to avoid hardcoding credentials in source code.
 *
 * Required .env variables:
 *   VITE_FIREBASE_API_KEY
 *   VITE_FIREBASE_AUTH_DOMAIN
 *   VITE_FIREBASE_DATABASE_URL
 *   VITE_FIREBASE_PROJECT_ID
 *   VITE_FIREBASE_STORAGE_BUCKET
 *   VITE_FIREBASE_MESSAGING_SENDER_ID
 *   VITE_FIREBASE_APP_ID
 */
import { initializeApp } from 'firebase/app';
import { getAuth, connectAuthEmulator } from 'firebase/auth';
import { getDatabase, connectDatabaseEmulator } from 'firebase/database';

// Firebase configuration from environment variables
const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  databaseURL: import.meta.env.VITE_FIREBASE_DATABASE_URL,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

// Validate required config
const requiredKeys = ['apiKey', 'authDomain', 'databaseURL', 'projectId', 'storageBucket'];
for (const key of requiredKeys) {
  if (!firebaseConfig[key]) {
    console.error(`Firebase config error: ${key} is not set. Check your .env file.`);
  }
}

let app = null;
let auth = null;
let database = null;

/**
 * Initialize Firebase app and services.
 * Must be called before using any Firebase features.
 */
export function initFirebase() {
  if (app) return { auth, database };

  app = initializeApp(firebaseConfig);
  auth = getAuth(app);
  database = getDatabase(app);

  // Connect to emulators in development if configured
  if (import.meta.env.DEV && import.meta.env.VITE_USE_FIREBASE_EMULATORS === 'true') {
    connectAuthEmulator(auth, 'http://localhost:9099');
    connectDatabaseEmulator(database, 'localhost', 9000);
    console.log('Connected to Firebase emulators');
  }

  return { auth, database };
}

/**
 * Get the initialized Firebase Auth instance.
 */
export function getFirebaseAuth() {
  if (!auth) throw new Error('Firebase not initialized. Call initFirebase() first.');
  return auth;
}

/**
 * Get the initialized Firebase Database instance.
 */
export function getFirebaseDatabase() {
  if (!database) throw new Error('Firebase not initialized. Call initFirebase() first.');
  return database;
}

