# SSH4P

Remote access and REST API plugin for [Paper](https://papermc.io/) Minecraft servers.

SSH4P exposes two independent communication channels:

- **SSH console** — SSH into your server and interact with the Minecraft console directly, with full command execution and public key authentication.
- **HTTP REST API** — programmatically query server status, dispatch commands, and read/write files over a lightweight HTTP API with bearer token auth.

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
  host-key-file: hostkey.ser  # Auto-generated on first SSH enable — do not delete
  max-sessions: 3         # Maximum concurrent SSH connections

http:
  enabled: false          # Set true to start the HTTP API server
  bind-address: 0.0.0.0
  port: -1                # HTTP port; use -1 to share the Minecraft port via multiplexing
  max-content-length: 1048576  # Max request body in bytes (default: 1 MB)
  endpoints:
    public-extensions:
      - txt
      - log               # Files with these extensions are served without authentication
    private-extensions:
      - json
      - yml               # Files with these extensions require a bearer token
```

**Port multiplexing (`port: -1`):** SSH4P sniffs the first bytes of each incoming connection to route SSH and HTTP traffic through the Minecraft port. No firewall rule changes are needed beyond what is already open for your server.

---

## Setting Up SSH Access

SSH4P uses **public key authentication only** — no passwords. Authorized keys are stored in `plugins/SSH4P/ssh_keys.json`.

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
    "last_login": 0,
    "public_key": "ssh-ed25519 AAAA... mc-admin"
  }
]
```

Paste the full contents of your `.pub` file as the `public_key` value. RSA, ECDSA, and Ed25519 keys are all supported.

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

Obtain a bearer token by authenticating with your SSH public key:

```
POST /api/auth
Content-Type: text/plain

<your public key in OpenSSH format>
```

Response: a token string. Include it in subsequent requests:

```
Authorization: Bearer <token>
```

Tokens expire after 24 hours.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth` | Public | Exchange SSH public key for a bearer token |
| `GET` | `/api/status` | Public | Server status (players, version, MOTD, TPS) |
| `POST` | `/api/command` | Required | Dispatch a console command (raw command text as body) |
| `GET` | `/files/<path>` | Depends on extension | Read a file from the plugins directory |
| `PUT` | `/files/<path>` | Required (private ext) | Write a file in the plugins directory |

File paths are relative to `plugins/SSH4P/` and `..` traversal is blocked.

### Example: check server status

```bash
curl http://your.server.ip:8080/api/status
```

```json
{
  "online": true,
  "players": 3,
  "max_players": 20,
  "version": "1.21.4",
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

---

## Commands

All commands require the `ssh4p.admin` permission or console access.

| Command | Description |
|---------|-------------|
| `/ssh4p` | Show help |
| `/ssh4p reload` | Reload `config.yml` without restarting the server |
| `/ssh4p purge <username\|all>` | Terminate active SSH session(s) |
| `/ssh4p debug` | Emit a debug log entry (useful for confirming log levels) |

---

## Security Notes

- **Firewall your SSH port.** If running SSH on a dedicated port (e.g. 2222), restrict access to trusted IPs if possible.
- **Protect `ssh_keys.json`.** Anyone whose public key appears in that file can execute arbitrary console commands.
- **Bearer tokens are not persisted.** They are cleared on server restart. Re-authenticate after restarts.
- **File write access** (`PUT /files/`) is restricted to paths under `plugins/SSH4P/` and requires a valid token for private-extension files.
- The host key in `hostkey.ser` is auto-generated on first use. Delete it only if you intentionally want to rotate the server's identity (clients will see a host key change warning).
