# Kodi infrastructure

## Qdrant (vector store)

Mem0 runs inside the **Kodi FastAPI backend** (`backend/`) and uses Qdrant as the vector store. Start Qdrant on the VPS (or locally for development):

```bash
docker compose -f infra/docker-compose.yml up -d
```

Bind addresses are `127.0.0.1` so Qdrant is not exposed publicly; only processes on the host (or the backend container if you later dockerize the API) should reach `localhost:6333`.

## VPS layout (Hetzner CX21 or similar)

1. Ubuntu 24.04, firewall: allow **22** (SSH) and **443** (HTTPS) only.
2. Install Docker and run the compose file above.
3. Run the Kodi backend as a **non-root** systemd service (see `backend/deploy/kodi-backend.service.example`).
4. Terminate TLS with **Caddy** or **nginx** + Let’s Encrypt, reverse-proxying to `127.0.0.1:8000` (or your chosen uvicorn bind).

## Environment variables

Copy `backend/.env.example` to `/etc/kodi/backend.env` (or similar) on the server. Never commit real keys.

Required for full V1:

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY` (Whisper)
- `BRAVE_API_KEY` (optional until search is used)
- `QDRANT_URL` (e.g. `http://127.0.0.1:6333`)
- `MEM0_COLLECTION` (e.g. `kodi_memories`)

Optional:

- `OPENAI_API_KEY` also used by Mem0 default embedder if you use OpenAI embeddings (see `backend` config).
