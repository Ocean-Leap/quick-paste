# iOS / iPadOS Shortcuts (M2, SPEC §10.12)

Two Shortcuts give iPhone/iPad one-tap send and receive without opening the
browser. Both authenticate with a per-user **API token** (FR-4) — everything is
foreground and user-initiated (no background clipboard access exists on iOS).

## One-time setup

1. Open the PWA → user menu → **Account settings** → **API tokens** → create a
   token named e.g. `iphone` with scope `write`. Copy the secret — it is shown
   once. The value looks like `qp_ab12cd34.<43 chars>`.
2. Find your channel id: `Get Contents of URL` on
   `https://<host>/api/auth/me` with header
   `Authorization: Bearer <token>` returns your channels; the `id` of the one
   named `Private` is your personal stream. (Or hardcode a shared channel's id.)
3. In the recipes below, replace `<HOST>`, `<TOKEN>`, and `<CHANNEL>`.

> Store the token only inside the Shortcut (or in a Keychain-backed app).
> Never share Shortcuts containing your token (SPEC §9.3).

## Shortcut 1 — "Send to quick-paste"

Accepts Share-sheet input **or** the clipboard; sends text as a clip and
anything else as a file.

| # | Action | Configuration |
|---|--------|---------------|
| 1 | Receive input | *Shortcut input*: Text, URLs, Images, Media, Files. If there is no input: **Get Clipboard** |
| 2 | If | *Input* `has any value` of type Text/URL → text path, otherwise → file path |
| 3a | (text) Get Contents of URL | URL `https://<HOST>/api/channels/<CHANNEL>/clips` · Method `POST` · Headers: `Authorization: Bearer <TOKEN>`, `Content-Type: application/json` · Request Body (JSON): `type` = `text`, `text` = *Input as Text*, `origin` = `iphone` |
| 3b | (file) Get Contents of URL | URL `https://<HOST>/api/channels/<CHANNEL>/files` · Method `POST` · Header: `Authorization: Bearer <TOKEN>` · Request Body: **Form**, one field named `file` of type *File* = *Input* |
| 4b | Get Dictionary Value | key `fileId` from *Contents of URL* |
| 5b | Get Contents of URL | URL `https://<HOST>/api/channels/<CHANNEL>/clips` · `POST` · same auth/JSON headers · body: `type` = `file`, `fileId` = *Dictionary Value*, `origin` = `iphone` |
| 6 | Show Notification | "Sent to quick-paste" (optional) |

Enable **Show in Share Sheet** in the Shortcut's settings and optionally add it
to the Home Screen.

Notes:
- Files above the simple-upload limit (`QP_SIMPLE_UPLOAD_MB`, default 20 MB)
  are rejected with HTTP 413 — Shortcuts cannot speak the resumable protocol;
  use the PWA for very large files.
- Repeating a send within `QP_DEDUP_WINDOW_SEC` is a harmless no-op (FR-18).

## Shortcut 2 — "Get latest from quick-paste"

| # | Action | Configuration |
|---|--------|---------------|
| 1 | Get Contents of URL | URL `https://<HOST>/api/channels/<CHANNEL>/clips/latest` · Method `GET` · Header: `Authorization: Bearer <TOKEN>` |
| 2 | If | *Contents* `has any value` — an empty channel returns HTTP 204 with no body |
| 3 | Get Dictionary Value | key `type` |
| 4a | (text) Get Dictionary Value `text` → **Copy to Clipboard** | puts the newest clip on the iOS clipboard |
| 4b | (file) Get Dictionary Value `file.id` → Get Contents of URL `https://<HOST>/api/channels/<CHANNEL>/files/<file.id>` (GET + auth header) → **Save File** or **Copy to Clipboard** for images |
| 5 | Show Notification | e.g. the first 100 characters of the text (optional) |

Add it to the Home Screen or trigger it from the Action button / Back Tap.

## Troubleshooting

- `401 unauthenticated` — token revoked/expired or header malformed; mint a new
  token. The header is exactly `Authorization: Bearer qp_xxxx.yyyy`.
- `404 not_found` — wrong channel id, or you are not a member of that channel.
- `429 rate_limited` — respect `Retry-After`; Shortcuts in loops can trip FR-44.
