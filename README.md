# ask-the-repo

A command-line tool and Slack bot that lets you ask natural-language
questions about git repositories and get answers grounded in the repo's
code and documentation.

## How it works

1. **Ingest** walks a repo, skips files git would ignore, chunks the
   remaining text (markdown-aware for docs, blank-line-block-aware for
   code, recursive-character for anything else), embeds each chunk with
   Ollama `nomic-embed-text` (or optionally Voyage `voyage-code-3`),
   and writes an index.
2. **Ask** embeds your question, picks the top-12 most similar chunks by
   cosine similarity, hands them to Claude (`claude-haiku-4-5`) with a
   strict "cite sources or say you don't know" prompt, and returns the
   answer plus a list of files considered.
3. **Slack bot** listens for @mentions, routes questions to the right
   repo's index, and posts answers back to the channel.

## Prerequisites

- **JDK 21** — `java -version` must report 21.x.
- **API keys**
  - `ANTHROPIC_API_KEY` (https://console.anthropic.com/)
  - `VOYAGE_API_KEY` (https://www.voyageai.com/) — only needed if using `EMBEDDING_PROVIDER=voyage`. By default, embeddings run locally via Ollama (see [Embedding providers](#embedding-providers)).
- **Slack tokens** (only for the bot)
  - `SLACK_BOT_TOKEN` (starts with `xoxb-`)
  - `SLACK_APP_TOKEN` (starts with `xapp-`)
- **Git provider tokens** (only for `sync` command)
  - `BITBUCKET_TOKEN` — Bitbucket Cloud app password (`username:app-password`) or Server HTTP access token
  - `GITHUB_TOKEN` — GitHub personal access token with `repo` scope

## Setup

```sh
git clone <repo-url>
cd ask-the-repo
cp .env.example .env
# fill in API keys (and optionally Slack tokens) in .env
./gradlew installDist
```

The `ask-the-repo` script in the repo root is a thin wrapper around the
`installDist` launcher. For convenience, add the repo root to your
`PATH` or copy the wrapper into `~/bin`.

## Usage

### Index a repo (local)

```sh
./ask-the-repo ingest --path /path/to/some/repo
```

Index is written to `/path/to/some/repo/.ask-the-repo/`. Consider adding
`.ask-the-repo/` to that repo's `.gitignore`.

### Index a repo (named / centralized)

```sh
./ask-the-repo ingest --path /path/to/some/repo --name my-project
```

Index is written to `~/.ask-the-repo/indexes/my-project/` (override with
`ASK_THE_REPO_INDEX_BASE`). Named indexes are what the Slack bot uses.

Re-running either form is incremental: unchanged files are skipped.

### Ask a question

```sh
# Using a repo-local index:
./ask-the-repo ask "how does X work?" --path /path/to/some/repo

# Using a named index:
./ask-the-repo ask "how does X work?" --name my-project
```

### Sync repos via API (no git clone)

```sh
# Sync all repos defined in repos.json:
./ask-the-repo sync

# Sync a specific repo:
./ask-the-repo sync --name my-project

# Run sync continuously every 30 minutes:
./ask-the-repo sync --interval 30
```

Files are fetched via the Bitbucket/GitHub REST API and never stored on
disk — only the vector index is persisted. This is ideal for private
repos where cloning onto the bot's host is a security concern.

#### repos.json

The sync command reads `~/.ask-the-repo/repos.json` to know which repos
to sync. Example:

```json
{
  "repos": [
    {
      "name": "backend-api",
      "provider": "bitbucket",
      "workspace": "acme-corp",
      "repo": "backend-api",
      "branch": "main",
      "channels": ["C06ABC123"]
    },
    {
      "name": "frontend-app",
      "provider": "github",
      "workspace": "acme-corp",
      "repo": "frontend-app",
      "branch": "main",
      "channels": ["C06DEF456", "C06GHI789"]
    }
  ]
}
```

Fields:
- `name` — index name (used in `--name` and in Slack `in <name>` syntax)
- `provider` — `bitbucket` or `github`
- `workspace` — Bitbucket workspace slug or GitHub org/user
- `repo` — repository slug
- `branch` — branch to index (default `main`)
- `channels` — Slack channel IDs that can query this repo. Empty = all channels.

### List named indexes

```sh
./ask-the-repo list
```

### Start the Slack bot

```sh
./ask-the-repo serve
```

Requires `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` in `.env`.

In Slack, mention the bot with a question:
- `@ask-the-repo how does authentication work?` — if only one repo is
  indexed, it searches that one.
- `@ask-the-repo how does auth work? in backend-api` — specify a repo
  by name when multiple are indexed.
- `@ask-the-repo help` — lists available repos.

### Slack app setup

1. Go to https://api.slack.com/apps and create a new app.
2. Under **Socket Mode**, enable it and generate an app-level token with
   `connections:write` scope. This is your `SLACK_APP_TOKEN`.
3. Under **OAuth & Permissions**, add bot token scopes:
   `app_mentions:read`, `chat:write`.
4. Under **Event Subscriptions**, subscribe to the `app_mention` bot
   event.
5. Install the app to your workspace. The bot token is your
   `SLACK_BOT_TOKEN`.
6. Add both tokens to `.env` and run `./ask-the-repo serve`.

## Embedding providers

ask-the-repo defaults to [Ollama](https://ollama.com) with `nomic-embed-text`
for embeddings — free, open-source, and runs locally. Voyage AI is
available as an optional paid alternative with slightly better code search
quality.

### Ollama (default)

```sh
# 1. Install Ollama
brew install ollama            # macOS
# or
curl -fsSL https://ollama.com/install.sh | sh   # Linux

# 2. Start the Ollama server (macOS: the app starts it automatically,
#    Linux install script sets up a systemd service)
ollama serve                   # if not already running

# 3. Pull the embedding model
ollama pull nomic-embed-text
```

That's it — no API key or `.env` configuration needed. Ollama serves on
`http://localhost:11434` by default.

Optional overrides in `.env`:

```sh
# OLLAMA_BASE_URL=http://localhost:11434   # default
# OLLAMA_MODEL=nomic-embed-text            # default
```

### Voyage AI (optional)

For higher-quality code embeddings, set `EMBEDDING_PROVIDER=voyage` and
provide a Voyage API key:

```sh
EMBEDDING_PROVIDER=voyage
VOYAGE_API_KEY=...
```

### EC2 / server setup for Ollama

```sh
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# The install script registers a systemd service that starts automatically.
# Pull the model:
ollama pull nomic-embed-text

# Verify the server is running:
systemctl status ollama

# If not started:
sudo systemctl enable --now ollama

# Test it:
curl http://localhost:11434/api/tags
```

Ollama listens on `http://localhost:11434` by default. If running Ollama
on a separate host, set `OLLAMA_BASE_URL=http://<ollama-host>:11434` in
your `ask-the-repo.env`.

### Trade-offs

- **Quality:** `nomic-embed-text` is slightly below Voyage `voyage-code-3`
  for code search, but the hybrid BM25 + vector retrieval compensates
  for most of the gap.
- **Speed:** Depends on your hardware. A CPU-only EC2 instance is slower
  than the Voyage API; a GPU instance is comparable.
- **Cost:** Ollama is free (no API fees), but requires compute resources.
  Voyage charges per token.
- **Index compatibility:** Switching providers requires re-ingesting all
  repos (vector dimensions and spaces differ).

## Deployment

Two supported paths:
- **[Docker](#docker)** — a single container image, optionally with PostgreSQL for stateless storage. Best for ECS, Kubernetes, or any container host.
- **[VM / EC2 with systemd](#vm--ec2-with-systemd)** — the traditional setup: unzip the distribution and run under systemd. Best when you already have a Linux host you manage directly.

### What you need

- A host with **Java 21** (JRE) — or just Docker if you use the container path.
- API keys: `ANTHROPIC_API_KEY`. Embeddings default to Ollama (see [Embedding providers](#embedding-providers)).
- Git provider token(s): `GITHUB_TOKEN` and/or `BITBUCKET_TOKEN` (or a GitHub App — see [GitHub App auth](#github-app-auth)).
- Slack app tokens: `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` (see [Slack app setup](#slack-app-setup) below).

## Docker

A `Dockerfile` and `docker-compose.yml` are included. The Dockerfile uses a two-stage build (Gradle + JRE) and defaults to `serve`.

### Quick start with Compose (app + pgvector)

The easiest way to run everything locally. Compose spins up the app alongside a `pgvector/pgvector:pg16` Postgres container, wires them together, and waits for the DB to be healthy before starting the app.

```sh
docker compose up --build
```

The app reads secrets from your `.env` (API keys, Slack tokens, GitHub auth), but `DATABASE_URL` is overridden in `docker-compose.yml` to point at the bundled `db` service — no need to edit `.env`.

Postgres data is persisted in the `pgdata` named volume. To wipe it: `docker compose down -v`.

### Build and run (standalone)

```sh
docker build -t ask-the-repo .

docker run --rm -p 3000:3000 \
  -e ANTHROPIC_API_KEY=... \
  -e SLACK_BOT_TOKEN=xoxb-... \
  -e SLACK_APP_TOKEN=xapp-... \
  -e ADMIN_USER=admin \
  -e ADMIN_PASSWORD=<change-me> \
  -e WEBHOOK_SECRET=<random-string> \
  ask-the-repo
```

Or pass your `.env` file directly:

```sh
docker run --rm -p 3000:3000 --env-file .env ask-the-repo
```

The admin UI is available at `http://localhost:3000/admin`.

### One-off commands

The default command is `serve`, but you can override it:

```sh
docker run --rm --env-file .env ask-the-repo list
docker run --rm --env-file .env ask-the-repo sync --name my-project
```

### Persistence

By default, indexes live on the container's filesystem and are lost when the container is removed. Two options:

**Option A — Volume mount** (single-host deployments):

```sh
docker run --rm -p 3000:3000 \
  -v ask-the-repo-data:/root/.ask-the-repo \
  --env-file .env \
  ask-the-repo
```

**Option B — PostgreSQL** (recommended for ECS, Kubernetes, or any stateless orchestrator): see [PostgreSQL storage](#postgresql-storage) below.

## PostgreSQL storage

When `DATABASE_URL` is set, indexes and the repo registry are stored in PostgreSQL instead of the filesystem. Required for stateless container deployments.

### Requirements

- PostgreSQL 13+ with the **pgvector** extension.
  - AWS RDS / Aurora PostgreSQL: pgvector is available as an installable extension.
  - Local: `brew install pgvector` (macOS) or install the server package and run `CREATE EXTENSION vector;`.

### Setup

```sql
CREATE DATABASE ask_the_repo;
\c ask_the_repo
CREATE EXTENSION vector;
```

Then set in `.env`:

```sh
DATABASE_URL=postgres://user:password@host:5432/ask_the_repo
```

Tables are created automatically on first run. Switching between filesystem and PostgreSQL storage requires re-ingesting repos.

## GitHub App auth

As an alternative to `GITHUB_TOKEN`, you can authenticate as a GitHub App. This is preferred for org-wide use: fine-grained permissions, higher rate limits, and no personal token tied to an individual.

1. Create a GitHub App (Settings → Developer settings → GitHub Apps → New).
2. Grant **Contents: Read-only** permission.
3. Install the app on the repos/org you want indexed.
4. On the app page, click **Generate a private key** — a `.pem` file downloads.
5. Set in `.env`:

```sh
GITHUB_APP_ID=<app-id>
GITHUB_APP_INSTALLATION_ID=<installation-id>
GITHUB_APP_PRIVATE_KEY=<PEM string, base64-encoded PEM, or path to .pem file>
```

The installation ID is visible in the URL when you click "Configure" on the installed app: `.../installations/<id>`.

## VM / EC2 with systemd

### Build and deploy

```sh
# 1. Build the distribution zip locally
./gradlew assembleDist

# 2. Copy to the server
scp build/distributions/ask-the-repo-0.1.0.zip app@<server>:~/

# 3. SSH in, extract, and set up
ssh app@<server>
rm -rf ask-the-repo-0.1.0
unzip ask-the-repo-0.1.0.zip
```

### Configure environment

Create a `.env` file on the server (e.g. `~/ask-the-repo.env`):

```sh
ANTHROPIC_API_KEY=...
# VOYAGE_API_KEY=...              # only if EMBEDDING_PROVIDER=voyage
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
GITHUB_TOKEN=...
BITBUCKET_TOKEN=...
ADMIN_USER=admin
ADMIN_PASSWORD=<change-me>
SYNC_INTERVAL_MINUTES=30
WEBHOOK_SECRET=<random-string>
# EMBEDDING_PROVIDER=voyage       # uncomment to use Voyage instead of Ollama (default)
```

### Run with systemd

Create `~/.config/systemd/user/ask-the-repo.service`:

```ini
[Unit]
Description=ask-the-repo

[Service]
WorkingDirectory=%h
EnvironmentFile=%h/ask-the-repo.env
ExecStart=%h/ask-the-repo-0.1.0/bin/ask-the-repo serve
Restart=on-failure

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload
systemctl --user enable --now ask-the-repo.service
```

The admin UI is now available at `http://<server-ip>:3000/admin`
(login with `ADMIN_USER` / `ADMIN_PASSWORD` from the env file).

### Configure repos and channels

1. Open `http://<server-ip>:3000/admin` in a browser.
2. Log in with the admin credentials.
3. Click **Add Repo** and fill in the provider, workspace, repo name, branch, and Slack channel IDs.
4. Click **Sync** on a repo to index it immediately.
5. The Slack bot is live — users can `@ask-the-repo` in the configured channels.

### Updating

```sh
# Build locally and deploy
./gradlew assembleDist
scp build/distributions/ask-the-repo-0.1.0.zip app@<server>:~/

# On the server
rm -rf ask-the-repo-0.1.0
unzip ask-the-repo-0.1.0.zip
systemctl --user restart ask-the-repo.service
```

### Webhook-triggered sync

Instead of polling with `SYNC_INTERVAL_MINUTES`, you can configure
GitHub or Bitbucket to POST to a webhook on push:

```
POST http://<server-ip>:3000/webhook/<repo-name>?secret=<WEBHOOK_SECRET>
```

The secret can also be sent as an `X-Webhook-Secret` header.

**GitHub:** In your repo settings, add a webhook with:
- Payload URL: `https://ask-the-repo.example.com/webhook/my-repo?secret=...`
- Content type: `application/json`
- Events: Just the push event

**Bitbucket:** In your repo settings, add a webhook with:
- URL: `https://ask-the-repo.example.com/webhook/my-repo?secret=...`
- Triggers: Repository push

The webhook returns immediately and runs the sync in the background.
Duplicate requests while a sync is running are ignored.

### HTTPS with a reverse proxy

The admin UI uses HTTP basic auth, so credentials are sent in plain text.
For production, put it behind a reverse proxy with TLS. Example with Caddy:

```
# /etc/caddy/Caddyfile
ask-the-repo.internal.example.com {
    reverse_proxy localhost:3000
}
```

Caddy auto-provisions TLS certificates. For nginx, add a `proxy_pass`
block and configure your own certificate (e.g. via Let's Encrypt / certbot).

### Exit codes

- `1` — usage error (unknown command, missing argument).
- `2` — missing API key or Slack token.
- `3` — no index found at the target path.

## Stack

- Kotlin 2.x on JDK 21, built with Gradle Kotlin DSL.
- `kotlinx.serialization-json` — Anthropic/Voyage request+response
  shapes.
- `com.slack.api:bolt-socket-mode` — Slack bot via Socket Mode (no public
  URL needed).
- HTTP via the JDK's `java.net.http.HttpClient`.
- Tests via `kotlin.test` (stdlib).

## On-disk index format

Index files live under `<repo>/.ask-the-repo/` (local mode) or
`~/.ask-the-repo/indexes/<name>/` (named mode):

- `manifest.json` — repo path, model, embedding model, dimension,
  timestamps.
- `files.json` — map of relative path to `{ contentHash, chunkIds }`.
  This is what makes re-ingest incremental.
- `chunks.jsonl` — one JSON chunk per line: `id`, `filePath`, `startLine`,
  `endLine`, `language`, `text`.
- `vectors.bin` — little-endian packed floats: `[int32 dim]` header,
  then `N * dim` float32s in the same order as `chunks.jsonl`.

The whole thing is rewritten on each ingest — no in-place edits.

---

## Design decisions

Non-obvious choices and their rationale.

- **Gradle 8.10.2 wrapper.** The Kotlin 2.x plugin ecosystem has the
  longest-standing track record on Gradle 8.x, and a reproducible wrapper
  matters more than the latest Gradle features.
- **`kotlin("test")` via JUnit 5 default.** Kotlin 2.x resolves
  `kotlin("test")` against `kotlin-test-junit5` when JUnit 5 is on the
  runtime classpath; `useJUnitPlatform()` makes that explicit.
- **Hand-rolled `.env` parser.** ~25 lines. Strips matching quotes,
  ignores comments and blanks, does not support variable expansion.
  Enough for this tool.
- **Gitignore matcher is deliberately partial.** It handles `*`, `**`,
  `?`, `/`-anchoring, trailing `/`, negation `!`, and comments. It does
  **not** handle character classes (`[abc]`), backslash escapes, or
  nested `.gitignore` files. The built-in ignores list
  (`.git`, `node_modules`, `target`, etc.) closes the biggest gaps.
- **Code chunking packs blank-line-separated blocks greedily up to ~1500
  chars / ~60 lines.** It never splits mid-block. Hard cap of 3000 chars
  is enforced by a final pass that splits anything bigger. Chunks under
  80 chars are dropped.
- **Markdown chunker splits on H1+H2, then on H3 if a section is too
  big.** Carries a `# Title > ## Section` prefix into sub-chunks so
  context is not lost.
- **Vectors stored as a single packed little-endian float blob**, not in
  per-chunk JSON. For the expected repo size (hundreds to low thousands
  of chunks) this is 4 * dim * N bytes — tens of MB at most — and loads
  instantly.
- **Hybrid BM25 + vector retrieval.** Cosine similarity alone misses
  exact keyword matches; BM25 alone misses semantic similarity. The
  hybrid scorer normalises both into [0,1] and combines them with a
  tunable alpha weight, giving better recall than either alone.
- **Pluggable embedding provider (Voyage / Ollama).** `EmbeddingClient`
  interface with `VoyageEmbeddingsClient` and `OllamaEmbeddingsClient`
  implementations. Ollama uses `nomic-embed-text` with the model's
  `search_document:` / `search_query:` prefix convention for optimal
  retrieval quality. Switching providers requires re-indexing since
  vector dimensions and spaces differ.
- **Voyage batching at 64 chunks per request, retry 1s / 2s / 4s on 429
  and 5xx.**
- **Anthropic client supports both streaming and non-streaming.** The
  CLI uses non-streaming (simpler, Haiku latency is acceptable). The
  Slack bot streams and updates the message in-place every ~1 second for
  faster perceived latency.
- **Slack Bolt SDK with Socket Mode.** Socket Mode connects via WebSocket
  so no public URL is needed — ideal for an internal company tool.
  Writing the Socket Mode protocol from scratch would be a significant
  undertaking, justifying the dependency.
- **Slack bot auto-detects the target repo.** When multiple repos are
  available in a channel, the bot embeds the question and compares
  against each repo's index to pick the best match. Users can still
  force a repo with the `in <repo-name>` suffix.
- **Named indexes live under `~/.ask-the-repo/indexes/<name>/`.** The
  same format as repo-local indexes. The `--name` flag controls which
  mode is used. This keeps the Slack bot decoupled from where repos are
  cloned.
- **API-based file reading instead of git clone.** For private repos,
  cloning to the bot host is a security concern. Instead, files are read
  via the Bitbucket/GitHub REST API, chunked and embedded in memory, and
  only the vector index is persisted. No source code is stored on disk.
- **Channel-based access control via repos.json.** Each repo entry has
  a `channels` list of Slack channel IDs. If empty, the repo is
  available everywhere. This lets teams scope repos per channel without
  any per-user config.
- **Webhook endpoint is only registered when `WEBHOOK_SECRET` is set.**
  If no secret is configured, the `/webhook/` route does not exist,
  eliminating the attack surface entirely.
- **Constant-time webhook secret comparison.** Uses
  `MessageDigest.isEqual()` to prevent timing-based secret enumeration.
- **Admin form input validation.** Repo names, workspaces, and repo
  slugs are validated against `^[a-zA-Z0-9][a-zA-Z0-9._-]*$`; branches
  against `^[a-zA-Z0-9][a-zA-Z0-9._/-]*$`; provider must be `github`
  or `bitbucket`. Prevents path traversal and injection via form fields.
- **Audit logging for admin actions.** All add/edit/delete/sync
  operations are logged with timestamp and authenticated user to stderr
  and an in-memory ring buffer (capped at 500 entries).
- **Question length limits.** The Slack bot rejects questions over 5,000
  characters; the CLI rejects over 10,000. Prevents abuse of embedding
  and LLM APIs.
- **Thread history TTL.** Slack conversation context expires after 24
  hours and is hard-capped at 200 threads, preventing unbounded memory
  growth.

## Known limitations and next steps

### Known rough edges

- **Gitignore matcher** does not support character classes, backslash
  escaping, or nested `.gitignore` files.
- **Entire vector store is loaded into memory.** Fine at thousands of
  chunks; not at millions.
- **No concurrent embedding requests.** First-time ingest of large repos
  is bottlenecked by embedding latency.
- **The `ask` command always embeds the question fresh**, even for
  repeated questions.
- **Slack bot loads the index on every question.** For a small number of
  repos this is fine (loads in <1s). At scale, keep indexes in memory.
- **Admin UI uses HTTP basic auth.** No session management, MFA, or
  per-user roles. Intended for internal use behind a reverse proxy with
  TLS.
- **No rate limiting** on admin UI, webhook, or Slack bot endpoints.

### Natural next steps

1. **A proper vector store** — Qdrant, pgvector, or DuckDB — once the
   total size crosses hundreds of MB.
2. **Rate limiting** on webhook and admin endpoints to prevent abuse.
3. **Encryption at rest** for vector indexes (source code chunks are
   currently stored as plaintext in `chunks.jsonl`).
