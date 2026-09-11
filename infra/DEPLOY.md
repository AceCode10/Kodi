# Deploying the Kodi backend to a Hetzner VPS (beginner walkthrough)

Goal: run the backend 24/7 on a cheap cloud server, reachable over HTTPS at your
own domain, so the Android app can connect. Everything runs in Docker; you bring
it up with one command and it survives reboots and crashes.

Architecture once finished:

```
Phone (Android)  --HTTPS/WSS-->  Caddy (TLS, port 443)  --HTTP-->  backend (FastAPI, port 8000)
                                       |                                |
                                  auto Let's Encrypt cert         Chroma + SQLite on disk volume
```

You only need three things: a Hetzner account, your domain, and ~30 minutes.

---

## 0. Before you start

- **Domain**: you own one. Pick the subdomain you'll use, e.g. `api.yourdomain.com`.
- **Keys**: `backend/.env` already has `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`,
  `OPENAI_API_KEY` filled in. You'll copy this file to the server. **Never commit it.**

---

## 1. Create the server

1. Sign up at https://console.hetzner.cloud
2. **New Project** → name it `kodi`.
3. **Add Server**:
   - Location: **Falkenstein** or **Nuremberg** (Germany — closest Hetzner region to Zambia).
   - Image: **Ubuntu 24.04**
   - Type: **CX22** (2 vCPU, 4 GB RAM, ~€4/mo) — plenty for one user.
   - SSH key: if you have one, add it (recommended). If not, Hetzner emails you a
     root password. Adding an SSH key is more secure and skips password prompts.
   - Name: `kodi-backend`
4. **Create & Buy now**. After ~30s you get a **public IPv4** address. Copy it.

---

## 2. Point your domain at the server

In your domain registrar's DNS settings, add one record:

| Type | Name  | Value (points to)        |
|------|-------|--------------------------|
| A    | `api` | `<your VPS IPv4 address>`|

(`Name = api` gives `api.yourdomain.com`. Use whatever subdomain you like.)

DNS can take a few minutes to an hour to propagate. Check from your PC:

```powershell
nslookup api.yourdomain.com
```

When it returns your VPS IP, continue. **TLS will fail until DNS resolves**, so
don't skip this.

---

## 3. Connect to the server

From your Windows PC (PowerShell):

```powershell
ssh root@<your VPS IPv4 address>
```

Type `yes` to accept the fingerprint. If you used a password, paste it (right-click
pastes in PowerShell). You're now on the server — the prompt changes to `root@kodi-backend`.

---

## 4. Install Docker (on the server)

Paste this whole block. It installs Docker + the compose plugin:

```bash
curl -fsSL https://get.docker.com | sh
```

Verify:

```bash
docker --version && docker compose version
```

Both should print a version. Docker is now running and starts on boot.

---

## 5. Lock down the firewall

Allow only SSH (22), HTTP (80, needed for cert issuance), HTTPS (443):

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
```

---

## 6. Get the code onto the server

Easiest: clone from your git remote (the repo lives on `feat/gemini-live-voice`).

```bash
cd /opt
git clone <your-repo-url> kodi
cd kodi
git checkout feat/gemini-live-voice
```

> No git remote yet? See **Appendix A** to copy files directly from your PC with `scp`.

---

## 7. Put your secrets and domain on the server

`backend/.env` is gitignored, so it is **not** in the clone. Copy it from your PC.
Open a **second** PowerShell window **on your PC** (not the SSH session):

```powershell
scp "C:\Users\Denny\3D Objects\APPS\Kodi\backend\.env" root@<VPS-IP>:/opt/kodi/backend/.env
```

Back in the **SSH session**, edit the Caddy config to use your real domain + email:

```bash
nano infra/Caddyfile
```

Change the two placeholders:
- `api.yourdomain.com` → your actual subdomain
- `you@example.com` → your email

Save in nano: `Ctrl+O`, `Enter`, then `Ctrl+X`.

---

## 8. Launch

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

First run builds the backend image (a few minutes) and Caddy fetches a TLS cert
automatically. Watch it come up:

```bash
docker compose -f infra/docker-compose.yml logs -f
```

Look for the backend listening on `:8000` and Caddy obtaining a certificate for
your domain. `Ctrl+C` stops following logs (containers keep running).

---

## 9. Verify it works

From your PC:

```powershell
curl https://api.yourdomain.com/v1/health
```

Expect: `{"status":"ok","version":"1.0.0","uptime_seconds":...}`

If you get a valid HTTPS response, the server is live. 🎉

---

## 10. Point the app at it

In the Kodi Android app → onboarding/Settings → **Backend URL**:

```
https://api.yourdomain.com
```

The app only accepts `https://` URLs (it derives the `wss://` voice socket from it).

Onboarding also asks for a **setup token**. Registration is never open, so you need
the value of `KODI_SETUP_TOKEN` from `backend/.env`. If you left it blank, the
backend generated one on first boot — read it back with:

```bash
docker compose exec backend cat /app/data/setup_token.txt
```

Register the device, then test: voice round-trip, a device tool, barge-in, and
fallback (the app drops to the SSE+Whisper path if the WebSocket fails).

---

## Day-to-day operations

All commands run from `/opt/kodi` on the server.

| Task | Command |
|------|---------|
| View logs | `docker compose -f infra/docker-compose.yml logs -f backend` |
| Restart | `docker compose -f infra/docker-compose.yml restart` |
| Stop | `docker compose -f infra/docker-compose.yml down` |
| Start | `docker compose -f infra/docker-compose.yml up -d` |
| Update after code change | `git pull && docker compose -f infra/docker-compose.yml up -d --build` |
| Update after editing `.env` | re-`scp` the file, then `... restart backend` |

**Reboots**: containers have `restart: unless-stopped`, so everything comes back
automatically after a server reboot. Nothing to do.

**Data safety**: registered devices, memories (Chroma), and the search cache live
in the `backend_data` Docker volume — they survive `down`/`up` and rebuilds. They
are deleted only if you run `docker compose ... down -v` (note the `-v`). Don't use
`-v` unless you want a clean slate.

---

## Appendix A — copy code without git

If you have no git remote, zip-free copy from your PC (PowerShell), excluding junk:

```powershell
# On the server first:  mkdir -p /opt/kodi
scp -r "C:\Users\Denny\3D Objects\APPS\Kodi\backend" root@<VPS-IP>:/opt/kodi/backend
scp -r "C:\Users\Denny\3D Objects\APPS\Kodi\infra"   root@<VPS-IP>:/opt/kodi/infra
```

(The build only needs `backend/` and `infra/`. Don't copy `backend/.venv` — delete
it on the server if it came along: `rm -rf /opt/kodi/backend/.venv`.)

---

## Appendix B — common problems

- **`curl` to the domain hangs or cert fails**: DNS A record not propagated yet, or
  port 80 blocked. Confirm `nslookup` returns the VPS IP and `ufw` allows 80/443.
- **Backend logs `Gemini not configured`**: `GEMINI_API_KEY` missing in the `.env`
  that's on the server. Re-`scp` it and restart.
- **App won't save the URL**: it must start with `https://` and the cert must be
  valid. Use the real domain, not the IP.
- **Voice connects then drops instantly**: check backend logs for a Gemini auth/model
  error — verify the API key and that billing is enabled on the Google account.
- **502 from Caddy**: backend container isn't healthy. `docker compose ... logs backend`.
