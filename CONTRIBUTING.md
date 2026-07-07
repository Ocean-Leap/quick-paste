# Contributing to quick-paste

Thanks for your interest. quick-paste is a small, self-hosted tool with a
deliberately tight scope (see the non-goals in `quick-paste-SPEC.md`) — the
functional specification is the source of truth, so please read the relevant
section before non-trivial work.

## Project layout

| Path | What |
|---|---|
| `server/` | Micronaut 4 / Java 17 backend (PostgreSQL, Flyway, WebSocket) |
| `pwa/` | Vite + React + TypeScript PWA |
| `extension/` | Chrome/Edge MV3 browser extension (vanilla TS + esbuild) |
| `shortcuts/` | iOS Shortcuts docs |
| `docker-compose.yml` | full stack: PostgreSQL + server + Caddy (+ optional tunnel) |
| `quick-paste-SPEC.md` | authoritative functional specification |

## Build & test

You need Docker (for Testcontainers and the stack), a JDK 17+, and Node 20+.

```bash
# Backend — tests run against a real PostgreSQL via Testcontainers
cd server && ./gradlew test
cd server && ./gradlew shadowJar        # fat JAR

# PWA
cd pwa && npm ci && npm run build        # runs tsc then vite build

# Extension
cd extension && npm ci && npm run typecheck && npm run build

# Full stack (needs a .env — copy .env.example first)
docker compose up --build
```

CI (`.github/workflows/ci.yml`) runs all of the above on every PR; please make
sure they pass locally first.

## Conventions

- **Spec-first.** Behavior changes should be reflected in `quick-paste-SPEC.md`
  in the same PR. New tunables are `QP_*` env vars documented in the spec and
  `.env.example` — never hardcode limits or credentials.
- **Backend**: thin controllers → services → repositories; every clip/file/item
  query is scoped to its owner (channel membership or `user_id`) at the
  repository layer. IDs are ULIDs; hashes are SHA-256. Add a new Flyway
  migration rather than editing an applied one.
- **Frontend**: strict TypeScript, function components + hooks, the shared
  carbon/ink design tokens; never cache clip/file content in the service worker.
- **Secrets never enter source control.** `.env` is gitignored; `.env.example`
  holds placeholders only.
- Keep commits focused and tests green. New behavior gets a test.

## Reporting bugs & vulnerabilities

Functional bugs → open an issue. Security issues → see
[`SECURITY.md`](./SECURITY.md) (private reporting, not a public issue).

By contributing you agree your work is licensed under the repository's MIT
license.
