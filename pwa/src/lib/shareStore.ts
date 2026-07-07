// Tiny IndexedDB stash for Web Share Target payloads. The service worker
// writes the incoming share here and redirects to /?share=pending; the app
// picks it up on startup. Raw IndexedDB so it runs in both contexts, no deps.

const DB_NAME = 'qp-share';
const STORE_NAME = 'pending';
const SHARE_KEY = 'share';

export interface StoredShare {
  title: string;
  text: string;
  url: string;
  files: File[];
}

function openDb(): Promise<IDBDatabase> {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Could not open the share store.'));
  });
}

/** Stashes an incoming share, replacing any previous one. */
export async function putShare(share: StoredShare): Promise<void> {
  const db = await openDb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put(share, SHARE_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error('Could not store the share.'));
      tx.onabort = () => reject(tx.error ?? new Error('Could not store the share.'));
    });
  } finally {
    db.close();
  }
}

/** Reads and clears the stashed share; resolves null when there is none. */
export async function takeShare(): Promise<StoredShare | null> {
  const db = await openDb();
  try {
    return await new Promise<StoredShare | null>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const read = store.get(SHARE_KEY);
      let value: StoredShare | null = null;
      read.onsuccess = () => {
        value = (read.result as StoredShare | undefined) ?? null;
        store.delete(SHARE_KEY);
      };
      tx.oncomplete = () => resolve(value);
      tx.onerror = () => reject(tx.error ?? new Error('Could not read the share.'));
      tx.onabort = () => reject(tx.error ?? new Error('Could not read the share.'));
    });
  } finally {
    db.close();
  }
}
