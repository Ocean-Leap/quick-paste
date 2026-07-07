# Security policy

quick-paste is a self-hosted tool that moves clipboard content and files
between a small trusted group's devices. Because you run it yourself, **the
security of a deployment depends on how it is configured** — please read the
hardening notes below in addition to reporting any code vulnerabilities.

## Reporting a vulnerability

Please **do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting: on the repository's **Security**
tab → **Report a vulnerability**. That opens a private advisory visible only to
the maintainers.

Include what you'd expect: affected version/commit, a description, reproduction
steps, and the impact. We aim to acknowledge within a few days. As a small
self-hosted project there is no bug-bounty program, but credit is given in the
advisory unless you prefer to remain anonymous.

## Deploying safely

The origin is the sole authentication boundary, so a few configuration choices
are load-bearing:

- **Set real secrets.** `QP_SESSION_SECRET` (a long random value),
  `QP_DB_PASS`, and — for Google mode — the OAuth client secret must be set in
  `.env` (gitignored). `docker compose` refuses to start without the DB
  password, so it can never come up on a default credential.
- **Never enable `QP_DEV_AUTH_BYPASS` on a reachable deployment.** It disables
  authentication entirely and is for local development only.
- **Use HTTPS.** Front the server with the Cloudflare Tunnel (production) or
  Caddy's HTTPS listener (LAN). Session cookies are only marked `Secure` over
  HTTPS; serving auth over plain HTTP exposes the session.
- **Local (password) mode is single-owner** and only as strong as
  `QP_LOCAL_PASSWORD`. Anyone on the network with the password has full access;
  set `QP_TRUSTED_PROXY_COUNT` correctly so per-IP login rate limiting is not
  spoofable.
- **API tokens are full-access, per-user credentials.** Store them in the
  Keychain / extension settings, never in source control, and revoke them from
  the account screen if a device is lost.
- **Data at rest is not application-encrypted.** Blobs and text live on the
  host under `QP_STORAGE_DIR` and in PostgreSQL; use host-level disk encryption.
  End-to-end encryption is reserved but not implemented.

## Scope

In scope: authentication/authorization bypass, cross-user or cross-channel data
access (tenant isolation), SSRF, injection, secret leakage, and similar code
defects. Out of scope: issues that require an already-compromised host,
misconfiguration explicitly warned against above, and denial-of-service from a
trusted authenticated user (this is a small-trusted-group tool, not public
SaaS — see the spec's capacity assumptions).
