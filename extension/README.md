# quick-paste browser extension

A Chrome/Edge (MV3) extension that sends to and reads from your quick-paste
instance **from any tab** — the one thing the installed PWA can't do, since a
PWA only captures when its own tab is focused.

- **Send** the current selection, a link, or an image via right-click; send the
  clipboard with a keyboard shortcut; or compose in the toolbar popup.
- **Read** recent history in the popup and copy an item back to your clipboard
  or download a file.

It's a quick-capture accelerator, not a full client — the PWA remains the
real-time surface. It talks to any quick-paste instance (yours or someone
else's) using a per-user **API token**, so it works with both Google and local
(password) auth modes and needs no changes to the server.

## Build

```bash
cd extension
npm install
npm run build      # → extension/dist/   (npm run watch to rebuild on change)
```

## Load it in Chrome

1. Open `chrome://extensions`, enable **Developer mode**.
2. **Load unpacked** → select `extension/dist`.
3. Pin the quick-paste icon, then click it → it opens **Settings** on first run.

## Connect it

1. In the quick-paste app, open the account menu → **API tokens** → create a
   token (scope `write`). Copy the secret — it's shown once.
2. In the extension **Settings**: enter your **Server URL**
   (e.g. `https://paste.example.com`) and the **token**, click **Test
   connection** (it should show your account and channel count), pick a
   **default channel**, set a **device label**, and **Save**. You'll be asked to
   grant the extension access to that one server origin.

## Use it

- **Right-click** selected text, a link, or an image → *Send … to quick-paste*
  (goes to your default channel; a notification confirms).
- **Keyboard shortcut** `Ctrl+Shift+V` (`⌘+Shift+V` on Mac) sends your
  clipboard. Rebind at `chrome://extensions/shortcuts`.
- **Toolbar popup**: type or paste (including a screenshot) and **Send**, or
  **Attach file**; scroll recent history and **Copy**/**Download** any item.

Files up to the server's simple-upload limit (`QP_SIMPLE_UPLOAD_MB`, default
20 MB) upload directly; larger files are handled by the app (resumable upload).

## Verify (what to click through)

With a running instance and the extension connected:

1. Select text on any page → right-click → *Send selection* → it appears in the
   PWA/stream in your default channel.
2. Copy something, press the shortcut → it appears as a clip from your device
   label.
3. Open the popup → the item is listed → **Copy** puts it back on your
   clipboard; a file clip **Download**s.
4. Right-click an image on a page → *Send image* → it uploads and appears as a
   file clip with a thumbnail.

## Notes / limits

- No live notifications in v1 — the popup fetches history on open. (MV3 service
  workers can't hold a WebSocket cheaply; deferred.)
- Clipboard reads happen in an offscreen document (MV3 requirement) and need the
  `clipboardRead` permission, already declared.
- The token is stored in `chrome.storage.local` (extension-private). Treat it
  like a password; revoke it in the app if a device is lost.
