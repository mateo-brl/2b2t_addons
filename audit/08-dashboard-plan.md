# 08 — Dashboard Plan

> Plan frontend pour le dashboard web BaseFinder. Consomme les events §3 et commandes §4 de `05-target-architecture.md`, dimensionné pour les volumes de `06-scale-plan.md` (≤10 bots, 21M chunks T2, 10k-100k bases). Cible : 1 dev, 10h/semaine, GPL-3.0.

---

## 1. Contraintes et objectifs

- **1 dev** qui fait backend + frontend + bot : stack doit être apprenable en un week-end, zéro magie.
- **Volume** : 10 bots max, 40 Hz events pic, 10k-100k bases, carte 2b2t jusqu'à 21M chunks agrégés en tuiles.
- **Pas de designer** : UI terse, dense, infos > esthétique. Dark mode par défaut (contexte scan = soir).

---

## 2. Stack recommandé

**Recommandation : React 18 + Vite + TanStack Router + TanStack Query + Zustand + Tailwind + shadcn/ui + MapLibre GL JS**

Justification par choix :
- **Vite** : dev server < 300 ms, build esbuild+rollup, zéro config pour TS+React.
- **TanStack Router** : routing file-based type-safe, search params typés (utile pour filtres `?dim=overworld&type=STASH`), pas de SSR à apprendre.
- **TanStack Query** : cache REST + invalidation + `useMutation` optimistic pour commandes bot (Pause/Resume/SetZone). Colle pile au modèle `CommandAck` de §4.
- **Zustand** : 1 store par flux live (liveBots, liveBases, events) ; pas de boilerplate Redux, merge partiel trivial sur chaque event SSE.
- **Tailwind + shadcn/ui** : composants copy-paste dans `components/ui/`, tu possèdes le code, accessibilité gérée (Radix en dessous). Pas de runtime CSS-in-JS.
- **MapLibre GL JS** : fork open-source de Mapbox GL, tuiles raster custom (tuiles 2b2t préprocessées), clustering natif GeoJSON, WebGL → rendu 10k+ markers sans peiner.
- **TypeScript strict** : `strict: true`, `noUncheckedIndexedAccess: true`, `exactOptionalPropertyTypes: true`. Les events bot→backend sont typés via **Zod** (schema runtime + type inféré `z.infer<>`), partagés avec backend si monorepo pnpm.

Alternatives écartées :
- **Next.js** : overkill. SSR inutile (dashboard authentifié, pas de SEO), App Router double le surface d'apprentissage, build plus lourd.
- **Svelte/SvelteKit** : plus léger et élégant, mais écosystème MapLibre + shadcn + Query moins mature → plus de temps de dev à câbler.
- **htmx / Hotwire** : impropre au temps réel à 40 Hz multi-bot, serait un patchwork SSE+swap.
- **Redux Toolkit / Jotai** : Zustand suffit pour un store live, moins de code.
- **Leaflet** : DOM-based, ralentit au-delà de quelques milliers de markers ; MapLibre WebGL passe à l'échelle.
- **Recharts/Victory** (graphes) : OK pour hp/altitude/TPS. **uPlot** en backup si on veut 10k points temps réel à 60 fps.

Langage : **TypeScript strict**.

---

## 3. Architecture front

```
dashboard/
├── index.html
├── vite.config.ts
├── tsconfig.json                    (strict, paths @/ → src/)
├── tailwind.config.ts
├── package.json
├── src/
│   ├── main.tsx                     (router, QueryClientProvider, Toaster)
│   ├── routes/                      (file-based, TanStack Router)
│   │   ├── __root.tsx               (shell : sidebar + main + WS status pill)
│   │   ├── index.tsx                (Fleet Overview — "/")
│   │   ├── bots.$id.tsx             (Bot Detail — "/bots/:id")
│   │   ├── map.tsx                  (Map — "/map")
│   │   ├── zones.tsx                (Zones — "/zones")
│   │   ├── events.tsx               (Events Feed — "/events")
│   │   ├── settings.tsx             (Settings — "/settings")
│   │   └── login.tsx                (Discord OAuth2 callback)
│   │
│   ├── components/
│   │   ├── ui/                      (shadcn : button, card, table, dialog,
│   │   │                             sheet, tabs, toast, select, badge,
│   │   │                             skeleton, tooltip, dropdown-menu)
│   │   └── domain/
│   │       ├── BotCard.tsx          (Fleet grid cell)
│   │       ├── BotStatusDot.tsx     (IDLE/FLYING/SCANNING/DEAD/OFFLINE)
│   │       ├── HealthBar.tsx        (hp + absorption, hunger)
│   │       ├── InventoryGrid.tsx    (9×4 + hotbar, icônes items)
│   │       ├── BaseRow.tsx          (ligne table bases trouvées)
│   │       ├── BaseTypeBadge.tsx    (STASH/FARM/CONSTRUCTION/MAP_ART…)
│   │       ├── ZoneEditor.tsx       (draw polygone MapLibre)
│   │       ├── EventRow.tsx         (ligne feed)
│   │       ├── TpsSparkline.tsx     (uPlot 200px × 30px)
│   │       ├── CoordLabel.tsx       (formatte X/Y/Z, clic→copy)
│   │       └── CommandButton.tsx    (bouton avec état pending via useMutation)
│   │
│   ├── lib/
│   │   ├── api.ts                   (fetch wrapper + zod parse, typé)
│   │   ├── schemas.ts               (zod : BotTick, BaseFound, ChunkScanned…)
│   │   ├── ws.ts                    (SSE client EventSource, reconnect expo)
│   │   ├── map.ts                   (MapLibre setup, projection 2b2t, layers)
│   │   ├── tiles.ts                 (URL builder /tiles/{dim}/{z}/{x}/{y}.png)
│   │   ├── auth.ts                  (currentUser hook, logout)
│   │   └── format.ts                (humanize ts, blocs/m, dim label)
│   │
│   ├── stores/
│   │   ├── liveBots.ts              (Zustand : Map<botId, BotSnapshot>)
│   │   ├── liveBases.ts             (Zustand : Map<chunkId, BaseRecord>,
│   │   │                             + bloom filter optionnel côté v2)
│   │   ├── events.ts                (ring buffer 5000 derniers events)
│   │   ├── zones.ts                 (polygones dessinés, synced avec backend)
│   │   └── wsState.ts               (connecté / reconnecting / down)
│   │
│   └── hooks/
│       ├── useBot.ts                (select(botId) depuis liveBots)
│       ├── useLiveEvents.ts         (filter par type/bot/dim)
│       ├── useZoneDraw.ts           (wrapper maplibre-gl-draw)
│       ├── useCommand.ts            (wrapper useMutation + toast ACK)
│       ├── useKeyboardShortcut.ts   (j/k nav, p=pause, r=resume)
│       └── useVirtualTable.ts       (@tanstack/react-virtual)
│
└── public/
    └── tiles/                       (overworld/{z}/{x}/{y}.png, nether/, end/)
```

**Règles de dépendance** :
- `routes/` peut importer `components/`, `hooks/`, `stores/`, `lib/`.
- `components/domain/` peut importer `components/ui/`, `stores/`, `hooks/`, `lib/`.
- `components/ui/` ne dépend que de lui-même (+ Radix + Tailwind).
- `stores/` et `lib/` n'importent jamais de composant React (sauf hooks hors React pur).
- **Zod schemas partagés** : si le backend est en TypeScript ce serait trivial ; ici Kotlin/Ktor → on duplique manuellement, validés via tests de round-trip JSON sur un fichier fixtures exporté par le backend.

---

## 4. Écrans avec wireframes ASCII

Layout global (toutes les pages) :

```
┌────────────────────────────────────────────────────────────────────────┐
│ BaseFinder  [Fleet] [Bots] [Map] [Zones] [Events] [Settings]  ● WS OK │  header 40px
├────────┬───────────────────────────────────────────────────────────────┤
│ sidebar│                                                               │
│  40px  │                        MAIN AREA                              │
│  icons │                                                               │
│        │                                                               │
└────────┴───────────────────────────────────────────────────────────────┘
```

### 4.1. Fleet Overview (`/`)

**Objectif** : vue d'ensemble des 10 bots en 1 glance. Détecter celui qui est mort, bloqué, ou low HP.

**Data deps** :
- `GET /api/bots` (TanStack Query, staleTime 30s) → liste bots + last session
- SSE stream `BotTick`, `HealthSnapshot`, `InventorySnapshot`, `DimensionChange`, `FlightStateChange`, `EmergencyDisconnect` → Zustand `liveBots`
- Merge : static (API) + live (store) → `BotCard`.

**Interactions** :
- Clic card → `/bots/:id`
- Bouton "Pause all" / "Resume all" en top-right (bulk mutations)
- Hover card : tooltip avec seq, dernière activité, backend RTT

**Wireframe** :
```
┌─ Fleet (10 bots active, 2 offline) ──────── [Pause all] [Resume all] ┐
│                                                                       │
│ ┌─ bot-01 ─ alt-main ───┐  ┌─ bot-02 ─ alt-b ──────┐  ┌─ bot-03 ─ ...┐│
│ │ ● SCANNING  OVERWORLD │  │ ● FLYING    NETHER    │  │ ● IDLE   OW  ││
│ │ 12400, 214, -8320     │  │ 1520, 110, -940       │  │ 0, 64, 0     ││
│ │ ♥ ████████░░  16/20  │  │ ♥ ██████████ 20/20   │  │ ♥ ░░░░░░░░   ││
│ │ 🍖 ████████░  18/20  │  │ 🍖 █████░░░░  9/20   │  │ 🍖 ████████   ││
│ │ elytra 78%  🔥×23     │  │ elytra 12% ⚠ 🔥×4    │  │ elytra 95%   ││
│ │ shards 4  bases 127   │  │ shards 1  bases 43   │  │ (queue…)     ││
│ │ seq 48291 · 0.4s ago  │  │ seq 12044 · 0.2s ago │  │ offline 3m   ││
│ │ [⏸ pause] [↻ resume]  │  │ [⏸]   [🛑 stop]      │  │ [▶ wake]     ││
│ └───────────────────────┘  └──────────────────────┘  └──────────────┘│
│                                                                       │
│ ┌─ bot-04 ──────────────┐  ┌─ bot-05 ──────────────┐ ┌─ bot-06 ─────┐│
│ │ ● FLYING    OW        │  │ ● EMERGENCY_STOPPED   │ │ ● OFFLINE    ││
│ │ ...                   │  │ (click for details)   │ │ last 2h ago  ││
│ │ ...                   │  │ last HP 4, suffocation│ │              ││
│ └───────────────────────┘  └──────────────────────┘  └──────────────┘│
│                                                                       │
│ (grid auto-fill, min-width 280px, flex-wrap)                          │
└───────────────────────────────────────────────────────────────────────┘
```

### 4.2. Bot Detail (`/bots/:id`)

**Objectif** : focus 1 bot. Debug et pilotage ciblé.

**Data deps** :
- `GET /api/bots/:id` (meta, session history)
- `GET /api/bots/:id/history?window=10m` (tick history pour graphes, 1Hz × 600 = 600 points)
- SSE filtré `bot_id=:id` → liveBots store pour le bot courant
- `GET /api/bots/:id/events?limit=200` (events récents) + filtre live sur events store

**Interactions** :
- 3 tabs : **Overview** (graphes + inventaire) / **Events** (timeline) / **Logs** (raw JSON debug)
- Actions barre supérieure : Pause / Resume / Emergency Stop / RequestStateDump / SetScanPattern (dropdown)
- Hotkeys : `p` pause, `r` resume, `e` emergency, `d` dump
- Graphes : HP (dernier 10min), Altitude (10min), Server TPS (observed), Backend RTT

**Wireframe** :
```
┌─ bot-01 · alt-main · session 3h12m · ● SCANNING ──────────────────────┐
│ [⏸ Pause] [↻ Resume] [🛑 Emergency Stop] [⟳ State Dump] [⋯ Pattern ▾] │
├─ Overview ─ Events ─ Logs ─────────────────────────────────────────────┤
│                                                                        │
│ ┌─ Position ────────────────┐  ┌─ Health ─────────── last 10m ──────┐ │
│ │ X: 12,400.82              │  │ 20 ┤                               │ │
│ │ Y:    214.05              │  │ 16 ┤  ╱╲__  ╲__╱╲____/             │ │
│ │ Z:  -8,320.49             │  │ 12 ┤                               │ │
│ │ yaw  134.2°  pitch -5.3°  │  │  8 ┤                               │ │
│ │ OVERWORLD                 │  │  4 ┤                               │ │
│ │ alt AGL 78  server TPS 18 │  │  0 ┴──────────────────────────────▶│ │
│ └───────────────────────────┘  └─────────────────────────────────────┘ │
│                                                                        │
│ ┌─ Altitude ─────── last 10m ─┐  ┌─ Server TPS ─────── last 10m ────┐ │
│ │ 260 ┤      ╱╲                │  │ 20 ┤━━━━╲__  ╱──╲___            │ │
│ │ 220 ┤ ╱╲_╱  ╲__╱             │  │ 15 ┤       ╲╱                   │ │
│ │ 180 ┤                        │  │ 10 ┤                            │ │
│ └──────────────────────────────┘  └────────────────────────────────┘ │
│                                                                        │
│ ┌─ Inventory ────────────────────────────────────────────────────────┐ │
│ │ hotbar: [🪓][🗡][🍞][🔥×23][🔥×8][◇ totem×3][🪙 ender][_][_]      │ │
│ │ main:   [🛡×16][◻ shulker×4][🍞×64][🍺×12][obsidian×48]...         │ │
│ │ armor:  helm(prot4,mend) · chest(ELYTRA dur 78%) · legs · boots    │ │
│ │ offhand: totem                                                     │ │
│ │ fireworks breakdown : flight1×0  flight2×0  flight3×23  flight4×0  │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│ ┌─ Recent events (last 20) ──────────────────────────────────────────┐ │
│ │ 13:42:01  BaseFound     chunk(774,-520,OW) STASH score 82          │ │
│ │ 13:41:58  FlightState   SCANNING → LANDING (low firework)          │ │
│ │ 13:40:12  ChunkScanned  chunk(772,-519,OW) BASE                    │ │
│ │ [view all →]                                                       │ │
│ └────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

### 4.3. Map (`/map`)

**Objectif** : carte 2b2t custom avec bots live + bases + heatmap scan.

**Data deps** :
- Tuiles raster statiques préprocessées (ou un fond simple grille + biomes si pas de tuiles dispo en v1) servies depuis `/tiles/{dim}/{z}/{x}/{y}.png`
- `GET /api/bases?dim=overworld&bbox=x1,z1,x2,z2&min_score=50` (GeoJSON) → source MapLibre clustered
- `GET /api/heatmap/{dim}/{z}/{x}/{y}.png` tuiles agrégées chunks scannés (précalculées backend, voir §6)
- SSE `BotTick` → layer "bot positions" mis à jour live

**Interactions** :
- Toggle dim : Overworld / Nether / End (radio)
- Layers togglables : Bases (filter type + score slider) / Heatmap scan / Bot positions / Zones
- Clic bot → popup mini-card + lien `/bots/:id`
- Clic base → popup (type, score, evidence, ts, bot qui l'a trouvée)
- Search bar coords : `/goto 12400,-8320` → center
- Mesure distance (outil règle)

**Wireframe** :
```
┌─ Map ─────────────────────────────────────────────────────────────────┐
│ Dim: (●) Overworld  ( ) Nether  ( ) End                               │
│ Layers: [✓] Bases  [✓] Heatmap  [✓] Bots  [ ] Zones  [ ] Highways     │
│ Filter bases: type [all ▾]  score ≥ [50]───●──[100]   count: 8,412    │
│ Goto: [  12400, -8320  ] [Go]                                         │
├───────────────────────────────────────────────────────────────────────┤
│                                                                       │
│         ·           ╱  heatmap scan (gradient) ╲                     │
│            ·   ·   ╱                             ╲                   │
│     🛰 bot-04       ⬤  (cluster 128 bases)                            │
│                                                                       │
│                     ▲ STASH score 87                                  │
│         ╭───────╮   ▲ FARM  score 72                                  │
│         │ zone A│                                                     │
│         │   ▦▦  │    🛰 bot-01 (12400, -8320)                         │
│         │       │    ┌───────────────────┐                            │
│         ╰───────╯    │ bot-01  alt 214   │ (popup)                    │
│                      │ SCANNING · OW     │                            │
│                      │ [open detail →]   │                            │
│                      └───────────────────┘                            │
│                                                                       │
│  🛰 bot-07                                                            │
│                                                                       │
│ ─────────────────────────────────────────────────────────  [− | +]   │
│ cursor: X=12418 Z=-8299  biome=plains  chunk(776,-519) SCANNED        │
└───────────────────────────────────────────────────────────────────────┘
```

**Implémentation clé** : tuiles heatmap **précalculées côté backend** (zoom-dependent), pas de 21M markers côté client. Les bases individuelles passent en GeoJSON clustered natif MapLibre (paramètres `cluster: true, clusterMaxZoom: 12, clusterRadius: 50`) → clusters > 100 bases = 1 cercle avec compteur. Test OK jusqu'à 100k points GeoJSON en RAM client.

### 4.4. Zones (`/zones`)

**Objectif** : dessiner polygones de scan, assigner à N bots.

**Data deps** :
- `GET /api/zones` (tous les polygones + meta : priority, dim, assigned bots, shards completed %)
- `POST /api/zones` (créer) / `PATCH` (modifier) / `DELETE`
- `POST /api/zones/:id/assign` { botIds: string[] } → backend recalcule shards et envoie `SetZone` commands

**Draw tool** : **`@mapbox/mapbox-gl-draw`** (MIT, compatible MapLibre via shim `@maplibre/maplibre-gl-draw` ou l'original qui marche déjà sur MapLibre). Modes : polygon, rectangle, delete. Snap 16-block (chunk edge) optionnel.

**Interactions** :
- Split view : liste zones à gauche, carte à droite
- Clic zone dans la liste → center + select sur la carte
- Draw mode : bouton [✏ draw polygon], dessin, Enter pour valider, Echap pour annuler
- Right-click zone → "Assign to bots…" ouvre un `<Dialog>` multi-select

**Wireframe** :
```
┌─ Zones ────────────────────────────────────────────────────────────────┐
│ [✏ Draw polygon] [▭ Draw rect] [🗑 Delete mode]   Dim: [Overworld ▾]   │
├─ Zones list ──────────┬─ Map ──────────────────────────────────────────┤
│                       │                                                │
│ ▣ zone-A  priority 3  │                                                │
│   OW · 2.1 M blocs²   │             ╱──────────╲                       │
│   12 shards · 58%     │            │  zone-A    │                      │
│   bots: 01, 04        │             ╲──────────╱                       │
│                       │                                                │
│ ▣ zone-B  priority 2  │        ┌───────┐  ┌──────┐                     │
│   OW · 0.5 M blocs²   │        │ zoneB │  │zone-C│                     │
│   5  shards · 100% ✓  │        └───────┘  └──────┘                     │
│   bots: (none)        │                                                │
│                       │         draw mode: cliquez pour déposer sommets│
│ ▣ zone-C  priority 1  │                                                │
│   Nether · 80k blocs² │                                                │
│   2 shards · 12%      │                                                │
│   bots: 02            │                                                │
│                       │                                                │
│ [+ New zone]          │                                                │
│                       │                                                │
├───────────────────────┴────────────────────────────────────────────────┤
│ Selected: zone-A       [Assign bots…] [Edit geometry] [Delete] [Export]│
└────────────────────────────────────────────────────────────────────────┘
```

### 4.5. Events Feed (`/events`)

**Objectif** : timeline live tous events, filtre, debug.

**Data deps** :
- SSE `/api/events/stream` sans filtre serveur → filtres côté client (sauf si > 100 evts/s, alors paramétrage serveur `?types=...&bots=...`)
- `GET /api/events?limit=1000&before=ts` pour scroll infini historique
- Ring buffer client 5000 events → virtualisation `@tanstack/react-virtual`

**Interactions** :
- Filtres : par type (checkboxes multi), par bot (select), par dim, par niveau (ALL/WARN/CRIT)
- Search free-text sur payload (client-side regex sur les events en buffer)
- Pause/Resume stream button (fige l'affichage sans couper la collecte)
- Export JSONL des events filtrés

**Wireframe** :
```
┌─ Events Feed ─────────────────────────────── [⏸ Freeze]  [⬇ Export] ──┐
│ Types: [✓]BotTick [✓]BaseFound [✓]ChunkScanned [✓]Health [✓]Flight    │
│        [✓]Dim    [✓]Survival  [ ]Heartbeat  [ ]Inventory              │
│ Bots:  [all ▾]       Dim: [all ▾]       Level: [ALL ▾]                │
│ Search: [                                             ]  4,212 shown  │
├────────────────────────────────────────────────────────────────────────┤
│ 13:42:01.112 [bot-01] BaseFound     chunk(774,-520,OW) STASH  score 82│
│ 13:42:01.084 [bot-04] ChunkScanned  chunk(1902,322,OW) EMPTY          │
│ 13:42:01.041 [bot-01] BotTick       pos=(12400,214,-8320) tps=18.9    │
│ 13:42:00.982 [bot-02] FlightState   CRUISE → DESCENT (low firework)   │
│ 13:42:00.841 [bot-07] SurvivalAlert ⚠ WARN PLAYER_NEARBY @ 180 blocs  │
│ 13:42:00.765 [bot-04] ChunkScanned  chunk(1901,322,OW) WILD           │
│ 13:42:00.612 [bot-01] BotTick       pos=(12399,214,-8321) tps=18.9    │
│ 13:41:59.993 [bot-05] EmergencyDc   reason=SUFFOCATION hp=4           │
│ 13:41:59.812 [bot-03] Handshake     v0.3.1 mc=1.21.4                  │
│ ...                                                                    │
│  (virtualized, 10k rows OK, scroll = load older via /api/events)       │
└────────────────────────────────────────────────────────────────────────┘
```

Clic sur une ligne → `<Sheet>` latéral avec le payload JSON complet (pretty).

### 4.6. Settings (`/settings`) — minimal v1

**Objectif** : config opérateur. V1 = strict minimum.

**Sections** :
- **Bots** : liste API keys par bot, copy button, rotation manuelle (nouveau token → ancien invalide à la prochaine reco).
- **Discord** : webhook URL (test button), channel id, alerts on (BaseFound score ≥ X / EmergencyDc / SurvivalAlert CRIT).
- **Scan policy** : TTL re-scan par type de finding (STASH 7j, FARM 30j, etc.), par dim.
- **User** : Discord username (lu depuis OAuth), logout.

**Wireframe** :
```
┌─ Settings ─────────────────────────────────────────────────────────────┐
│                                                                        │
│ ── Bots ──────────────────────────────────────────────────────────────│
│  bot-01  alt-main    token: bf_****_xY2z  [copy] [rotate] [remove]    │
│  bot-02  alt-b       token: bf_****_9aKp  [copy] [rotate] [remove]    │
│  [+ Register new bot]                                                 │
│                                                                        │
│ ── Discord ───────────────────────────────────────────────────────────│
│  Webhook URL: [https://discord.com/api/webhooks/...         ] [test]  │
│  Alerts:                                                              │
│    [✓] BaseFound with score ≥  [75]                                   │
│    [✓] EmergencyDisconnect                                            │
│    [✓] SurvivalAlert CRIT                                             │
│    [ ] ChunkScanned (spam)                                            │
│                                                                        │
│ ── Scan policy (re-scan TTL) ─────────────────────────────────────────│
│  STASH         [  7 ] days                                            │
│  FARM          [ 30 ] days                                            │
│  CONSTRUCTION  [ 90 ] days                                            │
│  MAP_ART       [ never ]                                              │
│                                                                        │
│ ── User ──────────────────────────────────────────────────────────────│
│  Logged as @matal#1234 (Discord) · admin  [Logout]                    │
│                                                                        │
│                                                                [Save] │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Temps réel

**Recommandation : SSE côté dashboard**.

Pourquoi pas WebSocket :
- Flux est 99 % unidirectionnel backend→dashboard. Les commandes dashboard→bot passent par **REST `POST /api/commands`** (le backend relaie ensuite vers le bot via SSA WebSocket bidirectionnel — c'est §4 de l'archi cible).
- SSE reconnect auto via `EventSource`, support natif HTTP/2 multiplexing, pas de handshake custom, passe proxies et load balancers sans drama.
- Si v2 on a besoin de streams binaires (tuiles live ?) → on upgrade à WS à ce moment-là.

**Endpoint** :
```
GET /api/events/stream?types=BotTick,BaseFound,FlightStateChange&bots=01,02
     Accept: text/event-stream
     → event: BotTick
       data: {"bot_id":"01","seq":48291,"pos":[...],"ts":...}
```

**Côté client** :
```ts
// lib/ws.ts
const es = new EventSource(`/api/events/stream?${qs}`, { withCredentials: true });
es.addEventListener('BotTick', (e) => {
  const parsed = BotTickSchema.parse(JSON.parse(e.data));  // zod
  useLiveBots.getState().applyTick(parsed);
});
es.onerror = () => { /* EventSource retry auto, on toggle wsState=reconnecting */ };
```

**Backpressure / throttle** : si backend push > 30 Hz sur un même bot, le store Zustand coalesce le dernier `BotTick` par bot avant render (via `requestAnimationFrame`). Les events critiques (`BaseFound`, `EmergencyDc`, `SurvivalAlert`) ne sont jamais coalescés.

**Commandes optimistic** :
```ts
const pause = useMutation({
  mutationFn: (botId) => api.post(`/bots/${botId}/commands/pause`),
  onMutate: (botId) => {
    useLiveBots.getState().setPending(botId, 'PAUSING');
  },
  onError: (_, botId) => useLiveBots.getState().clearPending(botId),
  // onSuccess : on attend juste l'event CommandAck qui arrivera via SSE
});
```

Un event `CommandAck(cmd_id, status)` pousse l'état définitif dans le store et résout le toast "pending → OK/ERR".

---

## 6. Performance

- **Liste bases (Map, Events, ZoneDetail)** : virtualisation `@tanstack/react-virtual` dès > 500 lignes. Fixed row height = 32px.
- **Carte heatmap chunks scannés** : **tuiles raster agrégées précalculées côté backend** (§06-scale-plan a 21M chunks). Quadtree-indexed, zoom 0 = monde entier en 1 tile 512×512 (1 pixel = 32×32 chunks). Zoom 10 = 1 pixel = 1 chunk. Backend régénère les tuiles impactées lors d'un `ChunkScanned` event par un worker async (debounce 30s). Jamais de marker par chunk.
- **GeoJSON bases** : clustering natif MapLibre `cluster: true`. Jusqu'à ~100k features OK en RAM.
- **Graphes hp/altitude/TPS** : `uPlot` (pas Recharts) si > 2k points, sinon Recharts OK.
- **WebSocket/SSE throttle** : coalesce `BotTick` par bot à 2 Hz max pour le rendu (le store garde la dernière valeur, mais on ne re-render que 2×/s). `BaseFound` immédiat.
- **Ring buffer events** : 5000 events max en mémoire, puis FIFO. Export → streaming JSONL.
- **Code splitting** : `/map` et `/zones` chargent MapLibre GL en **dynamic import** (`import('maplibre-gl')`) → Fleet et Bot Detail chargent < 200 KB gzip. MapLibre GL pèse ~400 KB gzip.
- **Dark mode par défaut** (Tailwind `dark:`) : toggle en settings mais le dev bosse majoritairement le soir.
- **Bundle target** : < 250 KB gzip pour la home, < 700 KB gzip total avec MapLibre chargé.

---

## 7. Auth

- **Discord OAuth2** (OAuth2 authorization code flow) : bouton "Login with Discord" → redirect Discord → backend échange code → JWT **cookie httpOnly, SameSite=Lax, Secure**.
- Backend expose `GET /api/me` → `{ discordId, username, avatarUrl, role }`. Dashboard lit ça au boot, redirect `/login` si 401.
- **Single-user v1** : admin = 1 Discord user id configuré en env backend. Tout autre login → 403 avec message "Not authorized".
- **CSRF** : toutes les mutations ont un header `X-CSRF-Token` (double-submit cookie pattern).
- **Multi-user v2** : roles `admin` (tout) / `operator` (commandes + edits) / `viewer` (read-only). Plus tard, pas v1.

---

## 8. Déploiement

- **Build** : `vite build` → `dist/` static.
- **Serving v1** : servi par **Ktor directement** via `staticResources("/", "dashboard-dist")`. Une seule unité à déployer, 1 origin → pas de CORS. Idéal pour 1 dev.
- **Serving v2** : si le backend grossit et qu'on veut CDN, **Cloudflare Pages** (gratuit, build automatique sur push GitHub) pour le front, Ktor reste backend API-only. Domaine `dashboard.basefinder.example`.
- **CI** : GitHub Actions : lint (eslint + tsc --noEmit) + test (vitest) + build. Artifact → `dashboard-dist.tar.gz` utilisé par le job de build backend.
- **Versioning** : version injectée via `define: { __VERSION__: JSON.stringify(pkg.version) }` dans `vite.config.ts`, affichée en footer pour debug.

---

## 9. Phase 0 → v1 → v2

### Phase 0 (≈ 30h) — "Je vois mes bots"
Livrables :
- Setup Vite/TS/Tailwind/shadcn, routes `__root`, `/`, `/bots/:id`, `/login`.
- Zod schemas pour `BotHandshake`, `BotTick`, `HealthSnapshot`, `InventorySnapshot`, `EmergencyDisconnect`, `FlightStateChange`, `BaritoneState`, `TelemetryHeartbeat`.
- SSE client + reconnect + `liveBots` store Zustand.
- **Fleet Overview** (§4.1) + **Bot Detail** Overview tab (§4.2) **read-only**.
- Discord OAuth2 login + JWT cookie.
- Ktor serve-static + déploiement VPS.

Pas de carte, pas de zones, pas de commandes, pas d'events feed.

### v1 (≈ 40h) — "Je pilote mes bots et je vois la carte"
- **Map** (§4.3) avec layers Bases + Bots + Heatmap (tuiles précalculées backend minimale).
- **Zones** (§4.4) draw + CRUD + assign.
- Commandes : Pause, Resume, Emergency Stop via `useMutation` optimistic + `CommandAck` via SSE.
- Bot Detail Events tab + filtre events basique.
- **Events Feed** (§4.5) minimal (types filter + pause stream).
- Settings v1 minimal (API keys + Discord webhook).

### v2 (≈ 25h) — "Je scale"
- Bases filtering avancée (score range, date range, bot source, export CSV).
- Events Feed avec scroll infini historique + recherche regex.
- Settings étendu (scan TTL, zones privées, rate limits).
- Multi-user roles + audit log.
- Dark/light toggle + i18n (fr/en).

**Total estimé : 95h dev.** À 10h/sem : ~10 semaines.

---

## 10. Risques front

| # | Risque | Impact | Probabilité | Parade |
|---|---|---|---|---|
| 1 | **SSE trop bavard** (40 Hz × 10 bots × BotTick → 400 evt/s) sature le main thread et rame | Dashboard freeze dès 4-5 bots | Moyenne | Filtrage serveur `?types=` strict par page (Fleet reçoit BotTick coalescé 1 Hz par bot), coalescing client via rAF, Zustand `shallow` compare. Bench à 10 bots simulés avant merge. |
| 2 | **Tuiles heatmap chunks pas prêtes** côté backend → Map inutilisable | v1 dashboard map sans info scan | Haute | v1 fallback : afficher uniquement les bases (GeoJSON, petit volume) sans heatmap. Heatmap peut arriver en v1.5. Carte fonctionnelle quand même. |
| 3 | **MapLibre + draw tool trop complexe** (écosystème gl-draw pas parfait sur MapLibre) | Slip 1-2 semaines sur `/zones` | Moyenne | Plan B : dessiner les zones dans un formulaire (coords AABB tapées à la main + preview), pas de draw graphique. Moche mais livrable. |
| 4 | **Zod schemas divergent** du backend Kotlin | Parse errors runtime, dashboard crash aléatoire | Haute | Backend expose `GET /api/schema/events.json` (JSON Schema), script dev `pnpm schema:check` vérifie chaque schéma zod ≡ JSON Schema backend. Fail CI si divergence. |
| 5 | **Single-dev burnout sur front** car moins fun que le bot | Plan 95h dérape à 200h | Haute | Cap strict Phase 0 à 30h : si > 30h, tailler dans Bot Detail graphes (retirer uPlot, garder juste les nombres). Dashboard moche mais fonctionnel bat dashboard joli mais inexistant. |

---

_Fin du livrable. ≈ 530 lignes, budget ≤ 700 respecté._
