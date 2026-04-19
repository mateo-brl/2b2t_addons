# 10 — Roadmap

_4 jalons, ~14-20 semaines calendaires à 10 h/sem._
_Chaque jalon a : objectif mesurable, tâches dimensionnées S/M/L, critère de sortie binaire._

Conventions d'effort : **S** = ≤2 h · **M** = 2-6 h · **L** = 6-12 h. Un jalon ne doit jamais contenir plus d'une L non-optionnelle.

---

## Jalon 0 — Stabilisation (Semaine 1-2, ~12-18 h)

**Objectif mesurable.** Au bout du Jalon 0 : (a) les 5 bugs S1 identifiés en audit 02 sont fixés et couverts par au moins un test qui eût attrapé le bug, (b) la CI `build-release.yml` lance `./gradlew test` et échoue si un test échoue, (c) l'ADR 0001 est mergé sur `main`, (d) la branche `refactor/extract-flight-controller` est mergée (voir livrable inclus dans cet audit).

### Tâches

| # | Tâche | Effort | Fichier(s) ciblé(s) | Test associé |
|--:|---|---|---|---|
| 0.1 | **Merger `refactor/extract-flight-controller`** (livré dans cet audit). Crée `src/test/`, `domain/flight/`, 3 tests JUnit. | déjà fait | `src/main/java/com/basefinder/domain/flight/*` + `src/test/...` | 3 tests existent |
| 0.2 | **Activer `./gradlew test` dans `.github/workflows/build.yml`**. Ajouter step avant le package. | S | `.github/workflows/build.yml` | CI rouge si test rouge |
| 0.3 | **BUG-001 Discord leak PortalHunter** : propager `baseLogger.getDiscordNotifier().shutdown()` dans `PortalHunterModule.onDisable()`. | S | `PortalHunterModule.java` | Test : 10 toggles consécutifs → 0 thread résiduel |
| 0.4 | **BUG-014 Fireworks `flight:1`** : créer `McItemAdapter.findFireworkByMinFlight(int min)` qui lit `DataComponents.FIREWORKS.flightDuration`. Remplacer `findFireworkInHotbar`. | M | `FireworkResupply.java`, nouveau `adapter/mc/McItemAdapter.java` | Test unitaire sur adapter avec ItemStack mocké |
| 0.5 | **BUG-020 ElytraBot.stop() incomplet** : reset `pendingInventorySwap`, `previousSlotBeforeFirework`, `lastPosition`, `stuckTimer`, `tickCounter`, `fireworkCooldown`. | S | `ElytraBot.java:stop()` | Test : `start();…stop();start();` → état initial |
| 0.6 | **BUG-017 NPE reflection Baritone** : isoler la reflection dans `adapter/baritone/BaritoneSettingsReflection` avec try/catch explicite + warn logué. | M | nouveau `adapter/baritone/BaritoneSettingsReflection.java`, `BaritoneController.java` | Test : classloader sans Baritone → pas de NPE, warn logué |
| 0.7 | **BUG-011 shulker perdu** : réintégrer placement shulker dans `AutoMendingModule.abort()` via `InventoryAction` idempotent. | M | `AutoMendingModule.java` | Test : abort mid-mending → shulker en slot d'origine |
| 0.8 | **`printStackTrace` → logger** : remplacer les 11 sites par `LOGGER.error(...)` (audit 04 §6 #2). | S | `BaseFinderPlugin.java` + 3 autres | — |
| 0.9 | **Épingler `rusherhack-api` sur snapshot datée** (audit 04 §6 #3). | S | `build.gradle`, `gradle.properties` | Build reproductible |
| 0.10 | **Merger ADR 0001** sur `main` après review. | S | `docs/adr/0001-layered-architecture.md` | Fichier présent sur main |

### Critère de sortie Jalon 0
- [ ] `./gradlew build` vert sur `main` ET sur CI ET sur une VM propre.
- [ ] `./gradlew test` vert sur au moins 4 classes de test.
- [ ] Les 5 bugs S1 sont fermés (avec soit un test, soit une note explicite pourquoi non-testable).
- [ ] Le nombre total de lignes dans `ElytraBot.java` est **< 1 350** (contre 1 411 aujourd'hui).

### Pourquoi ce jalon d'abord
On ne part pas en refactor d'architecture massif avec 5 bugs S1 non-colmatés — ça masquerait les régressions. On ne part pas non plus en architecture sans **un** test qui compile : c'est le *proof of concept* qu'un `domain/` peut exister.

---

## Jalon 1 — Architecture en couches (Semaine 3-6, ~30-40 h)

**Objectif mesurable.** Au bout du Jalon 1 : (a) le dossier `domain/` contient 4-5 use cases purs testables, (b) toutes les instanciations de services passent par une unique `ServiceRegistry`, (c) `BaseFinderHud` ne tire plus sur `getFeature("BaseHunter")` mais consomme `BaseFinderViewModel`, (d) le build reste vert à chaque étape et le plugin reste fonctionnel en jeu.

Cartographie : couvre les **étapes 2 à 4** du chemin de migration (audit 05 §5). L'étape 1 (`PlanFlightTickUseCase`) est déjà livrée au Jalon 0.

### Tâches

| # | Tâche (réf. audit 05 §5) | Effort | Sortie |
|--:|---|---|---|
| 1.1 | **Étape 2 — Composition root `bootstrap/ServiceRegistry`**. Une seule instance de `ElytraBot`, `ChunkScanner`, `BaseLogger`, `DiscordNotifier`. Les modules reçoivent leurs deps par constructeur. Supprime la liste en dur `isElytraBotInUse()`. | L | `bootstrap/ServiceRegistry.java` + 5 constructeurs modifiés |
| 1.2 | **Étape 3a — Port `ChunkSource` + `domain/scan/BlockKey`**. `BlockAnalyzer` devient pur : les `Set<Block>` deviennent `Set<BlockKey>` enum domaine. | L | `domain/scan/ChunkSource.java`, `domain/scan/BlockKey.java`, `adapter/mc/McChunkSourceAdapter.java` |
| 1.3 | **Étape 3b — `ChunkScannerService` dans `application/scan/`**. Le scanner retourne un `ScanResult` pur ; un `ClassifyFindingUseCase` construit les `BaseRecord`. Supprime la double source de vérité audit 01 §4. | L | `application/scan/ChunkScannerService.java`, `application/scan/ClassifyFindingUseCase.java` |
| 1.4 | **Étape 3c — Tests scan**. Au moins 5 tests JUnit : score cluster, dédup, `BlockKey` mapping, cave-air heuristic, entity fingerprint. | M | `src/test/.../scan/*` |
| 1.5 | **Étape 4a — `BaseFinderViewModel` immuable** (record Java). Produit par tick par le module principal. | M | `domain/view/BaseFinderViewModel.java` |
| 1.6 | **Étape 4b — `BaseFinderHud` réécrit**. Consomme `BaseFinderViewModel`, supprime les 13 getters du module et le pull par nom. | L | `hud/BaseFinderHud.java`, `BaseFinderModule.java` |
| 1.7 | **Cleanup** : extraction `Dimension` enum (supprime `.equals("overworld")` — audit 01 §4), extraction `RoutePattern` sealed (optionnel, peut glisser au Jalon 3). | M | `domain/world/Dimension.java`, `domain/navigation/*` |

### Critère de sortie Jalon 1
- [ ] 4-5 use cases dans `application/`, chacun couvert par ≥ 1 test JUnit.
- [ ] Aucun `new ElytraBot()` en dehors de `ServiceRegistry`.
- [ ] Aucun `.getFeature("BaseHunter")` dans `hud/`.
- [ ] `ElytraBot.java` < 1 000 L.
- [ ] `PortalHunterModule.java` < 1 200 L (début de découpage via use cases scan).
- [ ] `./gradlew test` : ≥ 15 tests verts.

### Risque principal
Étape 3 (ChunkSource) est la plus coûteuse en revue — beaucoup de fichiers touchés. **Mitigation** : garder `ChunkScanner` ancien + nouveau en parallèle pendant 1 semaine, feature-flag, puis supprimer l'ancien. Pas de big-bang.

---

## Jalon 2 — Ingest plateforme (Semaine 7-9, ~25-30 h côté bot + ~25 h backend Phase 0 + ~20 h dashboard)

**Objectif mesurable.** Au bout du Jalon 2 : (a) 1 bot envoie ses `BotTick`, `BaseFound`, `InventorySnapshot` vers un backend Kotlin en WebSocket, (b) un dashboard web affiche en temps réel un bot vivant (Fleet Overview + Bot Detail read-only), (c) le dashboard est déployé sur un VPS à 5-8 €/mois, accessible via HTTPS.

Cartographie : couvre les **étapes 5, 7** du chemin de migration (audit 05 §5) + Phase 0 backend (audit 07 §9) + Phase 0 dashboard (audit 08 §9).

### Tâches BOT (~15 h)

| # | Tâche | Effort |
|--:|---|---|
| 2.1 | **Étape 5a — `TelemetrySink` port + events sérialisables**. `BotHandshake, BotTick, BaseFound, ChunkScanned, InventorySnapshot, HealthSnapshot, DimensionChange, EmergencyDisconnect, FlightStateChange` en records. | L |
| 2.2 | **Étape 5b — `NdjsonFileSink` adapter** (debug, émet dans un fichier local). Valide la chaîne event→sérialisation. | M |
| 2.3 | **Étape 7 — `WebSocketSink` + handshake**. java.net.http ou OkHttp, reconnect exponentiel, buffer local si backend down (BUG parade). | L |
| 2.4 | **Command receiver minimal** : `PauseBot`, `ResumeBot`, `EmergencyStop`. | M |

### Tâches BACKEND Phase 0 (~25 h)

| # | Tâche | Effort |
|--:|---|---|
| 2.5 | **Module Gradle `contracts/`** partagé bot↔backend. Records events en Java côté bot, data class Kotlin côté backend, 1 schéma de référence. | M |
| 2.6 | **Setup projet Kotlin + Ktor**, Dockerfile multi-stage, docker-compose avec Postgres 16 + traefik. | L |
| 2.7 | **Tables `bots`, `sessions`, `events`, `bases`** (DDL audit 07 §4, pas de partitioning en Phase 0). Flyway ou Liquibase. | M |
| 2.8 | **WS ingest handler** : auth bearer, handshake, stream NDJSON, COPY batched. | L |
| 2.9 | **REST read** : `GET /api/bots`, `GET /api/bots/:id/state`, `GET /api/bases`, `GET /api/events/stream` (SSE). Discord OAuth2 minimal (1 user admin = ton Discord ID en env). | L |
| 2.10 | **Déploiement VPS Hetzner CX11** + domaine + TLS Let's Encrypt. | M |

### Tâches DASHBOARD Phase 0 (~20 h)

| # | Tâche | Effort |
|--:|---|---|
| 2.11 | **Scaffold Vite + React 18 + TS strict + TanStack Router/Query + Tailwind + shadcn/ui**. | M |
| 2.12 | **Écran Fleet Overview** + **Bot Detail read-only** (inventaire, vie, pos, dim, graphes simples). | L |
| 2.13 | **Client SSE `/api/events/stream`** + Zustand store. | M |
| 2.14 | **Login Discord OAuth2** (redirect + JWT cookie). | M |

### Critère de sortie Jalon 2
- [ ] Le plugin RusherHack, en vrai sur 2b2t, envoie au moins un `BotHandshake` + `BotTick` par seconde vers le backend.
- [ ] Le dashboard web, sur HTTPS public, affiche le bot live avec latence < 3 s.
- [ ] `/metrics` expose `events_ingested_total`.
- [ ] Un crash du backend pendant 10 min ne perd aucun `BaseFound` (buffer local bot).

### Risque principal
La dérive zod↔Kotlin sur le schéma des events (audit 08 §10). **Mitigation** : un test CI backend qui sérialise un event Kotlin et le re-désérialise en JSON puis valide avec le schéma zod partagé.

---

## Jalon 3 — Scan multi-bot à l'échelle (Semaine 10-15, ~40-60 h)

**Objectif mesurable.** Au bout du Jalon 3 : (a) 3 bots (ou équivalents alts de test) scannent simultanément la **zone MVP T1 stash belt** (~4 M chunks OW highway N), (b) le dashboard permet de dessiner une zone polygone et de voir en temps réel les chunks scannés en heatmap, (c) les bases trouvées sont dédupliquées et scorées côté backend, (d) un crash bot → claim libéré après timeout → un autre bot reprend le shard.

Cartographie : couvre l'**étape 8** migration + backend v1 + dashboard v1 + MVP scale plan (audit 06 §9).

### Tâches BOT (~15 h)

| # | Tâche | Effort |
|--:|---|---|
| 3.1 | **Étape 6 — `BaritoneApiWrapper` compileOnly** (finalise la dette audit 04 §6 #1, BUG-017 résiduel). | L |
| 3.2 | **Étape 8 — fastutil + async IO** : `LongOpenHashSet` pour `scannedChunks`, `Long2ObjectOpenHashMap` pour `allAnalyses`, `ExecutorService` pour save. Débloque la cible 1M+ chunks (audit 03 QW #1-4). | L |
| 3.3 | **`ScanCampaignModule`** : reçoit un shard du backend, convertit en waypoints `RoutePattern` (serpentin), émet `ChunkScanned` + `BaseFound`, signale `ShardCompleted`. | L |
| 3.4 | **`BackendClient` shard endpoints** : `claim`, `heartbeat`, `release`, `complete` (audit 06 §9). | M |
| 3.5 | **Hot path quick wins** : pool `FlightState`, PITCH 21→13, `ReferenceOpenHashSet<Block>` (audit 03 QW #1, #5, #7). | L |

### Tâches BACKEND v1 (~20 h)

| # | Tâche | Effort |
|--:|---|---|
| 3.6 | **Tables `zones`, `shards`, `chunks_scanned`** + partitioning `events` mensuel via pg_partman, `chunks_scanned` par `dim`. DDL audit 09 §3. | L |
| 3.7 | **Endpoint `POST /api/shards/claim`** avec `SELECT FOR UPDATE SKIP LOCKED`, heartbeat 60 s, release après 5 min sans heartbeat. | L |
| 3.8 | **Endpoint `POST /api/zones`** : polygon → split quadtree 1024² OW (audit 06 §3). | L |
| 3.9 | **Déduplication `BaseFound`** côté backend (UNIQUE `(dim, chunk_x, chunk_z, base_type)` + UPSERT score agrégé). | M |
| 3.10 | **Command queue** : `PauseBot`, `ResumeBot`, `SetZone`, `EmergencyStop` via WS push + ACK typé. | M |

### Tâches DASHBOARD v1 (~20 h)

| # | Tâche | Effort |
|--:|---|---|
| 3.11 | **Écran Map** : MapLibre + tuiles 2b2t custom (overworld / nether toggles), position live bots, heatmap chunks scannés côté backend (tuiles précalculées, pas 4M markers — audit 08 §4.3). | L |
| 3.12 | **Éditeur Zones** : draw polygon, liste zones, assign à N bots. | L |
| 3.13 | **Filter bases** : table virtualisée (`@tanstack/react-virtual`), filter score/type/dim. | M |
| 3.14 | **Actions bot** : boutons Pause/Resume/EmergencyStop avec `useMutation` optimistic + CommandAck. | M |

### Critère de sortie Jalon 3
- [ ] 3 bots scannent une zone 1024×1024 en ≤ 24 h calendaires cumulées (pas besoin de tourner 24h/24, juste somme temps de vol).
- [ ] Un shard claim est libéré dans les 5 min si un bot crash.
- [ ] Le dashboard affiche la heatmap et ≥ 10 bases trouvées dans le shard MVP (stash belt T1 dense — audit 06 §9.3).
- [ ] Aucun doublon de `BaseFound` en DB (dédup SQL UNIQUE vérifié).
- [ ] Le plugin local reste fonctionnel sans backend (mode "standalone" préservé).

### Ce qui reste pour V2 (au-delà de la roadmap)
- 10 bots coordonnés
- Scan Nether + End (aujourd'hui focus OW)
- Re-scan TTL policy
- Roles multi-user dashboard
- Archivage cold Parquet R2 (audit 09 §6)
- Migration NDJSON → protobuf (audit 05 §3)

Ces points sortent du budget "~20 semaines solo" mais sont tous dans les plans 07/08/09.

---

## Récapitulatif effort total

| Jalon | Heures dev | Semaines calendaires à 10 h/sem | Sortie observable |
|:--|--:|--:|:--|
| 0 | 12-18 | 1-2 | Bugs S1 fixés, tests qui tournent, architecture amorcée |
| 1 | 30-40 | 3-4 | Domaine pur, composition root, HUD découplé |
| 2 | 65-75 | 7-8 (parallélisable partiellement backend/front) | Bot live sur dashboard web |
| 3 | 55-70 | 5-7 | 3 bots scannent T1, heatmap dashboard |
| **Total** | **~160-200** | **~16-21** | **Démo multi-bot T1 fin Q3 2026** |

## Règles d'or pour ne pas retomber dans l'accumulation

1. **Jamais plus d'une branche feature ouverte à la fois.** La branche `refactor/extract-flight-controller` est le modèle : petite, testée, mergeable en 1 review.
2. **Pas de nouvelle feature sans test qui l'accompagne.** Le dossier `src/test/` doit grandir à chaque PR.
3. **Pas de contournement par `Minecraft.getInstance()` dans `domain/` ou `application/`.** Si tu as besoin d'un état MC, passe par un port. Sinon le cycle recommence.
4. **Pas de raccourci reflection sans wrapper typé.** On paie une fois le wrapper, on arrête de payer à chaque commit `fix:` sur une régression silencieuse.
5. **Un jalon se termine sur un critère de sortie binaire, pas un feeling.** Relire les bullets à la fin, ne bouger au jalon suivant que si tous cochés.
