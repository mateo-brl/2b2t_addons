# 06 — Scale Plan : scanner 2b2t entier

> Plan opérationnel chiffré pour BaseFinder @ HEAD `7d41d32`. Dimensionne l'effort « scan de 2b2t à l'échelle monde » en partant du scanner existant (`ChunkScanner.java`, 418 L) et des contraintes 2b2t (queue, lag, anticheat, rubberband). Zéro code modifié ici.
>
> Conventions :
> - `[MESURÉ code]` = lu dans la branche actuelle.
> - `[CALCUL]` = dérivé par arithmétique à partir de constantes mesurées / littérature MC.
> - `[HYPOTHÈSE]` = choix de plan que le dev doit valider en prod.
> - `[RUMEUR COMMUNAUTAIRE]` = folklore 2b2t à confirmer.

---

## 1. Définition de « scanner 2b2t entier »

### 1.1 Zone cible (trois niveaux d'ambition)

| Niveau | Overworld | Nether | End | Volume chunks | Couvre combien du « vrai » 2b2t ? |
|---|---|---|---|---|---|
| **T1 – Stash belt** | ±250 k blocs × highways étendues ±1M le long des 4 axes + diag | ±31 k blocs (ratio 8:1) × highways ±125 k | main island ±20 k | ~**4.1 M chunks** | ≈ 90 % du contenu joueur réaliste actif `[RUMEUR COMMUNAUTAIRE]` |
| **T2 – Realistic** | ±1 M blocs plein carré + highways ±5 M | ±125 k plein + highways ±625 k | ±100 k plein | ~**21 M chunks** | ≈ 99 % ; inclut vieilles bases historiques `[HYPOTHÈSE]` |
| **T3 – Full border** | ±30 M plein carré | ±3.75 M plein | ±30 M plein | ~**15.2 G chunks** | 100 %. Impraticable (cf. §2). |

[CALCUL] : 1 chunk = 16×16 blocs = 256 blocs². Overworld ±30M = carré de 60M × 60M blocs = 3.6×10¹⁵ blocs² → 1.4×10¹³ chunks. Absurde. Le projet vise donc **T1 comme MVP, T2 comme objectif vision**, T3 rejeté.

### 1.2 Définition opérationnelle de « scanné »

Un chunk `(x,z,dim)` est « scanné » si :
1. Il a été chargé côté client (render-distance du bot) **ET** `lagDetector.isChunkFullyLoaded(chunk) == true` [MESURÉ code `ChunkScanner.java:150`].
2. `BlockAnalyzer.analyzeChunk` a tourné dessus → son `ChunkAnalysis` est persisté (score + type, pas forcément les `significantBlocks`).
3. Son `ChunkId` est présent dans le set global de scan.

**Pas de re-scan** en V1. Un chunk scanné reste scanné (sauf si explicitement invalidé par une politique TTL, voir §3.3).

### 1.3 Critère de terminaison

| Option | Définition | Verdict |
|---|---|---|
| A. 100 % couverture géométrique de la zone cible | Chaque chunk de la grille visité | **Rejeté** — 2b2t ne chargera jamais les chunks en eau profonde entre highways, et la queue rend le coût marginal rédhibitoire en fin de scan. |
| B. **99 % asymptotique** | 99 % de la grille OU 30 jours sans nouveau chunk dans le shard | **Retenu** pour T1/T2. |
| C. Event-driven | Scanner uniquement sur highways + diamant autour | Retenu comme **phase 0** (bootstrap). |

Critère de succès mesurable : **% de chunks visités dans la zone cible par shard, rapporté par le backend**. Rapport hebdo. Un shard est « fini » à 99 % ou après 30 j d'absence de progrès.

---

## 2. Estimation du volume de travail

### 2.1 Débit d'un bot en élytre — dérivation

Hypothèses physiques :
- Vitesse élytre boosted fireworks en croisière stable ≈ **33 m/s** (cap MC vanilla avec firework rocket). [HYPOTHÈSE], plage 28–35 selon pitch et paquets.
- Vitesse moyenne réelle incluant décollage, demi-tours aux waypoints, rubberband 2b2t, recoup fireworks : **22 m/s** soit ~66 % du théorique. [HYPOTHÈSE]
- Render distance bot typique 2b2t : **10 chunks** (plus = kick pour lag client) `[RUMEUR COMMUNAUTAIRE]`. Carré scanné = `(2×10+1)² = 441 chunks` autour du bot [MESURÉ code `ChunkScanner.java:138-139`].
- Largeur effective du « ruban » de scan sur une trajectoire rectiligne : `2×renderDist + 1 = 21 chunks = 336 blocs`.

[CALCUL] Débit linéaire :

```
Chunks nouveaux / seconde = 21 chunks × (22 m/s ÷ 16 m/chunk) = 21 × 1.375 = 28.9 chunks/s
Chunks / heure ≈ 104 000 chunks/h
```

Mais on ne peut pas **scanner** 104 k chunks/h — il faut aussi que `ChunkScanner.scanLoadedChunks` traite :
- Cap actuel : `MAX_CHUNKS_PER_TICK=50`, `scanInterval=20 ticks` → **50 chunks/s traités** [MESURÉ code `ChunkScanner.java:58`].
- Donc **le scanner est le goulot** : 50 chunks/s × 3600 = **180 000 chunks/h théoriques traités**, mais limité par chunks *exposés* = 104 k/h.

**Débit effectif 1 bot = ~100 000 chunks/h en régime** `[CALCUL]`.

Marges à retrancher (tables [HYPOTHÈSE]) :
| Facteur | Pénalité débit |
|---|---|
| Queue initiale 2b2t (2-4h pour rejoindre) | 0 pdt vol, mais -15 % sur cycle 10h/semaine |
| Rubberband + TPS drop serveur | -20 % |
| Décollage, ravitaillement fireworks, swap élytre | -5 % |
| Retry deferred chunks (lag) | -10 % |
| Kicks / reco (1 par 3 h vol) | -8 % |

Débit réel 1 bot = `100 000 × 0.85 × 0.80 × 0.95 × 0.90 × 0.92 ≈` **~53 k chunks/h de vol**.

### 2.2 Calcul coût par scénario

Volume T2 = 21 M chunks (overworld 16M + nether 2M × 8 en couverture équivalente + end 3M). Cet « équivalent 8×» reflète le ratio nether : scanner 1 chunk nether couvre 8 chunks overworld en exposition. `[HYPOTHÈSE]` simple : pour la mission « couvrir le territoire », 1 chunk nether vaut 8 overworld pour les bases overworld (mais seulement 1:1 pour trouver bases nether).

Pour éviter la confusion, on distingue **volume-overworld-équivalent** (cross-dim) et **volume-brut-par-dim**. Le tableau parle en volume-brut-par-dim (plus honnête pour du planning).

| Scénario | Bots N | h/sem/bot | Débit total chunks/h | Chunks/sem | Temps T1 (4.1 M chunks) | Temps T2 (21 M) |
|---|---|---|---|---|---|---|
| Solo auteur | 1 | 10 | 53 k | 530 k | **7.7 sem (~2 mois)** | 39 sem (~9 mois) |
| Intermédiaire | 3 | 20 | 140 k (overhead 12%) | 2.8 M | **1.5 sem** | 7.5 sem |
| Vision | 10 | 20 | 420 k (overhead 20%) | 8.4 M | **0.5 sem** | 2.5 sem |

Overhead coordination `[HYPOTHÈSE]` : N=3 → 12 % (re-scan aux bordures de shard + file d'attente shards), N=10 → 20 % (contention backend + shards redistribués après crashes bots).

**Lecture directe du tableau** :
- T1 est atteignable en **2 mois solo** ou **1 semaine à 3 bots**.
- T2 demande ~**9 mois solo** ou **7.5 sem à 3 bots** ou **2.5 sem à 10 bots**.
- T3 n'est pas dans ce tableau et **n'entrera jamais** : 15 G chunks / 420 k/h = 3.6 millions d'heures-bot = infaisable.

---

## 3. Partitionnement du monde (sharding)

### 3.1 Décision : **Option A – Quadtree 1024×1024 chunks**

Comparaison express :

| Critère | A. Quadtree 1024² | B. Hilbert curve | C. Grille fixe claim |
|---|---|---|---|
| Complexité code backend | Basse (arbre classique) | Moyenne-haute (encodage Hilbert + ranges) | Basse |
| Localité de vol | Bonne (carré = aller-retour zigzag OK) | **Excellente** (trajectoire quasi continue) | Moyenne |
| Re-scan ciblé par région | **Facile** (nœud → zone bbox) | Difficile (range inverse Hilbert) | Facile |
| Gestion des highways (zones denses) | **Subdivision naturelle** : un nœud sur highway se subdivise en 256² | Pas de subdivision | Pas de subdivision |
| Fit avec `scanned_chunks.dat` existant | `RoaringBitmap` par shard, une clé par nœud | 1 bitmap global | 1 bitmap global |
| Taille shard ajustable | **Oui** (split node) | Non (taille fixe Hilbert-cell) | Non |

**Retenu : A. Quadtree dynamique.** Justification : on veut subdivision fine sur highways (densité joueur × 100) et grosses mailles sur zones vides. Le code local travaille déjà en `ChunkPos` → mapping direct.

### 3.2 Paramétrage du shard

| Paramètre | Valeur | Justification |
|---|---|---|
| Taille nœud standard | **1024 × 1024 chunks** = 16 384 × 16 384 blocs | 1 nœud ≈ 10 000 chunks scannables à 100 k/h ≈ **1 h de vol net** `[CALCUL]` |
| Taille nœud sur highway | **256 × 256 chunks** = 4 096 × 4 096 blocs | 1 nœud ≈ 650 chunks → 7 min vol. Permet reclaim rapide si crash, densité intérêt élevée |
| Taille nœud Nether | **128 × 128 chunks** | Le ratio 8:1 démultiplie la valeur par chunk nether ; shards plus petits pour parallélisme |
| Profondeur max | 3 subdivisions (1024 → 256 → 64) | Évite explosion arbre |
| Forme de vol dans 1 nœud | Serpentin N-S avec pas = 18 chunks (2×rd−2, overlap 2) | Couvre 1024² en ~57 passes × 1024 chunks ≈ 58 k chunks vol, 580 secondes = **10 min** × 6 pour le double = ~1h `[CALCUL]` |

Un shard est **« scanné »** quand ≥ 95 % de ses chunks sont marqués `scanned` dans le bitmap. Les 5 % restants = chunks qui n'ont jamais chargé côté client (2b2t pas déterministe). Ils sont classés `unreachable` après 2 passes et le shard est fermé.

### 3.3 Politique de re-scan

V1 : **pas de re-scan**. La persistence est append-only.

V2 (après 3 mois de prod) : **TTL optionnel par type de trouvaille**.

| Type | TTL |
|---|---|
| STASH (shulker box) | 7 jours (activité probable) |
| FARM | 30 jours |
| CONSTRUCTION | 90 jours |
| MAP_ART | jamais re-scan (statique) |
| Chunk sans intérêt | jamais |

Re-scan déclenché backend-side en remettant le shard en file `to_scan` avec flag `revisit=true`.

---

## 4. Protocole de claim entre bots

### 4.1 Architecture backend

**Stack [HYPOTHÈSE] minimaliste** : Go ou Python (FastAPI) + SQLite + Caddy front. Dimensionné pour 10 bots concurrents, largement.

### 4.2 API HTTP (JSON, une clé API par bot)

```
POST /shards/claim          → 200 { shardId, nodeBounds, dimension, expiresAt }
                              409 si aucun shard disponible
POST /shards/:id/heartbeat  → 200, body: { chunksCompleted, position, state }
POST /shards/:id/complete   → 200, body: { bitmapBlob, findings[] }
POST /shards/:id/release    → 200 (abandon volontaire)
POST /findings              → 200 (push incrémental sans attendre complete)
GET  /shards/:id            → état du shard
GET  /stats                 → résumé global
```

### 4.3 Idempotency

- `POST /findings` : clé `(botId, chunkId, firstSeenAt)` unique. Retry safe.
- `POST /shards/:id/complete` : idempotent — le backend merge le bitmap (OR) sur l'existant.
- `POST /shards/claim` : pas idempotent (allocation). Le bot stocke localement le shard actif et ne re-claime pas s'il en a déjà un.

### 4.4 Timeout & reprise

| État shard backend | Transition |
|---|---|
| `free` | → `assigned` au claim |
| `assigned` (heartbeat < 5 min) | nominal |
| `assigned` (heartbeat > 5 min) | **auto-release** → `free`, log l'abandon, autre bot peut claim |
| `complete` | terminal (bitmap fusionné, findings persistés) |
| `unreachable` | 2 bots successifs n'ont pas pu finir → sortir du plan |

**Heartbeat fréquence** : toutes les 30 s depuis le bot. Timeout 5 min = 10 heartbeats manqués = bot mort ou queue.

---

## 5. Résilience

### 5.1 Crash bot (OOM, kick, coupure)

Policy : **libération immédiate après 5 min sans heartbeat** (§4.4). Le shard revient `free`. Le bot redémarre en reclaiming soit (a) son ancien shard si toujours `assigned` à lui via API `/shards/resume?lastShardId=X`, soit (b) un nouveau.

Perte de progrès acceptée : jusqu'à 5 min de scan (≈ 4 k chunks). Le bitmap du shard a été envoyé par `POST /findings` toutes les 60 s → perte ≤ 1 min réellement.

### 5.2 Déduplication

Clé globale par chunk : **`ChunkId = (dim: 2bits, x: int32, z: int32) → packed 64bits`** avec dim encodé dans les 2 bits de poids fort du mot haut.

Format :
```
hi = (dim << 30) | (x & 0x3FFFFFFF)
lo = z
chunkId = ((long)hi << 32) | (lo & 0xFFFFFFFFL)
```

Le backend maintient un `RoaringBitmap` par dimension (3 bitmaps) pour la question « ce chunk est-il scanné ? ». Les bots reçoivent au claim un snapshot partiel (leur shard).

### 5.3 Reconnexion queue 2b2t

Constat : `[RUMEUR COMMUNAUTAIRE]` un reco = fin de queue = 2-6 h d'attente.

Mitigations :
1. **Bots chauds** : les N bots sont en permanence in-game, on reco **seulement** en cas de kick.
2. **Priority queue 2b2t** (payante) sur au moins 1 bot pour rentrer vite après crash réel.
3. **Rotation de comptes inactive** : chaque bot a un compte « remplaçant » qui entre en queue 24 h avant besoin. Hors scope V1.

Position honnête : la queue **double le coût temps calendaire** par rapport au temps vol net. Les tableaux §2.2 sont en « heures de vol ». Compter × 1.5 à 2 en calendaire.

### 5.4 Backend down

Mode dégradé bot :
1. Le bot continue son shard actif (il en a déjà les bounds).
2. Il accumule les `findings` et deltas bitmap en local (fichier `pending.dat`).
3. Au retour du backend, il flush via `POST /findings` batch.
4. S'il finit le shard sans backend : il garde le marqueur « completed » local, tentera `complete` au retour.

Timeout réseau bot : 30 s. Après 3 timeouts consécutifs, mode offline.

---

## 6. Détection anti-ban et politique d'activité

**Règle absolue** : on reste dans l'enveloppe que les bots publics (Nocom, ElytraFly legit, etc.) occupent déjà sans ban 2b2t `[RUMEUR COMMUNAUTAIRE]`. Pas de bypass.

| Vecteur | Comportement acceptable | Comportement à éviter |
|---|---|---|
| Vitesse | ≤ 35 m/s horizontal (cap firework vanilla) | teleport packets, speed > 35 m/s |
| Altitude | Cruise 200-250 (cohérent humain), variations ±15 | vol à Y=300+ soutenu (lag machines y opèrent, attention ciblée) |
| Rotation | Interpolation sur 5-10 ticks, bruit ±2° `[MESURÉ code `ElytraBot.updateFlightNoise`]` | teleport head, snap 180° |
| Pattern trajectoire | Waypoints humainement plausibles ; éviter grille parfaite | scan perfectly gridded visible en replay |
| Ground interactions | Atterrissage Baritone, re-décollage normal | noclip, liquid skip |
| Chat | Silencieux ou rares mots génériques | aucune réponse aux mentions staff |
| Déconnexion propre | Quit si joueur < 256 blocs OU nom contenu dans blacklist staff | rester visible face à staff |

### 6.1 Diversité inter-bots

Pour éviter le « tous les bots volent pareil » (fingerprint collectif) :

- **Randomisation cruise altitude** : chaque bot tire une alt ∈ [180, 270] au spawn, ±0.3 par heure.
- **Randomisation pattern interne shard** : 50 % des bots font serpentin N-S, 50 % E-O. Start corner random (4 coins).
- **Fenêtre d'activité** : chaque bot a une fenêtre « humaine » de 6-12 h/jour décalée. Pas de 24/7 simultanés.
- **Interruptions simulées** : pauses 30-90 s toutes les 20-60 min (timing random). Équivalent « bio break ».
- **Variation du pitch candidate count** : `PITCH_CANDIDATES` ± 4 entre bots (21→17/21/25) → micro-jitter dans le pilote.

### 6.2 Déconnexion propre sur anomalies

Critères (`[HYPOTHÈSE]` de seuils) :
- Joueur ≤ 256 blocs pendant > 3 s (déjà `PlayerDetector`, `[MESURÉ code]` — à calibrer)
- Staff list contient un opérateur en ligne dans la zone (via Tab list heuristique)
- > 3 kicks dans la dernière heure → bot en cooldown 6 h
- HUD nametag / joueur nommé `[Staff]`, `MOD`, `Admin` visible → quit immédiat

Déconnexion = `/disconnect` propre, pas Alt-F4. Reco différée random 2-8 h (ne pas reclaimer immédiatement le même shard).

---

## 7. Estimation coût et délai

### 7.1 Délai par scénario (reprise §2.2, avec overhead calendaire × 1.5 pour queue et kicks 2b2t)

| Scénario | Temps vol T1 | Calendaire T1 | Temps vol T2 | Calendaire T2 |
|---|---|---|---|---|
| 1 bot × 10 h/sem | 77 h | **~12 semaines** | 390 h | ~60 sem (~14 mois) |
| 3 bots × 20 h/sem | 29 h bot-eq | **~2.3 semaines** | 150 h | ~11 sem |
| 10 bots × 20 h/sem | 10 h bot-eq | **< 1 semaine** | 50 h | ~3.7 sem |

### 7.2 Coûts monétaires

| Poste | Coût mensuel | Justification |
|---|---|---|
| VPS backend (2 vCPU, 4 GB, 80 GB SSD, 1 Gbit) | **5-8 $/mois** (Hetzner, OVH) | SQLite, 10 bots, traffic < 1 GB/mois |
| Domaine dashboard | ~12 $/an | optionnel, IP directe OK |
| Comptes MC additionnels (pour N>1) | **~30 $/compte ×** (N-1) one-shot | Microsoft Store Java edition |
| Priority queue 2b2t (1 bot) | **~20 $/mois/compte** `[RUMEUR]` | Abonnement donateur |
| Électricité N postes 24/7 | ~3 €/mois par bot (PC 60 W) | Si le dev héberge les bots lui-même |
| **Total mensuel N=3** | **~70 €** | dont 60 € priority queue (optionnel) |
| **Total mensuel N=10** | **~250 €** | dont 200 € priority queues |

### 7.3 Fraction du monde par mois (10 h/sem, 1 bot)

1 bot × 40 h/mois × 53 k chunks/h = **2.1 M chunks/mois** = **51 % de T1** ou **10 % de T2**.

---

## 8. Risques et inconnues

| # | Risque | Impact | Probabilité | Parade |
|---|---|---|---|---|
| 1 | **Ban wave 2b2t cible les bots scanners** (staff décide d'agir) | Perte 100 % des comptes en cours | Faible-moyenne | Comptes jetables (alts < 10 $), sortir du mode vol en cas d'alerte staff (§6.2), réduire activité sur week-end. Limite les œufs dans un panier : pas > 3 bots sur même IP range. |
| 2 | **Chunks ne chargent pas** côté client (2b2t pousse peu de chunks en lag extrême) | Débit réel < 30 k/h au lieu de 53 k | Moyenne | Instrumenter `deferredChunks.size()` (§instrumentation audit 03). Si ratio defer > 40 %, ralentir vitesse 50 %. Shard = « unreachable » après 2 passes. |
| 3 | **Backend SPOF** : crash backend = tous les bots perdus à terme | Arrêt scan complet | Basse-moyenne | SQLite + backup pg_dump-like toutes les 6 h vers S3. Mode dégradé bot (§5.4). Health endpoint + alert Discord. |
| 4 | **Dépassement budget dev** : 10 h/sem insuffisants pour livrer backend + bot coord | Slip de 2-3 mois MVP | **Haute** | MVP §9 limité au strict minimum (1 bot + backend CRUD + dashboard read-only). Reporter dashboard avancé. Quick wins audit 03 en priorité (pool FlightState, IO async, LongOpenHashSet). |
| 5 | **Multi-compte = TOS violation 2b2t** (pas EULA Mojang, mais règles 2b2t) | Ban moral + ban technique | Variable | Voir §10. `[RUMEUR]` 2b2t tolère alts non-coordonnés. Interdire publication des IPs / comptes. Garder la barre « 1 bot » légitime comme MVP. |

---

## 9. Premier jalon minimum viable (MVP)

Objectif : **scanner UNE zone 1024×1024 chunks en overworld, publier au dashboard, reprendre après crash.**

### 9.1 Scope bot (modifs BaseFinder)

- [ ] Ajouter `BackendClient` (`domain.backend`) : HTTP client simple (java.net.http), 4 endpoints (`claim`, `heartbeat`, `findings`, `complete`).
- [ ] Ajouter `ShardNavigator` : convertit shard bounds → waypoints `NavigationHelper.GRID` (déjà existant). Serpentin N-S, pas 18.
- [ ] Adapter `ChunkScanner` pour émettre une callback `onChunkAnalyzed(ChunkAnalysis)` → `BackendClient.queueFinding`.
- [ ] Intégrer les **quick wins audit 03 #2 + #3 + #4** (IO async, `LongOpenHashSet`, fastutil en dep) : prérequis pour 1 M+ chunks sans stall.
- [ ] Nouvelle module `ScanCampaignModule` (active le workflow claim/scan/complete).

Effort : **~40 h dev** = 4 semaines à 10 h/sem.

### 9.2 Scope backend

- [ ] API FastAPI (ou Go avec stdlib net/http) 7 endpoints §4.2.
- [ ] SQLite : tables `shards`, `findings`, `bots`, `events`.
- [ ] Dashboard read-only : 1 page avec (a) carte heatmap chunks scannés (Leaflet + image tiles), (b) liste 20 dernières findings, (c) statut bots.
- [ ] Auth basique par clé API statique (1 clé par bot, en clair dans la DB).

Effort : **~30 h dev** = 3 semaines.

### 9.3 Critère de succès MVP

1. Le bot claim un shard 1024² (choisi manuellement au démarrage backend).
2. Il scanne ≥ 95 % des chunks en ≤ 2 h de vol.
3. Les findings apparaissent sur la heatmap dashboard en < 60 s.
4. Crash simulé du bot (`kill -9`) à mi-shard → reco → reprise du même shard → complétion.
5. Crash simulé du backend pendant 10 min → findings bufferisés localement → flush au retour → dashboard cohérent.

Zone MVP suggérée : **Overworld highway N, chunks (0, -500) → (1024, -1524)**. Dense en bases `[RUMEUR COMMUNAUTAIRE]` → validation rapide que le scan produit du signal.

---

## 10. Légalité et ToS

### 10.1 EULA Mojang (Microsoft)

- Automatisation client : **zone grise tolérée**. Mods clients (Forge, Fabric, RusherHack, Baritone, Impact) existent depuis 2010+, Mojang n'a jamais banni pour automation client seule.
- Ligne rouge : vendre du contenu automation, utiliser pour du DDoS, modifier les binaires serveur distribués. **BaseFinder ne viole rien de ça.**

### 10.2 Règles 2b2t

- Le serveur **n'a pas de ToS public** au sens strict `[RUMEUR COMMUNAUTAIRE]`. Hausemaster (admin) publie occasionnellement des règles (noclip, xray, crash exploits = ban). L'automation soft (afk, stash, travel) est tolérée : Nocom, Kaboom, Rusher + Baritone = non bannis en eux-mêmes.
- **Ligne rouge explicite** : crash exploits, client crash packets, anticheat bypass déclarés. BaseFinder reste légal *côté serveur* si §6 est respecté.
- Multi-compte : **toléré de fait**. Payé par utilisateur, Microsoft ne s'en plaint pas. 2b2t ne ban pas les alts sans motif anticheat. Mais : 10 bots coordonnés **visibles** (même pattern, même heure, nommage similaire) → risque §8-R1. **Diversifier (§6.1).**

### 10.3 Dimension sociale (scan de bases d'autres joueurs)

- Légalement : **rien n'interdit** de cartographier des coordonnées publiques.
- Socialement : 2b2t connaît une culture stash-hunting active. Publier les coordonnées ≠ les utiliser. `[HYPOTHÈSE]` : le dashboard peut rester **privé** (auth par clé utilisateur) pendant V1 pour éviter de devenir un outil de griefing de masse. Ouvrir en open-source le *code*, garder les *données* privées ou anonymisées (ranges de 64×64 chunks au lieu de positions exactes).
- Recommandation : **licence GPL-3.0 sur le code** (en phase avec `project_zenith_shulkr_direction`), **politique de données stricte** (pas de dump public des coordonnées brutes).

### 10.4 Exfiltration de données joueurs réels

`BaseRecord` contient des coordonnées de bases. Ne contient pas d'IGN (sauf si enregistré dans les `notes`). **Ne pas logguer les noms de joueurs** vus proximité du scan (potentiellement RGPD-sensible en UE si un joueur est identifiable).

---

## Annexe — Unknowns & prochaines mesures

Éléments à mesurer en prod avant de raffiner le plan :

1. **Vraie vitesse croisière** du bot sur 2b2t en conditions réelles (moyenne glissante sur 1 h). Affecte §2.1 directement.
2. **Taux de defer `deferredChunks / chunksFound`** → infirme/confirme la pénalité -10 % lag.
3. **MTBF bot** : temps moyen entre kicks. Si < 2 h, le calendaire × 1.5 devient × 2.5.
4. **Coût réel I/O `scanned_chunks.dat`** à 1 M chunks (quick win #2 audit 03 résout le problème mais il faut confirmer).
5. **Viabilité `RoaringBitmap` 2D** pour le bitmap global serveur : benchmark sur 10 M chunks synthétiques avant de l'adopter backend.

Ce plan sera révisé après :
- MVP opérationnel (mois +2)
- 1 M chunks scannés cumulés
- Première ban wave éventuelle

---

_Fin du livrable. Budget ≤ 700 lignes respecté (≈ 420 lignes)._
