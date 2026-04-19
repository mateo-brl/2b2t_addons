# 09 — Data Plan

> Plan data concret pour BaseFinder (Plugin RusherHack 2b2t + backend Ktor/Postgres + dashboard).
> Inputs lus : `06-scale-plan.md` (volumes T1/T2, débit bot, shards), `03-performance.md` §4
> (persistence, format 8 B/chunk bot-local), `05-target-architecture.md` §3 (schéma events, NDJSON).
>
> Contraintes dures : 1 dev, 10 h/sem, budget Hetzner CX11 (2 vCPU, 2 GB RAM, 40 GB SSD), GPL-3.0.
> Pas de TimescaleDB/ClickHouse/InfluxDB (coût op refusé). Postgres 16 monolithe, Redis optionnel v2.
>
> Conventions : `[CALCUL]` = arithmétique ; `[HYPOTHÈSE]` = choix à valider ; `[SQL]` = exécutable.

---

## 1. Volumes attendus

Entités v1 (reprise 05 §3.2 + extensions serveur).

| Entité | bytes/entrée (row+idx) | Phase 0 (1 bot, 4 sem) | v1 (3 bots, 2 mois) | v2 (10 bots, 6 mois) |
|---|---:|---:|---:|---:|
| `bots` | 128 B | 1 | 3 | 10 |
| `sessions` | 256 B | ~30 | ~300 | ~3 000 |
| `shards` | 512 B | ~50 | ~1 500 | ~25 000 |
| `bases` | 1.0 KB | ~500 | ~5 000 | ~50 000 |
| `chunks_scanned` | 56 B (32 row + 24 idx) | 2.1 M | 22 M | 21 M (asymptote T2) |
| `events BotTick` | 160 B | 4 M | 120 M | 1.3 Md brut |
| `events ChunkScanned` | 120 B | 2.1 M | 22 M | 21 M |
| `events BaseFound` | 300 B | 500 | 5 000 | 50 000 |
| `events autres` (Health/Inv/Dim/Flight/Alert) | 180 B moy. | 200 k | 6 M | 60 M |

### Total Postgres (après downsampling BotTick 1 Hz hot 7j → 0.1 Hz décimé)

| Phase | Events hot (≤90 j) | chunks_scanned | bases + autres | **Total** |
|---|---:|---:|---:|---:|
| Phase 0 | 700 MB | 120 MB | 50 MB | **~0.9 GB** |
| v1 | 5 GB | 1.2 GB | 200 MB | **~7 GB** |
| v2 (asymptote) | 22 GB | 1.2 GB | 1 GB | **~25 GB** + 9 GB index = **~34 GB hot** |

[CALCUL] sans downsampling, v2 events = 7.5 TB → irréaliste. Downsampling obligatoire (§2).
Marge 6 GB sur 40 GB VPS pour WAL, pg_dump, logs. Alerte à 80 % critique (§8).

---

## 2. Stockage : hot vs warm vs cold

Stratégie 3 couches, découpée par **pattern d'accès** (pas juste ancienneté).

### 2.1 Hot — Postgres SSD VPS
- `bots`, `sessions`, `shards`, `bases` intégral (always-hot, <60 MB).
- `chunks_scanned` intégral (requêtes dashboard heatmap, anti-join "zones vides", 1.2 GB plafond T2).
- `bot_state_latest` (dernière pos/HP par bot, table dénormalisée — v1 — ou Redis — v2).
- `events` des **7 derniers jours** — accès dashboard "dernière heure / 24h / 7j".

Justif ligne de coupe 7 j : au-delà, dashboard passe en agrégats hebdos (vues matérialisées).

### 2.2 Warm — Postgres partitions archivées
Partitions `events_YYYY_MM` âge 7–90 j, indexées plus léger, accès analyses ad-hoc. Retention **90 j** puis détachement via `pg_partman` + export parquet → DROP.

### 2.3 Cold — Parquet + zstd sur Cloudflare R2
- Events > 90 j exportés en Parquet compressé (zstd, ~15× vs Postgres row format).
- Backups `pg_dump` quotidiens (7 dailies + 12 monthlies).
- Dumps bitmap `scanned_chunks.dat` bot-local (recovery 03 §4).

**Choix R2** : pas de frais egress (vs AWS S3). 10 GB gratuits, puis 0.015 $/GB/mois. Hetzner Storage
Box alternative (5 €/mois pour 1 TB) mais WebDAV, moins pratique. Volume cold v2 à 6 mois = 80 GB
parquet → **~1 $/mois**, insignifiant.

### 2.4 Lignes de coupe

| Données | Hot | Warm | Cold |
|---|---|---|---|
| `BotTick` | 7 j full 1 Hz | 8–90 j décimé 1/10 s | >90 j parquet |
| `ChunkScanned` events | 30 j | 31–90 j | >90 j parquet |
| `BaseFound`, `DimensionChange`, `EmergencyDisconnect` | forever hot | — | backup only |
| `chunks_scanned` | forever hot | — | backup only |
| `bases`, `shards`, `sessions`, `bots` | forever hot | — | backup only |

---

## 3. Schéma physique Postgres

Reprise et approfondissement de 05 §3. Focus 3 tables critiques + enums partagés.

### 3.0 Enums numériques

On **n'utilise pas** `CREATE TYPE ENUM` Postgres (ALTER bloquant, difficile à faire évoluer).
On utilise `SMALLINT` + mapping applicatif stable :
- `event_type` : 1=BotTick, 2=ChunkScanned, 3=BaseFound, 4=BotHandshake, 5=InventorySnapshot,
  6=HealthSnapshot, 7=DimensionChange, 8=EmergencyDisconnect, 9=FlightStateChange,
  10=BaritoneState, 11=SurvivalAlert, 12=TelemetryHeartbeat
- `base_type` : 1=STASH, 2=STRONG_PLAYER, 3=FARM, 4=CONSTRUCTION, 5=MAP_ART, 6=VILLAGE, 7=TRIAL, 8=ANCIENT
- `dim` : 0=OW, -1=NETHER, 1=END

**Pourquoi SMALLINT vs VARCHAR** : 2 B vs ~12 B, pas de TOAST hot path, comparaisons scalaires.
Sur 1.3 Md events v2 : **~13 GB économisés** juste sur la colonne `type`. Évolution = update app
code + mapping (pas de DDL). Trade-off assumé.

### 3.1 `events` — timeseries, partitioned monthly

```sql
CREATE TABLE events (
    id         BIGSERIAL,
    bot_id     INT         NOT NULL REFERENCES bots(id),
    session_id INT         REFERENCES sessions(id),
    seq        BIGINT      NOT NULL,                  -- monotone par bot, idempotency
    type       SMALLINT    NOT NULL,
    at         TIMESTAMPTZ NOT NULL,
    payload    JSONB       NOT NULL,
    PRIMARY KEY (at, id)                              -- PK doit inclure clé de partitioning
) PARTITION BY RANGE (at);

CREATE TABLE events_2026_04 PARTITION OF events FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE events_2026_05 PARTITION OF events FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
-- ... pg_partman ensuite (cf. 3.1.1)

CREATE INDEX events_bot_at_idx  ON events (bot_id, at DESC);
CREATE INDEX events_type_at_idx ON events (type, at DESC);
CREATE UNIQUE INDEX events_bot_seq_idx ON events (bot_id, seq);  -- dédoublonnage reco bot
-- Pas d'index GIN sur payload en v1 (coût write élevé). Ajouter ciblé si requête le justifie.
```

**pg_partman** pour gestion auto :

```sql
CREATE EXTENSION IF NOT EXISTS pg_partman;
SELECT partman.create_parent('public.events', 'at', 'range', '1 month', p_premake => 3);
UPDATE partman.part_config
   SET retention = '90 days', retention_keep_table = true, retention_keep_index = false
 WHERE parent_table = 'public.events';
-- Cron : SELECT partman.run_maintenance() chaque nuit 03:00.
```

**Pourquoi partitioning mensuel vs semaine/jour** :

| Fenêtre | Partitions après 1 an | Verdict |
|---|---:|---|
| Journalière | 365 | planner overhead >1000 partitions, catalog bloat |
| Hebdomadaire | 52 | OK mais purge granularité 7j |
| **Mensuelle** | **12** | planner OK, pg_dump par table simple, retention 90j = 3 partitions |

DROP PARTITION mensuel = O(1) metadata, vs DELETE ligne-à-ligne → autovacuum I/O storm inacceptable sur CX11 2 GB RAM.

**PK `(at, id)`** : Postgres impose que toutes colonnes de partitioning soient dans l'index unique.
`at` clé de partitioning, donc **dans** la PK. Vraie clé métier de dédup = `(bot_id, seq)` via `events_bot_seq_idx`.

### 3.2 `chunks_scanned` — large, partitioned par `dim`

```sql
CREATE TABLE chunks_scanned (
    chunk_x         INT         NOT NULL,
    chunk_z         INT         NOT NULL,
    dim             SMALLINT    NOT NULL,       -- 0 OW, -1 NETHER, 1 END
    scan_hash       BIGINT      NOT NULL,       -- xxh64 blocs significatifs (05 §2.3)
    scanned_at      TIMESTAMPTZ NOT NULL,
    scanned_by_bot  INT         NOT NULL REFERENCES bots(id),
    result_class    SMALLINT    NOT NULL,       -- 0=EMPTY, 1=WILD, 2=BASE_CANDIDATE
    PRIMARY KEY (dim, chunk_x, chunk_z)
) PARTITION BY LIST (dim);

CREATE TABLE chunks_scanned_ow     PARTITION OF chunks_scanned FOR VALUES IN (0);
CREATE TABLE chunks_scanned_nether PARTITION OF chunks_scanned FOR VALUES IN (-1);
CREATE TABLE chunks_scanned_end    PARTITION OF chunks_scanned FOR VALUES IN (1);

CREATE INDEX chunks_scanned_ow_xz_idx     ON chunks_scanned_ow     (chunk_x, chunk_z);
CREATE INDEX chunks_scanned_nether_xz_idx ON chunks_scanned_nether (chunk_x, chunk_z);
CREATE INDEX chunks_scanned_end_xz_idx    ON chunks_scanned_end    (chunk_x, chunk_z);
CREATE INDEX chunks_scanned_ow_bot_idx    ON chunks_scanned_ow     (scanned_by_bot, scanned_at DESC);
```

**Taille estimée 21 M chunks T2** :
- Row : 4+4+2+8+8+4+2+24 header = ~54 B
- PK idx (dim,x,z) : ~20 B. Idx (x,z) : ~18 B
- Total : 21 M × 92 B = **1.93 GB**. OK sur 40 GB VPS.

`RoaringBitmap` BYTEA donnerait <100 MB (03 §4) **mais** perd les filtres par bot et UPSERT atomic.
Trade-off : row-based Postgres côté serveur (queryabilité), bitmap compact côté bot local uniquement.

### 3.3 `bases` — small, queryable rapidement

```sql
CREATE TABLE bases (
    id             BIGSERIAL PRIMARY KEY,
    chunk_x        INT         NOT NULL,
    chunk_z        INT         NOT NULL,
    dim            SMALLINT    NOT NULL,
    base_type      SMALLINT    NOT NULL,
    score          REAL        NOT NULL,       -- 0.0 – 100.0
    evidence       JSONB       NOT NULL,       -- {"shulkers":5,"beds":3,"signatures":["STRONG"]}
    found_by_bot   INT         NOT NULL REFERENCES bots(id),
    first_found_at TIMESTAMPTZ NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL,
    notes          TEXT,
    UNIQUE (dim, chunk_x, chunk_z, base_type)
);
CREATE INDEX bases_score_found_idx ON bases (score DESC, first_found_at DESC);
CREATE INDEX bases_dim_type_idx    ON bases (dim, base_type, score DESC);
CREATE INDEX bases_spatial_idx     ON bases (dim, chunk_x, chunk_z);
```

**Taille** : 50 k bases × ~800 B (row + 3 idx) = **~50 MB**. Négligeable.

### 3.4 Tables auxiliaires (DDL compact)

```sql
CREATE TABLE bots ( id SERIAL PRIMARY KEY, name TEXT NOT NULL UNIQUE,
    api_token_hash BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    active BOOLEAN NOT NULL DEFAULT true );

CREATE TABLE sessions ( id SERIAL PRIMARY KEY, bot_id INT NOT NULL REFERENCES bots(id),
    started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
    end_reason SMALLINT );   -- 1=graceful, 2=kick, 3=crash, 4=backend_stop
CREATE INDEX sessions_bot_started_idx ON sessions (bot_id, started_at DESC);

CREATE TABLE shards ( id BIGSERIAL PRIMARY KEY, dim SMALLINT NOT NULL,
    bounds_min_x INT NOT NULL, bounds_min_z INT NOT NULL,
    bounds_max_x INT NOT NULL, bounds_max_z INT NOT NULL,
    state SMALLINT NOT NULL,   -- 1=free, 2=assigned, 3=complete, 4=unreachable
    assigned_bot INT REFERENCES bots(id),
    assigned_at TIMESTAMPTZ, last_heartbeat TIMESTAMPTZ,
    completion_pct REAL NOT NULL DEFAULT 0.0 );
CREATE INDEX shards_state_idx ON shards (state, dim);
CREATE INDEX shards_assigned_hb_idx ON shards (assigned_bot, last_heartbeat) WHERE state = 2;
```

### 3.5 `bot_state_latest` (alternative à Redis en v1)

```sql
CREATE TABLE bot_state_latest (
    bot_id INT PRIMARY KEY REFERENCES bots(id),
    last_event_at TIMESTAMPTZ NOT NULL,
    pos_x DOUBLE PRECISION, pos_y DOUBLE PRECISION, pos_z DOUBLE PRECISION,
    dim SMALLINT, hp REAL, hunger SMALLINT, flight_phase SMALLINT,
    current_shard_id BIGINT REFERENCES shards(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- UPSERT par pipeline d'ingest (§4), pas par trigger SQL.
```

---

## 4. Pipeline d'ingest

### 4.1 Diagramme

```
Bot (Java) ─── WebSocket NDJSON ───▶ Ktor ingest
  60 evts/s       (TLS v2+)              │
                                         ▼
                                 Batcher: 100 evts / 500 ms
                                         │
                                         ▼
                                 COPY FROM STDIN (binary) ──▶ events
                                         │
                          ┌──────────────┼──────────────┬──────────────┐
                          ▼              ▼              ▼              ▼
                   chunks_scanned     bases        shards.         bot_state_
                     (UPSERT)        (UPSERT)     heartbeat         latest
                                                                  (Redis v2)
```

### 4.2 Batching 100 evts / 500 ms (Kotlin pseudocode)

```kotlin
val queue = Channel<Event>(Channel.BUFFERED)
launch {
    val batch = mutableListOf<Event>()
    var lastFlush = TimeSource.Monotonic.markNow()
    while (true) {
        val timeout = 500.ms - lastFlush.elapsedNow()
        select<Unit> {
            queue.onReceive { ev ->
                batch.add(ev)
                if (batch.size >= 100) { flush(batch); lastFlush = now() }
            }
            onTimeout(timeout.coerceAtLeast(0.ms)) {
                if (batch.isNotEmpty()) { flush(batch); lastFlush = now() }
            }
        }
    }
}
```

### 4.3 Pourquoi COPY > INSERT batch

| Méthode | Throughput Postgres 16 `[HYPOTHÈSE]` | Verdict |
|---|---:|---|
| `INSERT VALUES (...)` x N | ~2 k rows/s | round-trip/insert, lent |
| `INSERT VALUES (..), (..), ...` 100 rows | ~30 k rows/s | 1 round-trip, encode SQL |
| `INSERT SELECT UNNEST(...)` | ~50 k rows/s | arrays encode |
| **`COPY FROM STDIN (FORMAT BINARY)`** | **~200 k rows/s** | binaire direct, pas de parse SQL |

À 600 evts/s v2, même INSERT batch tient. Mais COPY donne marge pour burst scan dense (5000/s
cumulés) **et** garde CPU pour requêtes dashboard. Implémentation : JDBC `CopyManager` via driver
postgresql officiel (Ktor compatible).

### 4.4 UPSERT `chunks_scanned`

```sql
INSERT INTO chunks_scanned (dim, chunk_x, chunk_z, scan_hash, scanned_at, scanned_by_bot, result_class)
VALUES ($1, $2, $3, $4, $5, $6, $7)
ON CONFLICT (dim, chunk_x, chunk_z) DO UPDATE
    SET scan_hash = EXCLUDED.scan_hash,
        scanned_at = EXCLUDED.scanned_at,
        scanned_by_bot = EXCLUDED.scanned_by_bot,
        result_class = EXCLUDED.result_class
    WHERE chunks_scanned.scan_hash <> EXCLUDED.scan_hash
       OR chunks_scanned.scanned_at < EXCLUDED.scanned_at - INTERVAL '7 days';
```

Le `WHERE` sur UPDATE évite write I/O pour les chunks inchangés (économise ~70 % writes sur
revisites de render-distance). Sur 21 M chunks × ratio revisite 3 = 63 M UPSERT mais ~21 M writes effectifs.

### 4.5 UPSERT `bases`

```sql
INSERT INTO bases (chunk_x, chunk_z, dim, base_type, score, evidence,
                   found_by_bot, first_found_at, last_seen_at)
VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $8)
ON CONFLICT (dim, chunk_x, chunk_z, base_type) DO UPDATE
    SET score = GREATEST(bases.score, EXCLUDED.score),
        evidence = bases.evidence || EXCLUDED.evidence,
        last_seen_at = EXCLUDED.last_seen_at;
-- first_found_at préservé (absent de SET).
```

---

## 5. Requêtes types (optimisation)

Plans `[HYPOTHÈSE]` sauf mesure. Fréquence = dashboard polling 5 s ou requête user.

### 5.1 "Zones non-scannées proches du spawn" — anti-join spatial

```sql
WITH grid AS (
  SELECT gx, gz FROM generate_series(-500, 500, 64) AS gx, generate_series(-500, 500, 64) AS gz
),
missing AS (
  SELECT g.gx, g.gz, 64*64 - COUNT(cs.chunk_x) AS missing_cnt
    FROM grid g
    LEFT JOIN chunks_scanned_ow cs
           ON cs.chunk_x >= g.gx AND cs.chunk_x < g.gx + 64
          AND cs.chunk_z >= g.gz AND cs.chunk_z < g.gz + 64
   GROUP BY g.gx, g.gz
)
SELECT gx, gz, missing_cnt FROM missing WHERE missing_cnt > 0
 ORDER BY missing_cnt DESC LIMIT 50;
```
- Index : `chunks_scanned_ow_xz_idx` (range scan x,z). Coût brut = ~2 min. Inacceptable polling.
- **Mitigation** : vue matérialisée `coverage_grid_ow` rafraîchie 5 min, 256 rows × 40 B = 10 KB. Dashboard lit mat view en <1 ms.
- Fréquence : polling 30 s.

### 5.2 "Bases score > 50 trouvées cette semaine"

```sql
SELECT id, chunk_x, chunk_z, dim, base_type, score, first_found_at, found_by_bot
  FROM bases WHERE score > 50 AND first_found_at >= now() - INTERVAL '7 days'
 ORDER BY score DESC, first_found_at DESC LIMIT 100;
```
- Index : `bases_score_found_idx` → index-only scan. Coût <2 ms. Fréquence : polling 10 s.

### 5.3 "Events d'un bot dernière heure"

```sql
SELECT id, at, type, payload FROM events
 WHERE bot_id = $1 AND at >= now() - INTERVAL '1 hour'
 ORDER BY at DESC LIMIT 500;
```
- Index : `events_bot_at_idx`. Partition pruning : seule partition mensuelle courante scannée.
- Coût 1–5 ms (500 rows heap fetch). Fréquence : à la demande.

### 5.4 "Heatmap densité bases Nether, groupé par 16×16 chunks"

```sql
SELECT (chunk_x >> 4) AS cell_x, (chunk_z >> 4) AS cell_z,
       COUNT(*) AS cnt, AVG(score) AS avg_score
  FROM bases WHERE dim = -1
 GROUP BY 1, 2 ORDER BY cnt DESC LIMIT 2000;
```
- Index : `bases_dim_type_idx` (filtre dim). Coût <20 ms sur 7.5 k rows.
- Fréquence : tuiles Leaflet → cache app TTL 60 s → 1 req/min/dim.

### 5.5 "Derniers BotTick de chaque bot"

```sql
-- Version rapide via table dénormalisée §3.5
SELECT bot_id, last_event_at, pos_x, pos_y, pos_z, dim, hp, flight_phase
  FROM bot_state_latest WHERE last_event_at >= now() - INTERVAL '5 minutes';
```
- Coût <1 ms (10 rows). Fréquence : polling 2 s dashboard live.
- Fallback si pipeline en panne : `SELECT DISTINCT ON (bot_id) ... FROM events WHERE type=1 AND at >= now()-10min ORDER BY bot_id, at DESC;` via `events_bot_at_idx` → ~10 ms sur 6 k rows.

### 5.6 Récap

| # | Requête | Index | Coût | Fréquence | Cache |
|---|---|---|---|---|---|
| 1 | Coverage grid | `chunks_scanned_ow_xz_idx` + mat view | <1 ms | 30 s | mat view 5 min |
| 2 | Top bases 7j | `bases_score_found_idx` | <2 ms | 10 s | — |
| 3 | Events bot 1h | `events_bot_at_idx` | <5 ms | on-demand | — |
| 4 | Heatmap bases | `bases_dim_type_idx` | <20 ms | 1/min | Caffeine 60 s |
| 5 | Bot state live | `bot_state_latest` PK | <1 ms | 2 s | table dénorm |

---

## 6. Archivage cold

### 6.1 Export mensuel vers R2 (script cron)

```bash
#!/usr/bin/env bash
# /opt/basefinder/scripts/archive-events.sh — cron: 0 04 1 * *
set -euo pipefail
MONTH=$(date -d 'last month' +%Y_%m); TABLE="events_${MONTH}"
DUMP="/tmp/${TABLE}.dump"; PARQUET="/tmp/${TABLE}.parquet.zst"

pg_dump --format=custom --table="public.${TABLE}" --no-owner -U basefinder basefinder > "${DUMP}"

duckdb -c "
  INSTALL postgres; LOAD postgres;
  ATTACH 'host=localhost dbname=basefinder user=basefinder' AS pg (TYPE postgres);
  COPY (SELECT * FROM pg.public.${TABLE}) TO '${PARQUET}'
       (FORMAT PARQUET, COMPRESSION ZSTD, COMPRESSION_LEVEL 9);"

aws s3 cp --endpoint-url "https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com" \
         "${PARQUET}" "s3://basefinder-cold/events/${TABLE}.parquet.zst"

psql -c "SELECT partman.drop_partition_time('public.events', '3 months');"
rm -f "${DUMP}" "${PARQUET}"
```

### 6.2 Retention
Hot/Warm : 12 mois max Postgres. Cold R2 : forever (12 mois × 10 GB × 0.015 $ = **1.80 $/mois**). Backup pg_dump : 7 dailies + 12 monthlies.

### 6.3 Restore (runbook compact)

```bash
aws s3 cp "s3://basefinder-cold/events/events_2026_03.parquet.zst" ./
duckdb -c "
  INSTALL postgres; LOAD postgres;
  ATTACH 'host=localhost dbname=basefinder user=basefinder' AS pg (TYPE postgres);
  COPY (SELECT * FROM 'events_2026_03.parquet.zst')
       TO pg.public.events_restore_2026_03 (FORMAT POSTGRES);"
psql -c "ALTER TABLE events ATTACH PARTITION events_restore_2026_03
         FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');"
```
RTO = ~10 min pour 10 GB parquet.

---

## 7. Caching

### 7.1 v1 — pas de Redis
VPS 2 GB RAM : Postgres shared_buffers 512 MB, work_mem 8 MB × 20 conn, effective_cache_size 1.2 GB,
JVM heap 512 MB. Redis prendrait 128–256 MB → trop cher. On utilise :
- Table dénormalisée `bot_state_latest` (§3.5).
- Vue matérialisée `coverage_grid_ow` rafraîchie 5 min.
- Cache applicatif Ktor via `Caffeine` (in-JVM, TTL 60 s) pour heatmap tiles + top bases. Budget ≤50 MB dans le heap.

### 7.2 v2 — Redis optionnel (si >10 bots ou >30 users dashboard concurrents)
- `bot_state_latest` TTL 60 s + pub/sub pour WS live (évite polling Postgres).
- Tuiles heatmap : 10 dims × 64 zoom × ~100 tiles × 2 KB = ~130 MB. Cache LRU.
- Rate limit bot tokens (sliding window).

Coût op : +1 service systemd, +100 MB RAM. **Bascule si Postgres CPU soutenu > 60 %.**

---

## 8. Observabilité data

### 8.1 Extensions requises

```sql
CREATE EXTENSION pg_stat_statements;
CREATE EXTENSION pg_partman;
-- postgresql.conf : shared_preload_libraries = 'pg_stat_statements,pg_partman_bgw'
```

### 8.2 Endpoint `/metrics` Ktor (format Prometheus)

```
basefinder_events_inserted_total{type="BotTick"} 1234567
basefinder_events_inserted_total{type="ChunkScanned"} 234567
basefinder_chunks_upserted_total{dim="0"} 123456
basefinder_disk_usage_bytes{table="events"} 2500000000
basefinder_slow_queries_total 42
basefinder_ingest_backlog_size 120
```
Scrape par Grafana Agent local ou Grafana Cloud free tier.

### 8.3 Alertes

| Alerte | Seuil | Gravité | Action |
|---|---|---|---|
| Disk usage | >80 % (32 GB) | WARN | force archive cold, shrink WAL |
| Disk usage | >90 % (36 GB) | CRIT | stop ingest, purge manuelle |
| Ingest backlog | >10 k events | WARN | scaler batch, check COPY errors |
| Slow queries | >10/min @ 500 ms | WARN | EXPLAIN + index review |
| Events growth anomaly | +50 % vs moy 7j | WARN | check bot spam |

Notifications : Discord webhook (réutiliser infra bot BaseFound).

### 8.4 Top 10 slow queries (daily script)

```sql
SELECT calls, round(total_exec_time::numeric, 2) AS total_ms,
       round(mean_exec_time::numeric, 2) AS mean_ms, query
  FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
```

---

## 9. Backup & disaster recovery

### 9.1 Stratégie
- `pg_dump` quotidien 04:00 UTC → R2 (compressed, ~5 GB → ~1 GB).
- Retention 7 dailies + 12 monthlies.
- WAL archiving **désactivé v1** (coût CPU/RAM insoutenable sur CX11).
- Replica streaming **hors scope v1** (nécessite 2e VPS = budget x2).

### 9.2 RPO / RTO

| Scénario | RPO | RTO |
|---|---|---|
| VPS crash + restore pg_dump | **24 h** | **1 h** |
| Table corruption localisée | 24 h | 30 min |
| Event partition archivée perdue | 0 (cold R2) | 15 min |
| R2 complet perdu | n/a | optionnel cross-region B2 |

### 9.3 Runbook restore complet

```bash
apt install postgresql-16 postgresql-16-partman && systemctl start postgresql
sudo -u postgres psql -c "CREATE USER basefinder WITH PASSWORD '...';
                          CREATE DATABASE basefinder OWNER basefinder;"
aws s3 cp --endpoint-url ... "s3://basefinder-cold/backups/daily/latest.dump" /tmp/
sudo -u postgres pg_restore -d basefinder -j 2 --no-owner /tmp/latest.dump   # ~45 min pour 5 GB
psql -d basefinder -c "SELECT COUNT(*) FROM bots; SELECT MAX(at) FROM events;"
systemctl start basefinder-backend
```

### 9.4 Test restore mensuel
**Obligatoire** : le 1er du mois, spinup VPS éphémère Hetzner (0.008 €/h), restore dump récent,
verify `COUNT(*)`, destroy. Budget **~0.01 € par test**. Alerte si FAIL.

---

## 10. Phase 0 → v1 → v2 : migrations

**Phase 0 (1 bot, 4 sem)** : DDL minimal **sans partitioning**, volume ~0.9 GB, Postgres standalone
sans tuning. Tables `bots`, `sessions`, `shards`, `bases` comme §3.3–3.4 ; `chunks_scanned` et
`events` en tables simples (pas de `PARTITION BY`), mêmes index qu'en §3.

**Phase 0 → v1 (fenêtre ~30 min, stop ingest)** : ajout partitioning.

```sql
BEGIN;
ALTER TABLE events RENAME TO events_legacy;
CREATE TABLE events (...) PARTITION BY RANGE (at);  -- DDL §3.1
CREATE TABLE events_2026_04 PARTITION OF events FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
-- ... partitions couvrant events_legacy
INSERT INTO events SELECT * FROM events_legacy;   -- ~2 min / 1M rows
DROP TABLE events_legacy;
CREATE EXTENSION pg_partman;
SELECT partman.create_parent(...);
COMMIT;
```

Même procédure pour `chunks_scanned` → PARTITION BY LIST (dim).

**v1 → v2 (cold archive + Redis)** : cron archivage mensuel (§6.1) ; `apt install redis-server`
maxmemory 256 MB allkeys-lru ; Caffeine cache Ktor (code) ; créer `bot_state_latest` (§3.5).
**Aucune migration DDL bloquante.**

---

## 11. Risques data

1. **Saturation disque 40 GB** avant archive cold (proba haute sans downsampling, impact CRIT ingest stoppé). Parade : downsample BotTick 1 Hz → 0.1 Hz dès v2 ; alerte 80 % ; partman retention 90j strict ; upgrade CX21 80 GB pour +4 €/mois.
2. **Pipeline ingest lag** (bot burst > COPY throughput, proba moyenne, WARN perte potentielle). Parade : Channel bounded 10 k + back-pressure ; WAL fichier spillover si queue pleine ; métrique `ingest_backlog_size` + alerte ; COPY binary (x4 vs INSERT).
3. **Index bloat** events BIGSERIAL + PK composite (proba moyenne, WARN taille x2 à 6 mois). Parade : `REINDEX CONCURRENTLY` mensuel 04:00 ; partman drop vieilles partitions = reset naturel ; monitoring `pg_stat_user_indexes.idx_scan`.
4. **UPSERT contention** chunks_scanned (10 bots mêmes régions, proba moyenne, WARN deadlocks). Parade : sharding logique 1 shard = 1 bot exclusif (06 §4), pas de conflit inter-bot ; WHERE scan_hash <> EXCLUDED skip no-op ; batcher UPSERT 500 via temp table + INSERT SELECT ON CONFLICT.
5. **Backup R2 jamais testé ou corrompu** (proba moyenne, CRIT data irrécupérable). Parade : test restore mensuel automatisé (§9.4) + alerte FAIL ; cross-region R2 → B2 secondaire (~1 $/mois) ; checksum SHA256 à côté du dump ; 7+12 dumps empêche 1 seul corrompu de tuer tout.

---

## Annexe : budget VPS CX11 (2 GB RAM / 40 GB SSD)

**RAM v1** : Postgres shared_buffers 512 + work_mem×20 conn 160 + maintenance 256 + OS cache 800
+ JVM Xmx 512 (Caffeine ≤50 inclus) + OS 150 = **~1.9 GB / 2 GB** ⇒ sous tension, pas de Redis.
En v2 Redis +256 MB ⇒ **upgrade CX21 requis** (4 GB).

**Disque** : Postgres data (events hot 90j downsampled + chunks_scanned + bases + idx) ~34 GB
+ WAL ~1 + pg_dump burst 04:00 ~5 + parquet staging /tmp 04:30 ~2 + OS ~2 = **~45 GB peak** > 40 GB.

**Parade** : les bursts pg_dump/parquet sont séquentiels (04:00 puis 04:30), `/tmp` vidé après
upload R2. Plafond atteint uniquement sur échec upload R2 répété → alerte §8.3. Si v2 sature :
**upgrade CX21** (2 vCPU, 4 GB, 80 GB SSD) à **6 €/mois**, toujours dans budget 5–8 €/mois.

---

_Fin du livrable. Budget ≤600 lignes respecté._
