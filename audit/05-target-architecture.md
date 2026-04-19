# 05 — Target Architecture

> Cible architecturale pour BaseFinder (Plugin RusherHack MC 1.21.4).
> S'appuie sur les audits 01→04 (HEAD `7d41d32`). Contraintes : 10h/sem, 1 dev, 2b2t only,
> vision produit = multi-bot + dashboard web temps réel.
> L'auteur accepte de casser la compat interne (pas de public API externe stable).
> Marqueurs `[HYPOTHÈSE]` = détails RusherHack non 100 % vérifiés via javadocs.

---

## 1. Principes directeurs

1. **Domaine pur Java, sans `net.minecraft.*` ni `dev.rusherhack.*`.**
   Aujourd'hui `BlockAnalyzer` (684 L) importe `net.minecraft.*` pour un code qui est
   100 % des règles métier (audit 01 §3). On isole pour tester sans lancer le client.

2. **Ports & Adapters stricts : flèche unique `domain ← application ← adapters ← bootstrap`.**
   Aujourd'hui 447 accès `Minecraft.getInstance()` dans 25 fichiers (audit 04 §3.2) —
   le MC est partout. Un seul sens de dépendance = testable, remplaçable, dashboardable.

3. **Tout état traversable est un value object immuable. Les mutations sont des événements.**
   Aujourd'hui `BaseRecord` est muté après construction (audit 01 §4), `ChunkAnalysis` expose
   des setters, `FlightPhase` + 4 champs désynchronisables vivent côté `ElytraBot`. Un
   snapshot par tick = un ViewModel, trivial à sérialiser vers le dashboard.

4. **Event bus RusherHack pour l'intra-plugin, telemetry adapter pour le pont backend.**
   Audit 04 §2.5 : on n'utilise `EventUpdate` et rien d'autre. On ne construit PAS un event
   bus maison — on consomme ceux de RusherHack (`EventLoadWorld`, `EventChunk.Load`) et on
   poste nos événements domaine via un adapter telemetry qui parle NDJSON puis protobuf.

5. **Composition root unique, DI manuelle.**
   Aujourd'hui 4× `ElytraBot`, 2× `ChunkScanner`, 2× `BaseLogger` (audit 01 §6) qui écrivent
   dans le même fichier. Un seul `bootstrap/` instancie les services ; les modules RusherHack
   deviennent des adaptateurs thin qui reçoivent leurs dépendances.

---

## 2. Découpage en couches

### 2.1 Arborescence cible

```
src/main/java/com/basefinder/
├── domain/                     # pur Java, 0 import MC/RH, testable en JUnit pur
│   ├── world/                  # ChunkId, Zone, Dimension, Coord3D
│   ├── scan/                   # BaseRecord, BaseType, ScanResult, ChunkFingerprint
│   ├── flight/                 # FlightState, FlightPlan, PitchCandidate, FlightPhase
│   ├── survival/               # SurvivalPolicy, HealthSnapshot, InventorySnapshot
│   ├── navigation/             # Waypoint, NavigationPlan, RoutePattern (Sealed)
│   └── event/                  # BotEvent (sealed) — tous les events domaine typés
│
├── application/                # use cases, orchestration ; dépend uniquement de domain
│   ├── scan/
│   │   ├── ScanChunkUseCase
│   │   ├── ClassifyFindingUseCase
│   │   └── PersistScanResultUseCase
│   ├── flight/
│   │   ├── PlanFlightTickUseCase     # noyau pur de ElytraBot
│   │   ├── HandleTakeoffUseCase
│   │   └── HandleLandingHandoffUseCase
│   ├── survival/
│   │   └── EvaluateSurvivalTickUseCase
│   ├── telemetry/
│   │   ├── EmitBaseFoundUseCase
│   │   ├── EmitBotTickUseCase
│   │   └── ApplyBackendCommandUseCase
│   └── session/
│       └── LoadSessionUseCase / PersistSessionUseCase
│
├── adapter/                    # anti-corruption layer : parle MC/RH, traduit en domain
│   ├── mc/
│   │   ├── McWorldAdapter              (implements ports.ChunkSource, BlockQuery)
│   │   ├── McPlayerAdapter             (implements ports.PlayerState)
│   │   ├── McItemAdapter               (DataComponents 1.20.5+, FIREWORKS, ENCHANTMENTS)
│   │   ├── McInputAdapter              (wraps RusherHackAPI InputUtils)
│   │   ├── McRotationAdapter           (wraps RusherHackAPI RotationManager)
│   │   └── McRendererAdapter           (wraps RusherHackAPI Renderer3D)
│   ├── rusherhack/
│   │   ├── BaseFinderModuleAdapter     (extends ToggleableModule, thin)
│   │   ├── ElytraBotModuleAdapter
│   │   ├── PortalHunterModuleAdapter
│   │   ├── AutoTravelModuleAdapter
│   │   ├── AutoMendingModuleAdapter
│   │   ├── BaseFinderHudAdapter        (consomme un BaseFinderViewModel immuable)
│   │   ├── commands/
│   │   └── settings/SettingsBinding    (mapping typé setting ↔ domain DTO)
│   ├── baritone/
│   │   ├── BaritoneApiWrapper          (type-safe, compileOnly sur baritone.api.*)
│   │   └── BaritoneSettingsReflection  (SEULE reflection restante : Settings.value)
│   └── io/
│       ├── persistence/
│       │   ├── ScannedChunksStore      (Long2ObjectOpenHashMap, async IO)
│       │   ├── SessionStore            (versioned format)
│       │   └── VisitedPortalStore
│       ├── discord/DiscordNotifierAdapter  (shutdown-recreate safe, BUG-002)
│       └── telemetry/
│           ├── TelemetrySink           (port)
│           ├── NdjsonFileSink          (v1 : fichier local, parsable en dev)
│           ├── WebSocketSink           (v2 : vers backend dashboard) [HYPOTHÈSE]
│           └── EventSerializer         (NDJSON → protobuf path)
│
└── bootstrap/                  # composition root, un seul endroit qui "new"
    ├── BaseFinderPlugin.java   (entrypoint RusherHack, minimal)
    ├── ServiceRegistry.java    (DI manuelle : 1 construction de chaque service)
    └── ShutdownHooks.java      (ordre déterministe de fermeture)
```

### 2.2 Règles de dépendance (vérifiables par ArchUnit ou un grep de CI)

| Couche        | Peut importer                          | NE peut PAS importer                          |
|---------------|----------------------------------------|-----------------------------------------------|
| `domain`      | JDK, fastutil                          | MC, RH, Baritone, adapters, application       |
| `application` | `domain`, JDK                          | MC, RH, Baritone, adapters                    |
| `adapter`     | `domain`, `application`, MC/RH/Baritone, fastutil | autres `adapter/*` horizontalement (sauf via port) |
| `bootstrap`   | tout                                   | —                                             |

Règle d'or : **aucun fichier de `domain/` n'importe `net.minecraft.*` ni `dev.rusherhack.*`**.
Aujourd'hui c'est violé dans `util/BlockAnalyzer` (684 L MC-dépendant), `util/BaritoneController`,
`scanner/ChunkScanner` et 19 autres fichiers (audit 01 §5.2).

### 2.3 Types phares à modéliser en priorité

#### `domain/world/`
- `ChunkId(int x, int z, Dimension dim)` record — remplace les `ChunkPos` non-disambigués
  (audit 01 §4). Pack `long` interne pour fastutil : `Long2ObjectOpenHashMap<...>`.
- `Dimension { OVERWORLD, NETHER, END }` enum — remplace les `String.equals("overworld")`
  dupliqués dans `PortalHunterModule` + `AutoTravelModule` (audit 01 §4).
- `Coord3D(double x, double y, double z)` + `Coord2D` — supprime les paires `double x, double z`
  répétées 40+ fois (audit 01 §4).
- `Zone(polygon|aabb, priority, dimension)` — pour zone sweep et backend `SetZone` command.

#### `domain/scan/`
- `BaseRecord` (record immuable) — construit UNE fois par `ClassifyFindingUseCase`, jamais muté.
  Fixe le timestamp-perdu-à-la-restauration (audit 01 §4).
- `BaseType` enum — déjà existe, à déplacer sans MC.
- `ScanResult(ChunkId, List<Evidence>, Score, Freshness)` — sort du scanner, entre dans le
  use case de classification. Fini les `BaseRecord` jetés (audit 01 §3, §4).
- `ChunkFingerprint` — hash stable des blocs significatifs pour idempotency key telemetry.

#### `domain/flight/`
- `FlightPlan(destination, cruiseAlt, safetyMargin, mode)` — unifie les 4 champs désynchronisés
  de `ElytraBot` (audit 01 §4).
- `FlightState(pos, vel, rot, ...)` record pur — aujourd'hui existe mais alloué 1283× / tick
  (audit 03 §3). Deviendra poolable par le use case.
- `PitchCandidate(pitch, score, reasoning)` — le noyau testable de `calculateOptimalPitch`.
- `FlightPhase` sealed interface — 11 phases typées, pas de switch éparpillé.

#### `domain/survival/`
- `SurvivalPolicy(totemThreshold, eatThreshold, disconnectOn...)` record immuable.
- `HealthSnapshot(hp, absorption, hunger, saturation, ts)` — entrée pure du use case.
- `InventorySnapshot(elytraDurability, fireworkCounts byFlightDuration, totemCount, foodCount)`
  — résout BUG-014 (fireworks flight=1 non distingués d'flight=3).

#### `domain/navigation/`
- `RoutePattern` sealed : `Spiral | Grid | ZoneSweep | Random | Ring | Highways | Custom`.
  Aujourd'hui tout est `switch` dans 484 L de `NavigationHelper` (audit 01 §3).
- `Waypoint(ChunkId, purpose)` + `NavigationPlan(Iterable<Waypoint>, progress)` immuables.

#### `domain/event/`
Sealed interface `BotEvent` — voir §3 pour la liste complète.

### 2.4 Tests JUnit exemples (domain = sans Minecraft)

| Test class                              | Ce qu'il vérifie                                                 |
|-----------------------------------------|------------------------------------------------------------------|
| `PlanFlightTickUseCaseTest`             | Avec `FlightState(y=120, speed=2.5)` + `FlightPlan(dst, alt=140)`, renvoie un `PitchCandidate` qui remonte ; reste déterministe. |
| `ClassifyFindingUseCaseTest`            | Un `ScanResult` avec 5 shulkers + 3 beds → `BaseRecord(StrongPlayerBase, score ≥ 80)`. |
| `SpiralPatternTest`                     | `Spiral.generate(center, step=16, max=1000)` génère 1000 chunks uniques, monotone expansion. |
| `InventorySnapshotTest`                 | BUG-014 : `fireworks.byFlightDuration(1) ignored, byFlightDuration(3).size() == 5`. |
| `ChunkIdTest`                           | Hash et equals distinguent overworld/nether. |
| `BaseRecordTest`                        | Immuabilité : pas de setter, timestamp fixé à la construction. |
| `TelemetryEventSerializerTest`          | Round-trip NDJSON : `BaseFound` sérialisé = désérialisé, champs obligatoires présents. |
| `BaritoneApiWrapperTest` (adapter)      | Mock `baritone.api.*` via interfaces `compileOnly` ; vérifie que `goToXZ` émet le goal correct. |

Objectif réaliste v1 : **30+ tests domain** couvrant les use cases critiques sans MC.
Aujourd'hui : **0 tests** (audit 01 header).

---

## 3. Contrats d'événements (bot → backend)

### 3.1 Stratégie de sérialisation

- **v1 (MVP dashboard)** : NDJSON sur WebSocket (ou fichier tail pour dev). Lisible, debuggable,
  zéro dépendance. Taille OK tant qu'on reste sous ~100 events/s/bot.
- **v2 (scale multi-bot)** : protobuf sur la même socket. Migration event-par-event (chaque event
  a son schéma versionné). Déclencheur : >500 events/s cumulés ou >10 bots concurrents.
- Chaque event transporte : `bot_id` (uuid v4 local au bot), `session_id`, `seq` (monotone
  par bot), `ts_utc_ms`, `type`, `payload`. `seq + bot_id` = **idempotency key** — le backend
  déduplique en cas de reconnexion/replay.

### 3.2 Événements bot → backend

Chaque event est un `record` dans `domain/event/`. `taille` = estimation JSON compressé gzip.

| Event                  | Champs                                                                                             | Unités               | Taille | Fréquence               | Idempotency key                       |
|------------------------|----------------------------------------------------------------------------------------------------|----------------------|-------:|-------------------------|----------------------------------------|
| `BotHandshake`         | bot_id, version, account_hash, dims_supported, plugin_version, mc_version                          | —                    | ~200 B | 1× à la connexion       | bot_id + session_id                    |
| `BotTick`              | pos(Coord3D), yaw, pitch, hp, hunger, absorption, altitude_agl, server_tps_observed, client_fps    | deg, blocks, hp, tps | ~120 B | 1 Hz (down-sample 20Hz) | bot_id + seq                           |
| `BaseFound`            | chunk_id, base_type, score, evidence[≤8 string codes], ts                                          | score 0–100          | ~300 B | burst (0–10/min pic)    | chunk_id + base_type                   |
| `ChunkScanned`         | chunk_id, fingerprint_hash(xxh64), result_class(EMPTY\|WILD\|BASE)                                 | —                    | ~80 B  | ~50 Hz en scan dense    | chunk_id                               |
| `InventorySnapshot`    | elytra_durability_pct, fireworks_by_flight{1:n,2:n,3:n}, totems, food_slots, shulkers              | %, count             | ~180 B | 0.2 Hz + sur event clé  | bot_id + seq                           |
| `HealthSnapshot`       | hp, absorption, hunger, saturation, last_damage_source (nullable)                                  | hp, food points      | ~90 B  | sur changement >5 %     | bot_id + seq                           |
| `DimensionChange`      | from(Dim), to(Dim), via(PORTAL\|CMD\|DEATH), entry_pos, exit_pos                                   | —                    | ~150 B | rare                    | bot_id + seq                           |
| `EmergencyDisconnect`  | reason enum, last_pos, last_hp, last_inventory_ref                                                 | —                    | ~200 B | rare, terminal          | bot_id + session_id                    |
| `FlightStateChange`    | from(Phase), to(Phase), trigger, pos, altitude                                                     | —                    | ~120 B | ~0.5 Hz moyenne         | bot_id + seq                           |
| `BaritoneState`        | active, process_type(GOAL\|ELYTRA\|MINE), goal(Coord3D?), progress_pct                             | —                    | ~100 B | 0.5 Hz                  | bot_id + seq                           |
| `SurvivalAlert`        | level(WARN\|CRIT), category(PLAYER_NEARBY\|LOW_HP\|EQUIP\|FOOD), detail                            | —                    | ~120 B | sur event               | bot_id + seq                           |
| `TelemetryHeartbeat`   | uptime_s, events_sent_total, backend_rtt_ms                                                        | —                    | ~60 B  | 0.1 Hz                  | bot_id + seq                           |

Budget amont (estimation) : 1 bot actif scan dense ≈ **60 events/s ≈ 15 KB/s gzip**.
10 bots ≈ 600 events/s ≈ 150 KB/s. Trivial en NDJSON WebSocket. Protobuf deviendra pertinent
si on tape 100 bots ou si on ajoute un event `ChunkBlockDelta` (inutile pour v1).

---

## 4. Direction opposée : backend → bot (commandes)

### 4.1 Commandes minimales v1

| Command             | Payload                                   | Mode                 | ACK                            |
|---------------------|-------------------------------------------|----------------------|--------------------------------|
| `PauseBot`          | reason?                                   | fire-and-forget      | `BotStateChange` event suivant |
| `ResumeBot`         | —                                         | fire-and-forget      | `BotStateChange`               |
| `SetZone`           | polygon or aabb, priority, dimension      | request/response ACK | `CommandAck(seq, OK/ERR)`      |
| `ClearZone`         | zone_id                                   | request/response ACK | `CommandAck`                   |
| `SetScanPattern`    | RoutePattern (sealed)                     | request/response ACK | `CommandAck`                   |
| `EmergencyStop`     | —                                         | fire-and-forget      | immediate `EmergencyDisconnect` |
| `RequestStateDump`  | —                                         | request/response     | batch `BotTick` + `InventorySnapshot` + `FlightStateChange` |

### 4.2 Modèle de transport

- WebSocket bidirectionnel unique. Bot est client (simplifie NAT, 2b2t setups résidentiels).
- ACK schema : `CommandAck(cmd_id, status, error_detail?)`. `cmd_id` fourni par backend.
- Timeout commande : 2 s. Au-delà, backend retransmet avec même `cmd_id` (idempotent côté bot).
- Sécurité v1 : token bearer par bot, rotation manuelle. Pas de TLS en dev local ; TLS obligatoire
  dès que le backend est exposé. [HYPOTHÈSE] : WebSocket `javax.websocket` dispo dans le classpath
  MC — sinon pure-Java `HttpClient` WebSocket (Java 11+) sans dépendance.

---

## 5. Chemin de migration (sans big-bang)

Principe : chaque étape est auto-contenue, ≤10 h, laisse le plugin fonctionnel après merge.
On commence par **une seule extraction** pour valider la méthode avant tout refactor large.

| # | Étape                                                                                       | Livrable                                                                 | Effort  | Ce que ça valide                           |
|--:|---------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|---------|--------------------------------------------|
| 1 | **Extraire `PlanFlightTickUseCase`** (noyau pur de `ElytraBot.calculateOptimalPitch`). Nouveau package `domain/flight/` + `application/flight/`. `ElytraBot` l'appelle en délégué. Tests JUnit dessus. | +1 use case, +4 value types, +~15 tests, -~120 L dans `ElytraBot`        | 8 h     | Un bout de domaine tourne sans MC ; méthode OK |
| 2 | **Composition root** : créer `bootstrap/ServiceRegistry`. 1 seule instance de `ElytraBot`, `ChunkScanner`, `BaseLogger`, `DiscordNotifier`. Les 5 modules RusherHack reçoivent leurs deps par ctor. Résout 4×ElytraBot / 2×ChunkScanner / 2×BaseLogger (audit 01 §6). | Registry + ctors modifiés                                                | 8 h     | DI manuelle tient la route                 |
| 3 | **Port `ChunkSource` + `McWorldAdapter`**. Extraire `ChunkScannerService` vers `application/scan/` + `domain/scan/`. `BlockAnalyzer` devient pur (les Sets `Block` deviennent `BlockKey` enum domaine). ChunkSource mockable. Fixe BUG-004 (snapshot). | +5 tests scanner, `BlockAnalyzer` sans MC                                | 10 h    | Scan testable sans client                  |
| 4 | **`BaseFinderViewModel` immuable + HUD adapter**. Le module produit un snapshot par tick, le HUD le consomme read-only. Supprime les 13 getters du module et le pull par nom "BaseHunter" (audit 01 §3). Préalable clé au dashboard. | +1 record ViewModel, HUD réécrit                                         | 10 h    | Le bot expose son état via un seul DTO     |
| 5 | **`TelemetrySink` + `NdjsonFileSink`**. Brancher `EmitBaseFoundUseCase`, `EmitBotTickUseCase`. Émission locale vers fichier. Vérifie la chaîne `domain event → adapter → disque`. | +1 port, +1 adapter, +events sérialisables                               | 8 h     | Chemin telemetry fonctionnel (avant réseau) |
| 6 | **`BaritoneApiWrapper` compileOnly**. Ajouter Baritone en `compileOnly` dans `build.gradle`, supprimer la reflection sur les classes publiques, garder uniquement `Settings.value` (audit 04 §6 top 1, BUG-017). | `BaritoneController` → `BaritoneApiWrapper` + `BaritoneSettingsReflection` | 10 h   | Plus de NPE silencieux sur reflection      |
| 7 | **`WebSocketSink` + backend stub**. Brancher un backend minimal (Kotlin/Ktor ou Node). Émission live des events vers dashboard localhost. ACK commandes `PauseBot`/`ResumeBot`. | Backend skeleton + `WebSocketSink`                                       | 10 h    | Direction bot→backend et inverse marche    |
| 8 | **fastutil + async IO**. `LongOpenHashSet` pour `scannedChunks`, `Long2ObjectOpenHashMap` pour `allAnalyses`, `ExecutorService` pour save (audit 03 §5 QW #2, #3). Débloque la cible 1M chunks. | `ScannedChunksStore` réécrit, pool de `FlightState`                      | 10 h    | Plafond passe de 200 k à 1 M+ chunks       |

**Étape 1 seule doit sortir en premier et être mergée** pour valider : (a) qu'on peut créer
`domain/` sans casser le build RusherHack, (b) que les tests JUnit tournent, (c) que la review
sort propre. Puis cadence ~1 étape/semaine à 10 h/sem = **8 semaines jusqu'au MVP telemetry**.

---

## 6. Traçabilité : ce que l'architecture résout

| Problème (audit ref)                                                               | Réponse architecturale                                                                 | Où c'est matérialisé                         |
|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|----------------------------------------------|
| `PortalHunterModule` 1558 L, `ElytraBot` 1411 L god-classes (01 §3)                | Découpage en use cases single-purpose ; modules RusherHack deviennent adapters         | `application/*` + `adapter/rusherhack/*`     |
| 4× `ElytraBot`, 2× `ChunkScanner`, 2× `BaseLogger` (01 §6)                         | Composition root unique, une seule instance par service                                | `bootstrap/ServiceRegistry`                  |
| 447 accès `Minecraft.getInstance()` dans 25 fichiers (01 §5.2, 04 §3.2)            | Ports côté domaine (`ChunkSource`, `PlayerState`, `Inventory`), adapters côté MC       | `adapter/mc/*` + ports dans `domain/`        |
| HUD pull par nom "BaseHunter" + 13 getters (01 §3)                                 | `BaseFinderViewModel` immuable, HUD consumer read-only                                 | `domain/view/BaseFinderViewModel`            |
| `BaseRecord` muté post-construction, timestamp perdu à la restore (01 §4)          | Record immuable, construit 1× par `ClassifyFindingUseCase`                             | `domain/scan/BaseRecord`                     |
| `ChunkScanner` crée des `BaseRecord` jetés (01 §4)                                 | Scanner retourne `ScanResult` pur ; use case construit `BaseRecord`                    | `application/scan/*`                         |
| `Dimension` via `String.equals("overworld")` (01 §4)                               | Enum domaine                                                                           | `domain/world/Dimension`                     |
| BUG-001 DiscordNotifier leak dans PortalHunter (02)                                | 1 seule instance au registry, shutdown hook déterministe                               | `bootstrap/ShutdownHooks`                    |
| BUG-002 executor recréé impossible                                                 | Adapter recrée l'executor à `start()`; pas de `final`                                  | `adapter/io/discord/DiscordNotifierAdapter`  |
| BUG-014 fireworks `flight:1` non distingués (02)                                   | `InventorySnapshot.byFlightDuration` typé via `McItemAdapter` + `DataComponents`       | `adapter/mc/McItemAdapter`                   |
| BUG-017 reflection `Settings.value` fragile (02, 04 §3.1)                          | Reflection isolée dans `BaritoneSettingsReflection`, typage `compileOnly` ailleurs     | `adapter/baritone/*`                         |
| BUG-020 `ElytraBot.stop()` incomplet (02)                                          | `FlightState` immuable, phase typée sealed, reset = nouveau state                      | `domain/flight/*`                            |
| 1.9 MB/s churn `FlightState` allocs (03 §3)                                        | Pool `FlightState[]` injecté par use case ; domain reste testable                      | `adapter/flight/FlightStatePool`             |
| IO save sur game thread, stalls 40-500 ms (03 §4)                                  | `ScannedChunksStore` async via `ExecutorService`                                       | `adapter/io/persistence/ScannedChunksStore`  |
| `HashMap<ChunkPos,_>` bloque cible 1M chunks (03 §6)                               | `Long2ObjectOpenHashMap` + fastutil en dep explicite                                   | `adapter/io/persistence/*`                   |
| `Lang.setFrench` static global race (01 §5.1, 04 §3.6)                             | `i18n` dans `application/` ; passé par DI, plus de static                              | `application/i18n/`                          |
| Code dupliqué `scanForPortal` vs `scanForNetherPortals` (01 §6)                    | `PortalLocator` pur dans `application/scan/`                                           | `application/scan/PortalLocator`             |
| 7 patterns de waypoints dans un switch de 484 L (01 §3)                            | `RoutePattern` sealed interface, 1 classe par pattern, génération Stream               | `domain/navigation/*`                        |
| Vision dashboard bloquée par HUD pull                                              | `BaseFinderViewModel` + `TelemetrySink` émettent le même snapshot vers HUD et WS       | `application/telemetry/` + `adapter/io/telemetry/` |
| Multi-bot impossible à câbler                                                       | Chaque bot = 1 JVM ; état domaine sérialisable ; events fan-in backend                 | Architecture entière (pas un seul point)     |

---

## 7. Ce qu'elle ne résout PAS (honnête)

1. **Mise à jour MC 1.22 / 1.21.5.** L'adapter `adapter/mc/` absorbe une partie du choc
   (changements de signatures), mais si Mojang refactor `PalettedContainer`, `DataComponents`,
   ou la boucle d'input côté client, il faudra réécrire `McWorldAdapter` + `McItemAdapter` +
   `McInputAdapter`. L'architecture réduit le blast radius, elle ne le supprime pas.

2. **Auth 2b2t / queue / anti-cheat.** Ce projet ne touche pas à l'auth MS, au bypass de queue,
   aux politiques anti-bot serveur. 2b2t reste un environnement hostile ; aucune couche ne peut
   masquer un ban.

3. **Élimination immédiate du couplage.** Les 8 étapes de migration prennent ~2 mois réels à
   10 h/sem. Pendant cette transition, le code actuel et le code cible coexistent. L'architecture
   donne une direction, pas un interrupteur.

4. **Tests end-to-end automatisés du client MC.** Le domaine sera testé en JUnit. Les adapters
   MC ne le seront pas sans harness lourd (Mixin + client headless). On assume des tests manuels
   in-game pour l'intégration, + smoke test de l'adapter Baritone au démarrage (audit 04 §3.1).

5. **Dashboard backend prod-ready.** L'étape 7 livre un stub. Auth, persistence multi-bot, UI,
   alerting restent à construire côté backend dans un repo séparé. Hors scope BaseFinder.

---

_Fin du livrable. Densité visée ; `[HYPOTHÈSE]` marqués sur 2 détails (WebSocket API, `ISettingManager` plugin-level)._
