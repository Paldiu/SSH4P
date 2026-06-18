# SSH4P

Remote access and REST API plugin for [Paper](https://papermc.io/) Minecraft servers.

SSH4P exposes two independent communication channels:

- **SSH console** - SSH into your server and interact with the Minecraft console directly, with full command execution and public key authentication.
- **HTTP REST API** - programmatically query server status, dispatch commands, and read/write files over a lightweight HTTP API with bearer token auth.

Both channels can run on dedicated ports or share the Minecraft port via protocol multiplexing.

> Copyright (c) 2026 Paldiu / SimplexDev. All rights reserved.

---

## Requirements

- Paper 1.21+ (or a compatible fork)
- Java 25

No other plugins are required. All dependencies are bundled in the JAR.

---

## Installation

1. Download `ssh4p.jar` from the [SimplexDev Maven repository](https://oss.simplexdev.app/releases) (`app.simplexdev:ssh4p`) or build it from source with `./gradlew shadowJar`.
2. Place `ssh4p.jar` in your server's `plugins/` folder.
3. Start (or restart) the server. SSH4P generates its config files on first run:
   - `plugins/SSH4P/config.yml`
   - `plugins/SSH4P/ssh_keys.json`
4. Configure SSH and/or HTTP (see below), then run `/ssh4p reload` or restart the server.

---

## Configuration

`plugins/SSH4P/config.yml`

```yaml
ssh:
  enabled: false          # Set true to start the SSH server
  bind-address: 0.0.0.0  # Interface to listen on (0.0.0.0 = all interfaces)
  port: 2222              # SSH port; use -1 to share the Minecraft port via multiplexing
  host-key-file: hostkey.ser  # Auto-generated on first SSH enable - do not delete
  max-sessions: 3         # Maximum concurrent SSH connections

http:
  enabled: false          # Set true to start the HTTP API server
  bind-address: 0.0.0.0
  port: -1                # HTTP port; use -1 to share the Minecraft port via multiplexing
  max-content-length: 1048576  # Max request body in bytes (default: 1 MB)
  cors-origin: '*'        # Access-Control-Allow-Origin value (e.g. https://admin.example.com)
  endpoints:
    public-extensions:
      - txt
      - log               # Served without authentication
    private-extensions:
      - json
      - yml               # Require a bearer token
    # Any extension in NEITHER list is denied (403). This is default-deny.
```

**Port multiplexing (`port: -1`):** SSH4P sniffs the first bytes of each incoming connection to route SSH and HTTP traffic through the Minecraft port. No firewall rule changes are needed beyond what is already open for your server.

---

## Setting Up SSH Access

SSH4P uses **public key authentication only** - no passwords. Authorized keys are stored in `plugins/SSH4P/ssh_keys.json`.

### 1. Generate a key pair (if you don't have one)

```bash
ssh-keygen -t ed25519 -C "mc-admin"
```

This creates `~/.ssh/id_ed25519` (private) and `~/.ssh/id_ed25519.pub` (public).

### 2. Add your public key to ssh_keys.json

Open `plugins/SSH4P/ssh_keys.json` and add an entry:

```json
[
  {
    "username": "yourname",
    "last_login": null,
    "public_key": "ssh-ed25519 AAAA... mc-admin"
  }
]
```

Paste the full contents of your `.pub` file as the `public_key` value. `last_login` is stamped
automatically (ISO-8601 UTC) on each successful login; leave it `null` for new entries.

> **Only Ed25519 keys are accepted.** Generate one with `ssh-keygen -t ed25519` as shown above.
> RSA and ECDSA keys are rejected by both the SSH server and the HTTP login endpoint.

### 3. Enable SSH and reload

```yaml
# config.yml
ssh:
  enabled: true
  port: 2222
```

```
/ssh4p reload
```

### 4. Connect

```bash
ssh yourname@your.server.ip -p 2222
```

You will land in an interactive console session. Commands you type are dispatched on the Minecraft main thread and output is streamed back to your terminal.

---

## HTTP REST API

Enable the HTTP server in `config.yml` (`http.enabled: true`) and set a port.

### Authentication

Login is a **signed-challenge** flow — you prove possession of the private key that matches an
authorized public key. There is no password and the private key never leaves your machine.

`POST /api/auth/login` with a JSON body:

```json
{
  "publicKey": "ssh-ed25519 AAAA... mc-admin",
  "timestamp": "2026-06-17T16:00:00Z",
  "signature": "<base64( Ed25519-sign( \"ssh4p-auth:\" + timestamp ) )>"
}
```

- `timestamp` must be ISO-8601 UTC and within **5 minutes behind / 30 seconds ahead** of server time.
- `signature` is the base64-encoded Ed25519 signature over the ASCII bytes of the string
  `ssh4p-auth:<timestamp>`, produced with the private key for `publicKey`.
- Each signature is **single-use** — the server caches accepted signatures for the skew window and
  rejects replays, so a captured login body cannot be reused.

On success the server returns:

```json
{ "token": "<uuid>", "username": "yourname", "expiresAt": "2026-06-18T16:00:00Z" }
```

Send the token on subsequent requests:

```
Authorization: Bearer <token>
```

Tokens expire after 24 hours and are held in memory only (cleared on restart). Call
`DELETE /api/auth/session` with the `Authorization` header to log out early, or
`POST /api/auth/refresh` (with the header) to slide the expiry 24 hours forward and receive a fresh
`expiresAt`.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/login` | Public | Exchange a signed challenge for a bearer token |
| `POST` | `/api/auth/refresh` | Bearer | Slide the session expiry 24 hours forward |
| `DELETE` | `/api/auth/session` | Bearer | Invalidate the caller's bearer token |
| `GET` | `/api/status` | Public | Server status (online count, max, version, MOTD, TPS) |
| `POST` | `/api/command` | Bearer | Dispatch a console command (raw command text as body) |
| `GET` | `/files/<path>` | Depends on extension | Read a file or list a directory under `endpoints/` |
| `PUT` | `/files/<path>` | Bearer (private ext only) | Overwrite a file under `endpoints/` |

File paths are relative to `plugins/SSH4P/endpoints/`; traversal outside that directory is blocked.
File access is **default-deny by extension**: a GET serves `public-extensions` without a token,
requires a token for `private-extensions`, and returns `403 Forbidden` for any extension in neither
list. Directory listings are always readable.

### Example: check server status

```bash
curl http://your.server.ip:8080/api/status
```

```json
{
  "online": 3,
  "max": 20,
  "version": "git-Paper-...(MC: 1.21.4)",
  "motd": "A Minecraft Server",
  "tps": [19.98, 19.97, 19.95]
}
```

### Example: run a command

```bash
curl -X POST http://your.server.ip:8080/api/command \
  -H "Authorization: Bearer <token>" \
  -d "say Hello from the API"
```

The command is dispatched on the main thread as the console; the response is `202 Accepted` and does
**not** include command output. Use the SSH console if you need to see output.

---

## Commands

All commands require the `ssh4p.admin` permission or console access.

| Command | Description |
|---------|-------------|
| `/ssh4p` | Show help |
| `/ssh4p reload` | Reload `config.yml` and restart the SSH/HTTP pipelines (applies port/bind/extension changes) |
| `/ssh4p purge <username\|all>` | Terminate active SSH session(s) |
| `/ssh4p debug` | Emit a debug log entry (useful for confirming log levels) |

---

## Security Notes

- **The HTTP API is not encrypted.** SSH4P serves plain HTTP — there is no TLS. Because
  `POST /api/command` runs arbitrary console commands, a bearer token captured on the wire grants
  full server control. **Do not expose the HTTP port to an untrusted network.** Bind it to
  `127.0.0.1` and put it behind a reverse proxy that terminates TLS (nginx/Caddy), or restrict it by
  firewall to trusted hosts. SSH4P logs a prominent warning at startup if HTTP binds to a
  non-loopback address. The SSH channel, by contrast, is encrypted by the SSH protocol itself.
- **File access is default-deny by extension.** Only `public-extensions` are readable without a
  token; `private-extensions` require one; everything else is `403`. Put every extension you want
  reachable in exactly one of the two lists.
- **Symlinks inside `endpoints/` are followed.** Writes (`PUT`) are guarded — the resolved real path
  must stay within `endpoints/` — but reads still follow symlinks, so a symlink can expose a file
  outside the plugin folder over GET. Only place trusted symlinks there.
- **Restrict CORS for browser clients.** The default `cors-origin` is `*`; set it to your admin
  panel's origin if untrusted pages should not be able to call the API from a browser.
- **Firewall your SSH port.** If running SSH on a dedicated port (e.g. 2222), restrict access to trusted IPs if possible.
- **Protect `ssh_keys.json`.** Anyone whose public key appears in that file can execute arbitrary console commands.
- **Bearer tokens are not persisted.** They are cleared on server restart. Re-authenticate after restarts.
- **File write access** (`PUT /files/`) is restricted to paths under `plugins/SSH4P/endpoints/` and requires a valid token; only private-extension files may be written.
- The host key in `hostkey.ser` is auto-generated on first use. Delete it only if you intentionally want to rotate the server's identity (clients will see a host key change warning).
