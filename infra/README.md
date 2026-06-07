# Kodi infrastructure

Production runs as two Docker containers — see **[DEPLOY.md](DEPLOY.md)** for the
full step-by-step Hetzner VPS walkthrough.

## Stack (current)

- **backend** — FastAPI: Gemini Live voice WebSocket (`/v1/live`) + SSE agent loop.
  Vector store is **Chroma**, embedded in the container (no Qdrant). All state
  (Chroma DB, search cache, registered devices) lives in the `backend_data`
  volume at `/app/data`.
- **caddy** — reverse proxy; terminates TLS with automatic Let's Encrypt certs,
  proxies HTTPS/WSS to `backend:8000`.

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

## Required env (`backend/.env`)

- `GEMINI_API_KEY` — interactive voice brain (Gemini Live)
- `ANTHROPIC_API_KEY` — briefing + scheduled tasks (background)
- `OPENAI_API_KEY` — Whisper STT fallback + Chroma/Mem0 embeddings
- `BRAVE_API_KEY` — optional, web search

`backend/.env` is gitignored. Copy it to the server manually (see DEPLOY.md §7).

## VPS layout

- Ubuntu 24.04, Hetzner CX22 (2 vCPU / 4 GB).
- Firewall: allow **22** (SSH), **80** (cert issuance), **443** (HTTPS) only.
- Containers use `restart: unless-stopped` — survive reboots automatically.

## Operations notes

See [OPS_SESSIONS_AND_LOGS.txt](OPS_SESSIONS_AND_LOGS.txt). Key point: session
state is in-process memory, so run **one** uvicorn worker (the compose enforces
`--workers 1`). Scaling to multiple workers needs shared session storage (Redis)
first.
