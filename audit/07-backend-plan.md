# 07 — Backend Plan

> Plan backend BaseFinder : stack, schéma DB, API, déploiement.
> Contraintes : 1 dev, 10 h/sem, VPS 5-8 €/mois, GPL-3.0, 2b2t only.
> Ancré sur §3-4 de `05-target-architecture.md` (events/commandes) et §2, §4, §7 de `06-scale-plan.md` (volumes, sharding, coûts).
> Conventions : `[HYPOTHÈSE]` = choix à valider ; `[CALCUL]` = dérivé de chiffres audit.

---

## 1. Contraintes et objectifs

- **Budget humain** : 1 dev, 10 h/sem, auteur connaît Java/Kotlin mais pas Go/Rust.
- **Budget matériel** : 1 VPS Hetzner/Contabo, 5-8 €/mois (2 vCPU, 4 GB RAM, 80 GB SSD).
- **Volumes** : MVP T1 = 4.1 M chunks, 3 bots, 2.3 sem de scan. Vision T2 = 21 M chunks, 10 bots, 3.7 sem.
- **Débit events** : ~60 events/s/bot en scan dense, 600 events/s cumulés à 10 bots (audit 05 §3.2 ; ~150 KB/s gzip).
- **Scale target** : 1 → 3 → 10 bots ; pas plus. Pas de multi-tenant. Auteur = unique admin v1.

---

## 2. Recommandation de stack (motivée)

### Option retenue : **Kotlin + Ktor + Exposed + Postgres 16 + Redis (opt v2)**

Pourquoi :
- **Kotlin** : cohérent avec le bot Java 21 (même JDK, même Gradle, même IDE). Zéro coût de contexte-switching pour 1 dev. Sérialisation `kotlinx.serialization` partage les contrats events avec le plugin via un module Gradle commun (`contracts/` en pur Java/Kotlin, zéro dépendance MC).
- **Ktor** : framework HTTP/WS léger (2-3 MB runtime), WebSocket natif, coroutines pour I/O concurrent sans thread pool manuel. Pas de magie à la Spring. Startup < 2 s, footprint < 200 MB heap → dort confortable sur un CX11 4 GB.
- **Exposed** : DSL SQL type-safe maintenu par JetBrains. Pas d'ORM full Hibernate (pas de lazy loading, pas de n+1 cachés). DDL scripts SQL restent lisibles.
- **Postgres 16** : suffisant pour 21 M chunks avec partitioning par dim + mois. JSONB pour les payloads d'events (schema-flex v1 → protobuf v2). Free tier de `pg_partman` ou partitioning manuel.
- **Redis** : uniquement si claim store devient chaud (>1 claim/s). V1 : tout en Postgres avec `SELECT … FOR UPDATE SKIP LOCKED`, ça passe. V2 ajoute Redis si contention.

### Alternatives écartées

- **Go + chi + pgx** : plus rapide à démarrer et plus léger (50 MB RAM, binaire 15 MB), mais 2 écosystèmes à maintenir (JVM bot + Go backend). Coût coordo trop élevé pour 1 dev 10 h/sem. Rejeté.
- **Rust + Axum + SQLx** : overkill à ce volume (600 events/s = 0.06 % d'un CPU moderne). Courbe Rust mange 20-40 h de rampe avant productivité. Rejeté.
- **Node/TS + Fastify + Prisma** : runtime différent du bot, Prisma a du magic qu'Exposed évite, TypeScript ≠ Kotlin sur la modélisation domaine. Rejeté.
- **Python/FastAPI + SQLAlchemy** : typing plus faible que Kotlin, async Python reste fragile. Rejeté malgré suggestion initiale audit 06 §4.1.
- **GraphQL / Kafka / RabbitMQ** : hors scope v1. WebSocket + Postgres LISTEN/NOTIFY suffisent jusqu'à 50 bots.

---

## 3. Architecture services

**Monolithe modulaire** : un seul binaire Kotlin, split en modules Gradle par responsabilité. Splittable en services séparés plus tard si un module devient bottleneck.

```
basefinder-backend/
├── contracts/          # shared module, pure Kotlin, zéro dépendance runtime
│   ├── events/         # data classes BotHandshake, BotTick, BaseFound, ... (v1 NDJSON)
│   └── commands/       # PauseBot, SetZone, EmergencyStop, CommandAck
├── ingest/             # WS handler /ws/bot, valide, dédup, persist events
├── command/            # queue commandes pending, delivery bot, ACK tracking
├── shard/              # claim SKIP LOCKED, heartbeat, release, timeout watcher
├── query/              # REST read pour dashboard : /api/bots, /api/bases, /api/zones
├── auth/               # Discord OAuth (dashboard) + API keys bots (bcrypt)
├── scheduler/          # cron-like : heartbeat timeouts, re-scan TTL (v2), cleanup events
└── app/                # bootstrap Ktor, DI (Koin léger), config HOCON
```

Le module `contracts/` est **publié en local Maven** et consommé par le plugin BaseFinder → les events sont strictement les mêmes objets aux deux bouts. `kotlinx.serialization` gère NDJSON v1 et migrera vers protobuf v2 sans changer les data classes.

**Process model** :
- 1 JVM, 256-512 MB heap, coroutines (pas de thread pool dimensionné manuellement).
- 1 pool JDBC HikariCP, 10 connexions max (CX11 a 2 vCPU, inutile d'aller plus haut).
- Postgres `LISTEN basefinder_events` pour pousser les nouveaux events en SSE vers dashboard sans polling.

**Déploiement** : Docker Compose sur VPS unique. Postgres + backend + Caddy/Traefik + (Redis plus tard). Backup pg_dump quotidien vers Cloudflare R2 (free tier < 10 GB).

---

## 4. Schéma DB (Postgres 16)

Tous les IDs UUID v7 (time-ordered, indexables). `TIMESTAMPTZ` partout en UTC. Justifications index → patterns §5.

### 4.1 Tables de référentiel

```sql
-- 4.1.1 Bots enregistrés
CREATE TABLE bots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_label   TEXT NOT NULL UNIQUE,        -- "alt1", pas le vrai MS email
    api_key_hash    TEXT NOT NULL,                -- bcrypt(api_key), check au WS connect
    api_key_prefix  TEXT NOT NULL,                -- 8 premiers chars pour log/identif
    status          TEXT NOT NULL DEFAULT 'offline'
                    CHECK (status IN ('offline','connecting','online','disconnected','banned')),
    plugin_version  TEXT,
    mc_version      TEXT,
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX bots_status_idx ON bots(status) WHERE status = 'online';
-- Pattern : /api/bots filtre souvent status='online' ; index partiel pas cher
```

```sql
-- 4.1.2 Sessions (une par connexion WS)
CREATE TABLE sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id          UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    end_reason      TEXT,                         -- 'graceful','timeout','error','kicked'
    events_count    BIGINT NOT NULL DEFAULT 0,
    plugin_version  TEXT
);
CREATE INDEX sessions_bot_started_idx ON sessions(bot_id, started_at DESC);
-- Pattern : "dernière session de bot X" → B-tree desc, couvre 99% des queries
```

### 4.2 Zones et shards

```sql
-- 4.2.1 Zones définies par l'utilisateur (dashboard → SetZone command)
CREATE TABLE zones (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    dim         SMALLINT NOT NULL,                -- 0=OW, 1=NETHER, 2=END
    polygon     JSONB NOT NULL,                   -- GeoJSON-ish ; pas de PostGIS v1
                                                   -- [[x,z],[x,z],...] en coords chunk
    bbox_x1     INTEGER NOT NULL,                 -- denorm pour query fast
    bbox_z1     INTEGER NOT NULL,
    bbox_x2     INTEGER NOT NULL,
    bbox_z2     INTEGER NOT NULL,
    priority    SMALLINT NOT NULL DEFAULT 5,      -- 1-10
    status      TEXT NOT NULL DEFAULT 'active'
                CHECK (status IN ('active','paused','done','archived')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  TEXT                              -- Discord user id
);
CREATE INDEX zones_active_idx ON zones(priority DESC, created_at) WHERE status = 'active';
-- PostGIS non adopté : polygon simple suffit, audit 06 §3 travaille en bbox de toute façon.
-- Pattern : scheduler scan "prochain shard à assigner" = zones actives par priorité.
```

```sql
-- 4.2.2 Shards (découpe d'une zone en blocs 1024² / 256² / 128²)
CREATE TABLE shards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_id         UUID NOT NULL REFERENCES zones(id) ON DELETE CASCADE,
    dim             SMALLINT NOT NULL,
    bounds_x1       INTEGER NOT NULL,             -- coords chunk, inclusive
    bounds_z1       INTEGER NOT NULL,
    bounds_x2       INTEGER NOT NULL,             -- exclusive
    bounds_z2       INTEGER NOT NULL,
    chunk_count     INTEGER NOT NULL,             -- (x2-x1)*(z2-z1)
    status          TEXT NOT NULL DEFAULT 'free'
                    CHECK (status IN ('free','claimed','scanning','done','unreachable')),
    claimed_by      UUID REFERENCES bots(id),
    claimed_at      TIMESTAMPTZ,
    last_heartbeat  TIMESTAMPTZ,
    chunks_scanned  INTEGER NOT NULL DEFAULT 0,
    scanned_at      TIMESTAMPTZ,
    priority        SMALLINT NOT NULL DEFAULT 5   -- hérite de zone mais override possible
);
CREATE INDEX shards_claim_idx ON shards(priority DESC, zone_id)
    WHERE status = 'free';
CREATE INDEX shards_heartbeat_idx ON shards(last_heartbeat)
    WHERE status IN ('claimed','scanning');
CREATE INDEX shards_bot_idx ON shards(claimed_by) WHERE claimed_by IS NOT NULL;
-- Pattern 1 : claim = SELECT FOR UPDATE SKIP LOCKED sur status='free' → index partiel.
-- Pattern 2 : timeout watcher scanne heartbeat < NOW()-5min → index partiel sur claimed.
-- Pattern 3 : "quel bot a quoi" dashboard → index claimed_by partiel.
```

### 4.3 Résultats de scan

```sql
-- 4.3.1 Bases trouvées (source of truth)
CREATE TABLE bases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_x         INTEGER NOT NULL,
    chunk_z         INTEGER NOT NULL,
    dim             SMALLINT NOT NULL,
    base_type       TEXT NOT NULL,                -- STASH, FARM, BASE, MAP_ART, ...
    score           SMALLINT NOT NULL,            -- 0-100
    evidence        JSONB NOT NULL,               -- ["shulker:12","bed:3",...]
    found_by_bot    UUID REFERENCES bots(id),
    found_at        TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,         -- mis à jour si re-scan
    UNIQUE (chunk_x, chunk_z, dim, base_type)     -- idempotency clé métier
);
CREATE INDEX bases_dim_score_idx ON bases(dim, score DESC) WHERE score >= 50;
CREATE INDEX bases_found_at_idx ON bases(found_at DESC);
CREATE INDEX bases_chunk_idx ON bases(dim, chunk_x, chunk_z);
-- Pattern : /api/bases?dim=0&scoreMin=70 → 1er index partiel.
-- Pattern : flux dashboard "dernières découvertes" → 2e index.
-- Pattern : lookup ponctuel par coord → 3e index.
```

```sql
-- 4.3.2 Chunks scannés (haute volumétrie, partitionné par dim)
CREATE TABLE chunks_scanned (
    chunk_x         INTEGER NOT NULL,
    chunk_z         INTEGER NOT NULL,
    dim             SMALLINT NOT NULL,
    scan_hash       BIGINT NOT NULL,              -- xxh64 fingerprint
    result_class    SMALLINT NOT NULL,            -- 0=EMPTY, 1=WILD, 2=BASE
    scanned_at      TIMESTAMPTZ NOT NULL,
    scanned_by_bot  UUID REFERENCES bots(id),
    PRIMARY KEY (dim, chunk_x, chunk_z)
) PARTITION BY LIST (dim);

CREATE TABLE chunks_scanned_ow   PARTITION OF chunks_scanned FOR VALUES IN (0);
CREATE TABLE chunks_scanned_neth PARTITION OF chunks_scanned FOR VALUES IN (1);
CREATE TABLE chunks_scanned_end  PARTITION OF chunks_scanned FOR VALUES IN (2);

CREATE INDEX chunks_scanned_at_ow_idx   ON chunks_scanned_ow(scanned_at);
CREATE INDEX chunks_scanned_at_neth_idx ON chunks_scanned_neth(scanned_at);
-- Pattern 1 : "ce chunk est-il scanné ?" → PK direct, O(log n).
-- Pattern 2 : count chunks scannés dans un shard → range (dim, chunk_x BETWEEN, chunk_z BETWEEN).
-- Pattern 3 : re-scan TTL v2 → scanned_at index.
-- Pourquoi partition par dim : 16M OW vs 2M Nether vs 3M End, query toujours dim-scoped,
-- partition pruning évite de scanner 21M rows pour une query OW.
```

### 4.4 Events (timeseries, partitionné par mois)

```sql
-- 4.4.1 Event log append-only, partitionné par mois (TTL naturel 3-6 mois)
CREATE TABLE events (
    id          UUID NOT NULL DEFAULT gen_random_uuid(),
    bot_id      UUID NOT NULL,
    session_id  UUID,
    seq         BIGINT NOT NULL,                  -- monotone par bot
    type        TEXT NOT NULL,                    -- 'BotTick','BaseFound',...
    payload     JSONB NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (at, id),
    UNIQUE (bot_id, seq, at)                      -- idempotency bot+seq, audit 05 §3.1
) PARTITION BY RANGE (at);

CREATE TABLE events_2026_04 PARTITION OF events
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE events_2026_05 PARTITION OF events
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
-- Script scheduler crée partition M+1 le 25 du mois et drop M-6.

CREATE INDEX events_bot_at_idx ON events(bot_id, at DESC);
CREATE INDEX events_type_at_idx ON events(type, at DESC) WHERE type IN ('BaseFound','EmergencyDisconnect');
-- Pattern 1 : debug "events récents du bot X" → bot_id + at desc.
-- Pattern 2 : "BaseFound de la dernière heure" → index partiel sur types rares = dashboard live.
-- Partitioning par mois : drop partition = TRUNCATE O(1), évite VACUUM monstre sur table événementielle.
-- À 10 bots × 60 evt/s × 600 k/h × 24 h × 30 j = 432 M rows/mois. Compression TOAST JSONB ok,
-- mais on keep 3 partitions hot + archive S3 les autres.
```

```sql
-- 4.4.2 Snapshots "last-known" (vue matérialisée ou table maintenue)
CREATE TABLE bot_state_latest (
    bot_id              UUID PRIMARY KEY REFERENCES bots(id) ON DELETE CASCADE,
    last_tick_at        TIMESTAMPTZ,
    pos_x               DOUBLE PRECISION,
    pos_y               DOUBLE PRECISION,
    pos_z               DOUBLE PRECISION,
    dim                 SMALLINT,
    hp                  REAL,
    hunger              SMALLINT,
    inventory_snapshot  JSONB,
    health_snapshot     JSONB,
    flight_phase        TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Patch UPSERT à chaque BotTick/HealthSnapshot/InventorySnapshot.
-- Évite de scanner events pour afficher l'état courant d'un bot au dashboard.
```

### 4.5 Commandes (backend → bot)

```sql
CREATE TABLE commands (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id      UUID NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,                    -- 'PauseBot','SetZone',...
    payload     JSONB NOT NULL,
    status      TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','sent','ack_ok','ack_err','timeout')),
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMPTZ,
    ack_at      TIMESTAMPTZ,
    ack_detail  TEXT,
    issued_by   TEXT                              -- Discord user id
);
CREATE INDEX commands_bot_pending_idx ON commands(bot_id, issued_at) WHERE status = 'pending';
-- Pattern : à chaque connexion bot, push toutes les commandes pending → index partiel.
```

---

## 5. API

### 5.1 WebSocket bot

```
GET wss://backend/ws/bot
  Headers:
    Authorization: Bearer <api_key>
    X-Bot-Version: 0.3.0
```

- Après upgrade, le bot envoie immédiatement `BotHandshake` (NDJSON, 1 ligne).
- Backend valide `api_key_hash`, crée une `session`, renvoie `HandshakeAck { session_id, server_time }`.
- Stream bidirectionnel NDJSON : 1 event = 1 ligne JSON ≤ 8 KB.
- Bot envoie `BotTick` à 1 Hz → sert de heartbeat. **Timeout : si pas de BotTick pendant 30 s, backend ferme la socket et marque `sessions.end_reason='timeout'` + `bots.status='disconnected'`**.
- Backend peut pusher `Command` à tout moment ; bot répond `CommandAck { cmd_id, status }`.
- Resume : si `seq` reçu ≤ dernier `seq` persisté pour (bot_id, session_id), event ignoré (déduplication via UNIQUE (bot_id, seq, at)).

### 5.2 REST dashboard (JSON, auth JWT Discord)

```
GET  /api/bots                           → fleet overview (status, last_seen, active_shard)
GET  /api/bots/:id                        → détail 1 bot
GET  /api/bots/:id/state                  → bot_state_latest (inventaire, santé, pos)
GET  /api/bots/:id/sessions?limit=20      → historique sessions
POST /api/bots/:id/command                → { type, payload } → { cmd_id, status:'queued' }
GET  /api/bots/:id/commands?status=       → historique + ACK

GET  /api/bases?dim=&scoreMin=&limit=&offset= → liste bases (indexée)
GET  /api/bases/:id                           → détail

GET  /api/zones                               → list
POST /api/zones { name, dim, polygon, priority } → crée zone + découpe shards auto
PATCH /api/zones/:id                          → update priority/status
DELETE /api/zones/:id                         → archive (soft delete)

GET  /api/shards?zone=&status=                → vue sharding
POST /api/shards/:id/reset                    → force status='free'

GET  /api/events/stream                       → SSE, push live events via LISTEN/NOTIFY
GET  /api/stats                               → compteurs globaux (bots online, chunks scannés, bases, TPS ingest)

GET  /metrics                                 → Prometheus (basic auth séparée)
GET  /health                                  → liveness/readiness
```

### 5.3 Auth

**Bots** : API key générée côté backend au moment de l'enrôlement (`POST /api/admin/bots` par l'admin). Stockée en clair une seule fois (affichée), puis bcrypt. Rotation manuelle via regénération (v1). Pas de TLS mutual : bearer + TLS Let's Encrypt suffisent.

**Dashboard** : Discord OAuth2 → session JWT HS256 signée avec secret local (rotation tous les 90 j). Unique rôle v1 : `admin`, dont le Discord user_id est listé en config HOCON. Multi-user + rôles (viewer/operator/admin) = v2 après 10 bots.

---

## 6. Flux critiques

### 6.1 Bot connect → handshake → stream

```
Bot                                   Backend              Postgres
 |---- TLS + WS upgrade /ws/bot ------>|                     |
 |  Authorization: Bearer <api_key>   |                     |
 |                                     |--- SELECT bot ----->|
 |                                     |<-- row + hash ------|
 |                                     |  bcrypt.verify     |
 |<--- 101 Switching Protocols --------|                     |
 |--- BotHandshake {bot_id,ver,...} -->|                     |
 |                                     |--- INSERT session ->|
 |                                     |--- UPDATE bots     >|
 |                                     |      status=online  |
 |<--- HandshakeAck {session_id} ------|                     |
 |--- BotTick (seq=1) ---------------->|                     |
 |--- ChunkScanned (seq=2) ----------->|--- INSERT events -->|
 |--- BaseFound (seq=3) -------------->|--- UPSERT bases --->|
 |                                     |--- UPDATE state   ->|
 |--- BotTick (seq=4, heartbeat) ----->|                     |
 |             ...stream continu...    |                     |
```

### 6.2 Zone → shards → claim → scan → release

```
User(dashboard)            Backend                 Postgres              Bot
 |-- POST /api/zones ------>|                         |                    |
 |  {polygon, priority=8}   |                         |                    |
 |                          |-- INSERT zone --------->|                    |
 |                          |-- INSERT shards[] ----->|                    |
 |                          |   (quadtree 1024²      |                    |
 |                          |    avec subdiv highway) |                    |
 |<-- 201 {zone_id} --------|                         |                    |
 |                          |                         |                    |
 |                          |<--- Bot WS idle, poll   |                    |
 |                          |     "any shard for me?" |                    |
 |                          |  (au ResumeBot ou ack   |                    |
 |                          |   shard:done précédent) |                    |
 |                          |-- SELECT ... FOR UPDATE |                    |
 |                          |   SKIP LOCKED           |                    |
 |                          |   status='free' LIMIT 1>|                    |
 |                          |<-- row (shard X) -------|                    |
 |                          |-- UPDATE claimed_by --->|                    |
 |                          |-- Push Command:        ------- SetZone{X} -->|
 |                          |   SetZone + bounds      |                    |
 |                          |<----- CommandAck OK -----------------------|
 |                          |                         |<-- ChunkScanned*N |
 |                          |-- INSERT events, UPSERT |                    |
 |                          |   chunks_scanned, UPDATE|                    |
 |                          |   shards.chunks_scanned |                    |
 |                          |   à chaque 100 evts     |                    |
 |                          |                         |<-- BaseFound       |
 |                          |-- UPSERT bases -------->|                    |
 |                          |                         |<-- BotTick (hb)    |
 |                          |-- UPDATE last_heartbeat>|                    |
 |                          |                         |<-- scan 95%+       |
 |                          |-- UPDATE shards         |                    |
 |                          |   status='done',        |                    |
 |                          |   scanned_at=NOW        |                    |
 |                          |-- release, SELECT next  |                    |
 |                          |   free shard            |                    |
```

### 6.3 Emergency stop

```
User(dashboard)          Backend           Postgres          Bot
 |-- POST /api/bots/:id/   |                  |               |
 |   command {type=Emerge  |                  |               |
 |   ncyStop} ------------>|                  |               |
 |                         |-- INSERT cmd --->|               |
 |                         |   status=pending |               |
 |<-- 202 {cmd_id,queued}--|                  |               |
 |                         |                  |               |
 |                         |-- Push on WS:    --- EmergencyStop{cmd_id} ->|
 |                         |   (bot a WS open)|               |
 |                         |-- UPDATE cmd     |               |
 |                         |   status=sent -->|               |
 |                         |                  |               |   bot arrête :
 |                         |                  |               |   - Baritone.cancel
 |                         |                  |               |   - ElytraBot.stop
 |                         |                  |               |   - disconnect client
 |                         |<--- EmergencyDisconnect{reason} --|
 |                         |                  |               |
 |                         |-- INSERT event ->|               |
 |                         |-- UPDATE cmd     |               |
 |                         |   status=ack_ok->|               |
 |                         |-- UPDATE bots    |               |
 |                         |   status=offline>|               |
 |-- GET /api/bots/:id/    |                  |               |
 |   commands?id=cmd_id    |                  |               |
 |<-- {status:ack_ok} -----|                  |               |
```

Timeout safety : si `sent` sans ACK sous 2 s, backend retransmet avec même `cmd_id` (idempotent côté bot via dédup). Après 3 retries → `status=timeout`, alerte Discord webhook.

---

## 7. Observabilité

- **Logs structurés** : logback-classic + logstash-logback-encoder → JSON stdout. Fields : `bot_id`, `session_id`, `event_type`, `trace_id`. Captés par `docker logs`, rotation quotidienne 100 MB × 7.
- **Metrics Prometheus** (`/metrics`, basic auth admin:<pw>) :
  - `basefinder_events_total{bot_id,type}` counter
  - `basefinder_events_per_second{type}` gauge (fenêtre 10 s)
  - `basefinder_bots_connected` gauge
  - `basefinder_db_query_duration_seconds{query}` histogram
  - `basefinder_shards_free`, `basefinder_shards_claimed`, `basefinder_shards_done` gauges
  - `basefinder_command_latency_ms` histogram (issued → ack)
  - `basefinder_chunks_scanned_total{dim}` counter
- **Pas de Grafana self-hosted v1** : coût stockage metrics + 1 process de plus. Utilise **Grafana Cloud free tier** (10 k series, 14 j rétention) scrape via agent léger, ou simplement `curl /metrics` au besoin.
- **Alerting v1** : Discord webhook (déjà intégré bot) piloté par un scheduler côté backend qui lit `/metrics` toutes les 5 min : bot offline > 15 min, shard stuck > 30 min, events/s drop > 80 %, DB slow query > 500 ms.

---

## 8. Déploiement

### 8.1 Dockerfile multi-stage

```
FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/app/build/install/app/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/app"]
```

### 8.2 docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: basefinder
      POSTGRES_USER: basefinder
      POSTGRES_PASSWORD_FILE: /run/secrets/pg_pw
    volumes: [pgdata:/var/lib/postgresql/data]
    shm_size: 512m
  backend:
    image: ghcr.io/<user>/basefinder-backend:latest
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/basefinder
      DISCORD_OAUTH_ID: ...
      JWT_SECRET_FILE: /run/secrets/jwt
    depends_on: [postgres]
    labels:
      - traefik.enable=true
      - traefik.http.routers.bf.rule=Host(`basefinder.example`)
      - traefik.http.routers.bf.tls.certresolver=le
  traefik:
    image: traefik:v3
    ports: ["80:80","443:443"]
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - letsencrypt:/letsencrypt
volumes: { pgdata: {}, letsencrypt: {} }
```

### 8.3 Backups

- **pg_dump quotidien** : cron container qui pousse `backup-YYYYMMDD.sql.gz` vers Cloudflare R2 (10 GB free). Garde 30 jours, archive mensuelle 12 mois.
- **Test restore** : script manuel `./restore.sh <date>` dans un dossier `ops/`. À valider tous les 3 mois.
- Pas de réplication v1 (coût VPS × 2). Acceptable : RPO 24 h.

### 8.4 CI/CD

- **GitHub Actions** : build image au push sur `main`, push sur GHCR, tag `:latest` + `:sha`.
- **Deploy** : webhook `watchtower` ou script SSH simple qui fait `docker-compose pull && up -d backend`. Pas de blue/green v1.
- **Tests CI** : unit Kotlin (Exposed in-memory H2 ok pour 80 %, Testcontainers Postgres pour les 20 % partitioning).

---

## 9. Phase 0 → v1 → v2

### Phase 0 — « 1 bot read-only » (~25 h)

**Scope** :
- `contracts/` module + 4 events critiques (`BotHandshake`, `BotTick`, `BaseFound`, `ChunkScanned`)
- Ingest WS avec auth bearer
- `bots`, `sessions`, `events` (partition manuelle), `bases`, `chunks_scanned` (sans partition par dim encore)
- REST read : `GET /api/bots`, `GET /api/bases`, `GET /api/stats`
- 1 endpoint stub : `POST /api/shards/claim` qui retourne un shard hardcodé
- Dashboard minimaliste HTML/htmx (pas de SPA) : liste bots, bases, tick counter live via SSE
- Docker Compose local sans Traefik ni TLS

**Débloque** : validation chaîne bot → WS → DB → UI en prod sur 1 zone 1024² manuelle. Critère succès = audit 06 §9.3 adapté.

**Effort** : 25 h = 2.5 semaines à 10 h/sem.

### v1 — « 3 bots, commandes simples, sharding réel » (~35 h)

**Scope** :
- Tous les events + toutes les commandes `PauseBot`/`ResumeBot`/`EmergencyStop`/`SetZone`/`ClearZone`
- Découpage zone auto en shards quadtree (audit 06 §3.2)
- `SELECT FOR UPDATE SKIP LOCKED` pour claim atomique
- Scheduler : timeout heartbeat 5 min → release, alerte Discord
- Partitioning `chunks_scanned` par dim + `events` par mois
- Dashboard : carte Leaflet tiles heatmap, zone editor (draw polygon)
- TLS Traefik + Discord OAuth
- Backup R2

**Débloque** : 3 bots simultanés, gestion zones UI, resumability shards, scan de T1 en 2-3 semaines.

**Effort** : 35 h = 3.5 semaines.

### v2 — « 10 bots, re-scan, multi-user » (~40 h)

**Scope** :
- `SetScanPattern`, `RequestStateDump` commandes
- Re-scan TTL par `base_type` (audit 06 §3.3)
- Rôles (viewer/operator/admin) via Discord roles mapping
- Redis pour claim store si contention > 1/s
- Protobuf v2 side-by-side avec NDJSON, feature-flagged par version plugin
- Grafana Cloud dashboards partagés
- Archive events > 3 mois vers R2 (cold storage)

**Débloque** : scan T2 en 3-4 semaines avec 10 bots, rotation comptes, ops friendly.

**Effort** : 40 h = 4 semaines.

**Total v0 → v2** : ~100 h = 10 semaines à 10 h/sem ≈ **2.5 mois** de backend pur (en parallèle du travail bot qui fait ~60 h de son côté).

---

## 10. Risques backend

| # | Risque | Impact | Probabilité | Parade |
|---|--------|--------|-------------|--------|
| 1 | **Saturation écriture Postgres** : 600 evt/s × 10 bots × INSERT events + UPSERT chunks = ~1200 tx/s, VPS CX11 plafonne à 500-800 tx/s sur 1 disque SSD mutualisé | Backpressure sur WS, bots timeout 30s et reconnectent en boucle | Moyenne si pic 10 bots | Batcher côté backend : buffer events 500 ms, INSERT COPY en batch. `chunks_scanned` UPSERT groupés 100 à 1. Upgrade CX21 (8 €/mois) si la mesure confirme. |
| 2 | **Partition overflow** : oubli de créer `events_YYYY_MM` avant le 1er du mois → INSERTs fail | Arrêt ingest total | Haute sans scheduler | Scheduler cron interne qui crée partition M+1 le 25 du mois et archive M-6. Test en CI que la partition N+1 existe. Alerte Discord si INSERT fail 3× en 1 min. |
| 3 | **Clé API fuite** (bot repo public, logs, screenshot) | Accès ingest illégitime, pollution data | Moyenne-haute (auteur solo, facile à oublier) | Prefix 8 chars lisibles pour logs sans hash, rotation 1-click via dashboard. `api_key_prefix` loggué, reste bcryptée. Scan GitHub push protection. |
| 4 | **Dashboard devient SPOF social** : si des coords de bases fuitent via dashboard public, culture 2b2t de stash-hunting → backlash sur le projet (audit 06 §10.3) | Pression communautaire, risque de takedown | Moyenne long-terme | Auth Discord stricte v1, jamais d'endpoint public. `/api/bases` jamais en CORS `*`. Option `coord_fuzz_chunks=64` pour affichage macro. Politique data écrite dans README. |
| 5 | **Overrun budget dev** : backend seul consomme 10 h/sem, bot stagne | Slip 2-3 mois MVP | Haute (audit 06 §8 R4) | Monolithe modulaire = refactor incrémental sans microservice. Phase 0 livrable en 25 h, ça prouve la chaîne avant d'investir plus. Couper v2 (rôles, protobuf) si retard > 30 %. Réutiliser `contracts/` module = pas de duplication models bot/backend. |

---

_Fin du livrable. ~640 lignes. Cible VPS 5-8 €/mois tenue, stack JVM unifiée, effort Phase 0 = 25 h._
