# 03 — Performance & scalabilité

Branche `audit/principal-engineer-review`, HEAD `7d41d32`. Aucune mesure runtime disponible : tous les chiffres sont des estimations statiques (`[ORDRE DE GRANDEUR]`) sauf mention explicite.

Hypothèses de travail :
- Java 21, MC 1.21.4 client, 20 TPS cible.
- Fastutil est déjà présent sur le classpath (MC 1.21 l'embarque via `com.mojang:datafixerupper` et `net.minecraft.*` l'utilise en interne) → vérifié dans `~/.gradle/caches/modules-2/files-2.1/it.unimi.dsi/fastutil`. Non déclaré en dépendance explicite dans `build.gradle`, donc à sécuriser (voir §7 / §5).
- Render distance typique 2b2t : 10–16 chunks → 441 à 1089 chunks dans le carré de scan.
- `ElytraBot.tick()` = 1 appel / tick client = 20 Hz.

---

## 1. Hot paths identifiés

| Chemin | Fréquence | Coût estimé (allocs/op) | Impact FPS estimé | Confiance |
|---|---|---|---|---|
| `ElytraBot.calculateOptimalPitch` (cruise, circling) | 1 × / tick vol = 20 Hz | ~30 appels × 42 allocs `FlightState` = **~1 260 allocs**, + 30 arrays `FlightState[40]` | 0.5–1 ms/tick (~5 % frame 16 ms) | Moyenne (estimation allocation) |
| `ElytraBot.applySafePitch` (double simulation 20 ticks) | 1 × / tick vol | 21 `FlightState` + 1 array `[20]` | <0.2 ms | Haute |
| `ChunkScanner.scanLoadedChunks` boucle chunks | `1 / scanInterval` ticks = 1 Hz (scanInterval=20) | Parcourt `(2R+1)²` chunks, scanne jusqu'à 50/tick. Par chunk scanné : `BlockAnalyzer.analyzeChunk` | **1–3 ms/chunk × 50 = 50–150 ms** cap | Moyenne |
| `BlockAnalyzer.analyzeChunk` pass 1 | Par chunk scanné | 16×(8×8×8) = **8 192 `section.getBlockState`** (pas d'alloc Java, mais indirection MC) ; 0–50 `new BlockPos` via `addSignificantBlock` | ~1 ms/chunk [ORDRE DE GRANDEUR] | Moyenne |
| `CaveAirAnalyzer.analyzeChunkCaveAir` | Par chunk scanné | ~24 sections × 256 `getBlockState` + tunnel scan = **~6 000 lookups/chunk** | ~0.5 ms/chunk [ORDRE DE GRANDEUR] | Basse (dépend MC internals) |
| `ChunkScanner.applyClusterScoring` | 1 × par scan cycle | 8 × n `new ChunkPos(...)` + `HashMap.get` → **~400 allocs/cycle** si 50 chunks | Négligeable | Haute |
| `EntityScanner.scanEntities` | Par chunk scanné (scan=ON) | 1 `new AABB` + `Level.getEntities(...)` (alloc `ArrayList` interne MC) | ~0.2 ms/chunk si peu d'entités | Moyenne |
| `StateManager.saveScannedChunks` | Toutes les 5 min (défaut) | **Synchrone sur thread game** — bloque le rendu. 8 bytes × N chunks. Pour 1M chunks ≈ 8 MB write | 50–500 ms stall au save | Haute (code lu) |
| `ElytraBot.getGroundDistance` | Par tick vol, dans `handleDescending`/`handleFlaring` | Jusqu'à **320 `new BlockPos(...)` via `pos.below(dy)`** par appel | 0.2 ms/tick | Haute |
| `ElytraBot.getMaxTerrainAhead` (fallback sans TerrainPredictor) | Par tick vol | `(distance/8)` = ~40 appels `getTerrainHeight` | Dépend cache MC | Moyenne |
| `ChunkScanner.allAnalyses` (full retention) | Cumul indéfini jusqu'à cleanup à 25 000 entrées | ~500–1000 bytes/`ChunkAnalysis` | 25 MB heap au pic | Haute |

---

## 2. Structures de données — audit

| Usage | Type actuel | Alternative recommandée | Gain attendu | Localisation |
|---|---|---|---|---|
| `scannedChunks` (Set<ChunkPos>) | `ConcurrentHashMap.newKeySet()` = `Set<ChunkPos>` | `LongOpenHashSet` (fastutil) + packing `(long)x << 32 | (z & 0xFFFFFFFFL)` ; OU `RoaringBitmap` 2D si besoin range queries | `ChunkPos` = 2×int=16B+header+padding ≈ 24B/entrée. Le Set<ChunkPos> consomme ~72B/entrée (HashMap.Node + box). Long primitif = 8B/entrée + overhead fastutil ~16B/entrée. **~60 % RAM, +2× accès** | `ChunkScanner.java:27` |
| `allAnalyses` (Map<ChunkPos, ChunkAnalysis>) | `ConcurrentHashMap<ChunkPos, ChunkAnalysis>` | `Long2ObjectOpenHashMap<ChunkAnalysis>` | Supprime boxing `ChunkPos`, -30–40 % RAM map, lookup plus rapide (pas d'`equals`) | `ChunkScanner.java:33` |
| `deferredChunks` | `ConcurrentHashMap.newKeySet()` | Idem (`LongOpenHashSet`) | Idem | `ChunkScanner.java:43` |
| `trailChunks`, `interestingChunks` | `Collections.synchronizedList(new ArrayList)` | Si itération fréquente : `CopyOnWriteArrayList` ; sinon garder. Principalement : documenter que chaque iter doit être synchronisée | Évite `ConcurrentModificationException` latents | `ChunkScanner.java:28,30` |
| `STRONG_PLAYER_BLOCKS`, `MEDIUM_PLAYER_BLOCKS`, `TRAIL_BLOCKS`, `STORAGE_BLOCKS`, `WOOL_BLOCKS`, `CONCRETE_BLOCKS`, `VILLAGE_SIGNATURE_BLOCKS`, `TRIAL_CHAMBER_BLOCKS`, `ANCIENT_CITY_BLOCKS`, `END_NATIVE_BLOCKS`, `NETHER_NATIVE_BLOCKS` | `HashSet<Block>` construit via `Arrays.asList(...)` | `ReferenceOpenHashSet<Block>` (identité hash, pas besoin `equals`) | -50 % temps `contains()`. Sur ~8k lookups/chunk × 50 chunks = **400k contains/scan**. **Mesurable.** | `BlockAnalyzer.java:32,107,127,139,147,158,170,202,208,218,230` |
| `HeightmapCache.heightCache` | `ConcurrentHashMap<Long, Integer>` | `Long2IntOpenHashMap` (fastutil) | -40 % RAM (supprime boxing `Integer`), lookup +20 % | `HeightmapCache.java:20` |
| `FlightState[]` dans `PhysicsSimulator.simulateForward` | Nouveau array + N `FlightState.copy()` par appel | **Pool thread-local** de `FlightState[40]` réutilisé, OU adopter SoA : 6 `double[40]` + 2 `float[40]` réutilisables | Économise ~42 allocs × 30 candidats = **~1 260 allocs/tick** ⇒ GC pressure quasi nul | `PhysicsSimulator.java:148–159`, `ElytraBot.java:372,542` |
| `BaseRecord` / `ChunkAnalysis` créations | `new ChunkAnalysis(pos)` + `new SignificantBlock` (up to 50) | Garder (usage court) mais plafonner `significantBlocks` à 20 (actuellement 50) | -40 KB / 1000 chunks | `ChunkAnalysis.java:61`, `BlockAnalyzer.java:450,453,494,524` |
| `ChunkScanner.applyClusterScoring` création `new ChunkPos(pos.x+dx, pos.z+dz)` 8×/analyse | Alloc objet par voisin | Packer en long + lookup dans `Long2ObjectOpenHashMap` → zéro alloc | -400 allocs/scan cycle | `ChunkScanner.java:267` |

---

## 3. Allocations par tick (ElytraBot)

Séquence d'un tick `CRUISING` typique (chemin chaud) :

1. `FlightState.fromPlayer(mc.player)` dans `calculateOptimalPitch` → **1 FlightState**
2. Boucle 21 candidats coarse → 21 × `evaluatePitchCandidate` :
   - `physics.simulateForward(current, pitch, yaw, 40, false)` :
     - `new FlightState[40]` = **1 array**
     - `initial.copy()` = **1 FlightState** (+ inline `new FlightState(...)`)
     - 40 × `current.copy()` dans boucle = **40 FlightState**
   - Sous-total par candidat : **42 FlightState + 1 array**
   - 21 candidats : **882 FlightState + 21 arrays**
3. Boucle refine 9 candidats → idem : **378 FlightState + 9 arrays**
4. `applySafePitch(optimalPitch)` rappelle `simulateForward(..., 20, false)` une fois → **22 FlightState + 1 array**
5. `getMaxTerrainAhead(300)` (indirect via `getEffectiveTargetAltitude`) : si TerrainPredictor absent, 40 appels `getTerrainHeight` (aucune alloc si `level.getHeight` retourne un int). Si présent, zéro alloc sur le chemin chaud.
6. `getDistanceToDestination()` : 0 alloc.
7. `calculateYawToTarget(destination)` : 0 alloc.

**Total CRUISING par tick ≈ 1 283 `FlightState` + 32 arrays = ~1 315 objets alloués / tick.**

À 20 Hz : **≈ 26 300 allocs/s**. Chaque `FlightState` ≈ 72 bytes (8 doubles + 2 floats + header) → **~1.9 MB/s de churn, entièrement short-lived**.

### Impact GC

`[ORDRE DE GRANDEUR]` : G1 young-gen de 64 MB → collection mineure toutes les ~35 s. Chaque GC mineur ~2–5 ms. Probablement invisible, mais sur un profileur ça apparaîtra comme 1er allocateur du plugin.

### Autres allocations hot path ElytraBot

- `updateFlightNoise` : 0 alloc par tick ordinaire (le Random ne crée rien).
- `handleDescending` → `getGroundDistance` : boucle `pos.below(dy)` = **jusqu'à 320 `new BlockPos`/tick** en phase descente (coût O(320) à chaque frame de descente). Moins chaud que cruise mais à pooler.
- `updateChunkLoadingSafety` : pas d'alloc, 3 lookups chunks.
- `estimateGroundHeightAtDestination` → `findGroundBelow` → `for y loop, new BlockPos(x,y,z)` : jusqu'à world-height allocs par appel. Heureusement appelé rarement.

### Comment réduire (ordre priorité)

| Action | Gain allocs/s | Effort |
|---|---|---|
| Pool de `FlightState[40]` thread-local dans `PhysicsSimulator` + variante `simulateForwardInto(out[], ...)` | -26 000/s → <100/s | M |
| Passer `FlightState` en structure primitive (6 doubles + 2 floats stockés dans des `double[]` partagés) | -100 % allocs | L |
| Réduire `PITCH_CANDIDATES` de 21 à 11 + refine 5 | -50 % (coarse) | S |
| Early-exit dans `evaluatePitchCandidate` quand score < bestScore - K (garder sur top-K) | -20–30 % simulations | S |
| Réutiliser `BlockPos.MutableBlockPos` dans `getGroundDistance` / `findGroundBelow` | -640 allocs/tick descente | S |

---

## 4. Persistence à grande échelle

### Format actuel (`StateManager.java:75–88`)

```
int count
for i in count:
    int x  (4B)
    int z  (4B)
```

→ Fichier `scanned_chunks.dat` = `4 + 8*N` bytes. Pour 89 000 chunks ≈ 712 004 B ≈ **696 KB** (cohérent avec 700 KB mesuré).

### Extrapolation linéaire

| Chunks | Taille | Temps save estimé (SSD 500 MB/s, unique write) | Temps load |
|---|---|---|---|
| 100 000 | 0.76 MB | ~5 ms | ~100 ms (HashSet<ChunkPos> alloc+rehash) |
| 1 000 000 | 7.6 MB | ~40 ms | **~1–2 s** (1M allocs `new ChunkPos` + rehash) |
| 10 000 000 | 76 MB | ~300 ms | **~15–20 s** + **~800 MB heap** (72B/entrée HashMap.Node+ChunkPos box) |

**Le format binaire scale en espace, pas en mémoire runtime.** `Set<ChunkPos>` à 1M entrées = ~72 MB heap. À 10M = 720 MB : dépasse le heap typique d'un client MC (souvent 4 GB alloués, 1 GB disponible après MC/RH).

### Problème critique : save sur game thread

`saveScannedChunks` est appelé dans `onUpdate` → **bloque le rendu**. À 1M chunks, 40 ms de stall = 2 frames manquées.

### Alternatives

| Solution | Taille 1M chunks | Alloc RAM 1M | Latence save | Pros/Cons |
|---|---|---|---|---|
| **`RoaringBitmap` 2D (deux dimensions packed)** (dép. `org.roaringbitmap:RoaringBitmap`) | **0.5–2 MB** (compression forte sur régions denses contiguës) | ~2–10 MB | <20 ms | Idéal pour `Set<long>` avec clustering spatial. Parfait pour "chunks scannés dans un rayon". Serialization native. |
| **`LongOpenHashSet` + format actuel** | 7.6 MB | 16–20 MB | ~40 ms | Simple, -80 % RAM vs actuel, serialization ad hoc. **Meilleur ratio effort/gain.** |
| Append-only log + compaction | Croît sans limite, GC périodique | ~72 MB (Set in-RAM) | <1 ms par append (async) | Complexité moyenne, tolère crash. Save non-bloquant. |
| MapDB / Chronicle Map | 8–15 MB on-disk | ~5 MB hors heap | <5 ms | Ajoute dépendance lourde, surkill pour un Set<long>. |
| SQLite (JDBC) | ~15 MB | ~5 MB | <10 ms | Overkill, mais utile si on veut aussi stocker `allAnalyses` persistés. |

### Recommandation pour "scanner 2b2t entier"

**Plan en 2 phases** :

1. **Quick win (effort S)** : remplacer `Set<ChunkPos>` par `LongOpenHashSet` (fastutil) partout. Format binaire inchangé (mais itérer longs directement, sans box). → gain RAM 60 %, temps load/save -50 %. Viable jusqu'à ~2M chunks.
2. **Target (effort M)** : passer à `RoaringBitmap` 2D (packed `long` = (x<<32)|z) avec serialization native. Fichier compressé 10–100× pour régions denses, save async via `Files.newByteChannel` dans un `ExecutorService`. Viable jusqu'à 10M+ chunks avec <50 MB heap.

Déplacer aussi `saveState` et `saveScannedChunks` hors game thread (voir §6).

---

## 5. Top 10 quick wins (triés par ratio impact/effort)

| # | Action | Fichier:ligne | Effort | Impact estimé |
|---|---|---|---|---|
| 1 | **Pooler les `FlightState[]` et `FlightState` réutilisables** (ou SoA 6 doubles + 2 floats dans un buffer unique) dans `simulateForward` | `PhysicsSimulator.java:148` | **M** | -26 000 allocs/s, -1.9 MB/s churn, ~0.3 ms/tick gagné |
| 2 | **Déplacer `saveState` et `saveScannedChunks` sur thread IO dédié** (`ExecutorService.newSingleThreadExecutor`) avec copy-on-write du Set | `StateManager.java:75,115` + `BaseFinderModule.java:612,643` | **S** | Élimine stalls 40–500 ms, critique pour >100k chunks |
| 3 | **Remplacer `Set<ChunkPos>` par `LongOpenHashSet`** (pack (x<<32)|z) ; idem `Map<ChunkPos, ChunkAnalysis>` par `Long2ObjectOpenHashMap` | `ChunkScanner.java:27,33,43` ; `HeightmapCache.java:20` | **S** | -60 % RAM sur `scannedChunks`, -40 % sur `allAnalyses`. À 1M chunks : économise ~60 MB heap |
| 4 | **Ajouter fastutil en dépendance explicite** `compileOnly "it.unimi.dsi:fastutil:8.5.15"` (runtime fourni par MC) | `build.gradle:33` | **S** | Préalable obligatoire à #3 et #7. Sinon pas de garantie API stable. |
| 5 | **Réduire `PITCH_CANDIDATES` de 21 à 13 + refine 5** (grille 5° au lieu de 5°, suffisant pour contrôle elytra) | `ElytraBot.java:61` | **S** | -40 % simulations = -520 allocs/tick, -0.2 ms/tick |
| 6 | **Early-exit dans `calculateOptimalPitch`** : garder un `bestScore` et couper la trajectoire si `partial_score + future_upper_bound < bestScore - margin` | `ElytraBot.java:371–416` | **M** | -20 à -40 % ticks simulés selon scénario |
| 7 | **Passer les sets statiques de `BlockAnalyzer` en `ReferenceOpenHashSet<Block>`** (identité hash, pas `equals`) | `BlockAnalyzer.java:32–245` | **S** | -20 % CPU sur hot loop (8k lookups × 50 chunks/scan = 400k contains) |
| 8 | **Cacher `TerrainPredictor.predictHeight` avec `Long2IntOpenHashMap`** (clé = packed (x>>4, z>>4), TTL none) | nouveau, ou `HeightmapCache.java` déjà fait en partie | **S** | Économise les 40–60 appels `getMaxTerrainAhead` × 20 Hz = 800+ predictions/s |
| 9 | **Réutiliser un `BlockPos.MutableBlockPos`** dans `getGroundDistance`, `findGroundBelow`, et dans `BlockAnalyzer.analyzeChunk` pour les `new BlockPos(minX+x, worldY, minZ+z)` | `ElytraBot.java:1304,1326` ; `BlockAnalyzer.java:450,453,490,494,524` | **S** | -320 allocs/tick descente, -~200 allocs/chunk scanné |
| 10 | **Borner `allAnalyses`** plus agressivement (actuel: 25 000 entrées / 128 chunks radius) : passer à `LinkedHashMap` LRU avec `accessOrder=true` et max 5 000 | `ChunkScanner.java:33,337–372` | **S** | -80 % RAM résiduelle post-scan longue session |

Bonus (non dans top 10 mais facile) :
- `Collections.synchronizedList` + clients qui n'itèrent jamais sous synchronisation → latent bug ; documenter ou passer à `CopyOnWriteArrayList`.
- `StateManager.saveState` utilise `Properties` + `URLEncoder` pour une liste de bases : format texte O(N²) pour parse. À >10 000 bases : passer à CSV ou JSON (Gson déjà dispo via MC).

---

## 6. Plafonds à l'échelle

### Mémoire — où ça casse à 1M chunks ?

| Structure | Par entrée | À 1M chunks | Remarque |
|---|---|---|---|
| `scannedChunks: Set<ChunkPos>` | ~72 B (HashMap.Node + ChunkPos + box) | **72 MB** | Critique |
| `scannedChunks: LongOpenHashSet` | ~12 B | **12 MB** | Avec QW #3 |
| `allAnalyses: Map<ChunkPos, ChunkAnalysis>` | ~500–1000 B (analyse + liste significantBlocks jusqu'à 50 × 40 B) | ~600 MB si rempli | **Heap overflow probable** |
| `allAnalyses` après cleanup (cap 25 000) | — | ~15–25 MB | OK, dépend du cleanup |
| `deferredChunks` (cap 5000 via `ChunkScanner.java:153`) | ~72 B | 360 KB | OK |
| `heightmapCache` (cap 100k via `HeightmapCache.java:22`) | ~48 B (Long+Int+Node) | 4.8 MB | OK |
| `scanned_chunks.dat` en RAM pendant load | 72 B × N (allocation de tous les `ChunkPos`) | **72 MB burst** | Au load, allocation séquentielle rapide ⇒ GC stress |

**Conclusion mémoire** : à 1M chunks, sans les quick wins, le `scannedChunks` Set seul consomme 72 MB. Avec `allAnalyses` non nettoyé : >600 MB possible. **Le code protège avec `MAX_ANALYSES_SIZE=25000` et le cleanup distance (128 chunks) ; tant que le joueur bouge, c'est OK. En vol stationnaire long : risque.**

Avec les quick wins #3 + #10, tenue à **~30 MB pour 1M chunks scannés** (viable).

### CPU — le scanner peut-il suivre à 20 TPS ?

- Cap actuel : `MAX_CHUNKS_PER_TICK = 50` avec `scanInterval=20` (1 scan cycle/s) → **50 chunks/s scannés**.
- Coût par chunk ~1–3 ms (estimé pour `analyzeChunk` + `scanEntities` + `CaveAirAnalyzer`).
- 50 × 3 ms = **150 ms potentiel sur un seul tick** → hitch visible (7 frames à 16 ms).
- MAIS le cap s'applique à un scan cycle (1 fois/s), donc le budget est "150 ms sur un tick toutes les 20 ticks". Le reste des ticks : 0 ms.

**Estimation frame time au scan cycle** : 15–50 ms = **baisse FPS momentanée à ~20-50 FPS** pendant 1/20 des ticks.

Render distance 16 + joueur stationnaire = 1089 chunks dans carré → tous scannés en ~22 scan cycles (22 s).

**Bottleneck réel** : `BlockAnalyzer.analyzeChunk` fait **8192 `section.getBlockState(x,y,z)` par chunk** (passe 1). MC 1.21 gère ça rapidement (PalettedContainer optimisé), mais sur 50 chunks = **409 k lookups/scan cycle**. C'est jouable mais pas gratuit.

**Risque** : sur 2b2t avec TPS serveur qui chute (5 TPS), le client reste à 20 FPS mais les chunks arrivent lentement → `lagDetector.isChunkFullyLoaded` défère beaucoup → retry périodique (`DEFERRED_RETRY_INTERVAL=10`) avec cap `MAX_DEFERRED_RETRIES_PER_TICK=20`. OK.

### IO — la persistence bloque-t-elle le game thread ?

**Oui.** Preuve :

- `StateManager.saveState` (`StateManager.java:115–157`) appelle `props.store(out, ...)` en synchrone. Pour 1000 bases : ~50 ms.
- `StateManager.saveScannedChunks` (`StateManager.java:75–88`) utilise `DataOutputStream` bufferé synchrone. Pour 1M chunks = 40 ms minimum.
- Les deux sont invoqués depuis `onUpdate` (`BaseFinderModule.java:602–643`) dans `@Subscribe onUpdate(EventUpdate event)` → **thread client = game thread**.
- Même pattern sur `loadScannedChunks` au démarrage (1M chunks = 1–2 s blocage).

**Stall attendu** :
- 100 k chunks : save ~8 ms, load ~100 ms (burst alloc)
- 1 M chunks : save ~40 ms (2.4 frames), load ~1–2 s (perçu au plug-in init, moins critique)
- 10 M chunks : save ~300 ms (18 frames), load ~15 s ⇒ **game thread freeze visible**

**Fix requis (QW #2)** : tout l'IO en `ExecutorService.newSingleThreadExecutor`. Passer une snapshot (copie du Set à l'instant du declanchement) au worker.

---

## 7. Instrumentation recommandée

Cinq métriques prioritaires à poser avant toute optimisation sérieuse. On n'a pas Prometheus mais on peut exposer via `LOGGER.info` toutes les N ticks + un registre maison (`Metrics` singleton) qu'on pourra brancher sur un HTTP/UDP exporter plus tard.

| Métrique | Unité | Où injecter (fichier:ligne) | Justification |
|---|---|---|---|
| `scanner.chunk_analysis_time_ms` | histogramme (ms/chunk) | `ChunkScanner.java:167` (entourer `BlockAnalyzer.analyzeChunk`) | Confirme/infirme le 1–3 ms estimé. La vraie métrique #1. |
| `scanner.chunks_scanned_per_cycle` | compteur + gauge | `ChunkScanner.java:238` (déjà loggé, formaliser) | Suivre le débit réel vs `MAX_CHUNKS_PER_TICK` |
| `elytra.simulate_forward_duration_us` | histogramme (µs/appel) | `PhysicsSimulator.java:148` (entourer `simulateForward`) | Chiffrer le coût réel du physics loop. Actuellement théorique. |
| `elytra.optimal_pitch_duration_ms` | histogramme | `ElytraBot.java:335` (entourer `calculateOptimalPitch`) | Coût bout-en-bout, incluant `getTerrainHeight`. |
| `persistence.save_duration_ms` | timer | `StateManager.java:75,115` | Détecter quand save commence à stall (>16 ms = frame drop) |

Bonus utiles (si budget) :
- `scanner.deferred_chunks_size` (gauge) — `ChunkScanner.java:398`
- `allAnalyses_size` (gauge) — `ChunkScanner.java:346`
- `heightmapCache_size` (gauge) — `HeightmapCache.java:146`
- Compteur d'allocations via `ThreadMXBean.getThreadAllocatedBytes(threadId)` au niveau de `ElytraBot.tick()` — donne le `bytes/tick` sans profileur.

### Implémentation minimale suggérée

Une classe `com.basefinder.metrics.Metrics` avec :
```
private final AtomicLong[] buckets = new AtomicLong[32]; // log-scale histogram
public void recordDurationMs(String name, double ms) { ... }
public String dump() { ... } // log périodique toutes les 60 s
```

Hook : dans `BaseFinderModule.onUpdate`, tous les 1200 ticks (60 s), appeler `LOGGER.info(metrics.dump())`. Prépare le terrain pour un `HttpServer` local plus tard.

---

## Annexe : scénario "scan complet 2b2t"

Hypothèse : 1M chunks scannés réalistiquement (spawn radius 500k × highways densités variables).

Avec état actuel :
- RAM steady state : 72 MB (`scannedChunks`) + 25 MB (`allAnalyses` cap) + 5 MB (`heightmapCache`) = **~100 MB**
- RAM pic au load : +72 MB burst (allocation `ChunkPos` N×) = **~170 MB**
- Temps save : 40 ms sur game thread (2.4 frames stall)
- Temps load (cold start) : 1–2 s stall au plugin init
- Allocation elytra : 1.9 MB/s churn pendant vol

Avec quick wins #1, #2, #3, #4, #10 appliqués :
- RAM steady state : ~20 MB
- RAM pic au load : ~30 MB
- Temps save : 0 ms (async)
- Temps load : 500 ms async (toléré)
- Allocation elytra : <10 KB/s

**Verdict** : objectif "scan 2b2t entier" **réalisable** avec les 5 premiers quick wins, à condition de déplacer l'IO hors game thread. Sans ces modifs, plafond pratique ≈ 200–500k chunks avant stutters visibles.
