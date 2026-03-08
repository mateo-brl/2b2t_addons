<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.4-62B47A?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 1.21.4"/>
  <img src="https://img.shields.io/badge/RusherHack-Plugin-FF4444?style=for-the-badge" alt="RusherHack"/>
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/version-2.0.0-blue?style=for-the-badge" alt="Version 2.0.0"/>
  <img src="https://img.shields.io/github/license/mateo-brl/2b2t_addons?style=for-the-badge" alt="License"/>
</p>

<h1 align="center">BaseFinder</h1>

<p align="center">
  <b>Plugin RusherHack de chasse de bases automatisee pour 2b2t</b><br/>
  Vol elytra autonome · Scan de chunks intelligent · Survie 24/7 · Notifications Discord
</p>

<p align="center">
  <a href="#english">English</a> · <a href="#description">Francais</a>
</p>

---

## Description

BaseFinder est un plugin RusherHack qui automatise entierement la recherche de bases sur 2b2t. Il suffit d'activer le module : il vole en elytra, scanne les chunks, suit les trails et log tout ce qu'il trouve.

Concu pour fonctionner **24h/24 sans surveillance** avec des systemes de survie automatiques.

## Table des matieres

- [Fonctionnalites](#fonctionnalites)
- [Modules](#modules)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commandes](#commandes)
- [Guide 24/7](#guide-247)
- [Architecture](#architecture)
- [English](#english)

---

## Fonctionnalites

### Detection de bases

| Type | Description |
|------|-------------|
| **Constructions** | Structures joueurs (obsidienne, beacons, blocs de valeur...) |
| **Stockage** | Concentrations de coffres, shulkers, barrels, hoppers |
| **Stashs** | Shulkers isoles sans construction autour |
| **Fermes** | Fermes d'animaux, halls de villageois |
| **Portails** | Portails du Nether (obsidienne >= 10) |
| **Map Art** | Grandes surfaces de blocs colores a toute altitude |
| **Trails** | Chemins, routes de glace, rails menant aux bases |
| **Panneaux** | Panneaux avec du texte |

### Optimisations de detection

- **Scan d'entites** — item frames, armor stands, minecarts, villageois, animaux
- **Score cluster** — agregation des scores des chunks voisins pour les bases multi-chunks
- **Estimation de fraicheur** — determine si une base est `ACTIVE`, `ABANDONNEE` ou `ANCIENNE`
- **Sensibilite biome** — reduit les faux positifs (villages, temples, ancient cities)
- **Ponderation distance** — plus loin du spawn = plus interessant
- **Multi-Y scanning** — bonus pour activite a Y=0-10 (bedrock) et Y>200 (sky bases)
- **Shulker garanti** — un shulker est toujours detecte, meme pres du spawn
- **Retry chunks differes** — re-scan automatique des chunks partiellement charges (lag 2b2t)
- **Auto capture d'ecran** — screenshot automatique a chaque detection
- **Export waypoints** — export vers Xaero's Minimap et VoxelMap

### Vol automatique en Elytra

```
IDLE → TAKING_OFF → CRUISING → LANDING
                  ↘ CIRCLING ↗   ↘ EMERGENCY_PULL_UP
```

- Decollage et atterrissage automatiques
- Maintien d'altitude de croisiere configurable avec hard cap
- Gestion automatique des fireworks (inventaire, hotbar, timing)
- Auto-swap elytra quand la durabilite est basse (compatible Mending)
- **Evitement d'obstacles** — detection du terrain 60 blocs devant + pull-up d'urgence
- **Prediction terrain** — utilise la seed 2b2t pour anticiper le relief
- **Simulation physique** — 40 ticks de prevision, 21 angles de pitch evalues par tick
- **Anti-kick** — micro-variations de trajectoire
- Atterrissage securise via Baritone

### Navigation intelligente

| Mode | Description |
|------|-------------|
| **Spirale** | Recherche en spirale depuis la position actuelle |
| **Highways** | Suit les 8 highways (4 cardinales + 4 diagonales) |
| **Quadrillage** | Decoupe en carres configurables, scan systematique |
| **Zone** | Definir X/Z debut et fin, couvre toute la superficie |
| **Aleatoire** | Positions aleatoires dans un rayon configurable |
| **Anneau** | Recherche a une distance specifique |
| **Custom** | Waypoints personnalises |

### Voyage intelligent (AutoTravel)

- **Raccourci Nether** — utilise automatiquement le Nether pour les longs trajets (8x plus rapide)
- **Mode AUTO** — choisit elytra ou marche selon l'equipement et la dimension
- **Mode Marche** — sprint auto, saut auto, nage auto
- **Detection de portails** — scanne les chunks charges pour trouver les portails
- **Transitions de dimension** — detecte automatiquement le changement et continue

### Survie 24/7

| Systeme | Description |
|---------|-------------|
| **Auto Totem** | Garde un Totem of Undying en offhand en permanence |
| **Auto Manger** | Mange automatiquement quand la faim est basse |
| **Radar joueurs** | Detecte les joueurs proches → deconnexion instantanee |
| **Monitoring sante** | Alerte et actions d'urgence quand les PV sont critiques |
| **Auto-deconnexion** | Se deconnecte quand plus d'elytra ou de fusees |
| **Reappro fusees** | Refill automatique depuis les shulker boxes |
| **Sauvegarde auto** | Sauvegarde l'etat toutes les 5 minutes + avant chaque deconnexion |
| **Compensation lag** | Adapte tous les timings au TPS reel de 2b2t |

### Notifications Discord

- Webhook avec embed colore a chaque base trouvee (coords, type, score, shulkers, stockage)
- **Alertes critiques** — perte de vie, sante critique, fusees/elytra basses ou epuisees
- **Alerte deconnexion** — notification quand le bot se deconnecte d'urgence
- Cooldown de 60s par type d'alerte (pas de spam)
- Envoi asynchrone (zero lag en jeu)

### Detection New/Old Chunks

- **Liquid Flow Exploit** — detecte les chunks jamais visites via les ticks de fluides
- **Detection de version** — identifie les chunks generes dans d'anciennes versions de Minecraft
- **Overlay visuel** — rouge = nouveau, vert = ancien, jaune = pre-1.18

### Suivi de trails

| Methode | Description |
|---------|-------------|
| **Block Trail** | Chemins physiques (ice roads, rails, torches, cobblestone) |
| **Chunk Trail** | Lignes de old chunks entoures de new chunks |
| **Version Border** | Frontieres entre chunks de differentes versions MC |

### Persistence de session

- **Chunks scannes** — sauvegardes sur disque (~700KB pour 89K chunks), jamais re-scannes au restart
- **Bases trouvees** — restaurees sans alertes dupliquees
- **Distance parcourue** — persiste entre les sessions
- **Uptime cumule** — persiste entre les sessions
- **Waypoint intelligent** — au demarrage, saute au waypoint le plus proche

---

## Modules

| Module | Categorie | Description |
|--------|-----------|-------------|
| **BaseHunter** | External | Module principal — scan de bases automatise avec vol elytra, analyse de chunks, suivi de trails |
| **ChunkHistory** | External | Detection et visualisation des new/old chunks. Fonctionne seul ou avec BaseHunter |
| **ElytraBot** | External | Vol elytra standalone vers des coordonnees avec auto-swap et evitement d'obstacles |
| **AutoTravel** | External | Voyage intelligent avec raccourci Nether, elytra/marche, detection de portails |

---

## Installation

### Depuis les releases (recommande)

1. Aller dans l'onglet **[Releases](../../releases)**
2. Telecharger `basefinder-2.0.0.jar`
3. Placer le `.jar` dans `.minecraft/rusherhack/plugins/`
4. Ajouter les **arguments JVM requis** (voir ci-dessous)
5. Lancer Minecraft avec RusherHack
6. Les modules apparaissent dans la categorie **External**

> **Prerequis :** RusherHack + Minecraft 1.21.4

### Arguments JVM requis

Les arguments suivants doivent etre ajoutes aux JVM Arguments de votre launcher pour que RusherHack charge les plugins :

```
-XX:+DisableAttachMechanism -DFabricMcEmu=net.minecraft.client.main.Main --add-opens java.base/java.lang=ALL-UNNAMED -Drusherhack.enablePlugins=true
```

**PrismLauncher / MultiMC :** Clic droit sur l'instance > Parametres > Java > Arguments JVM supplementaires > coller les arguments.

**Vanilla Launcher :** Installations > Modifier > Autres options > Arguments JVM > ajouter a la fin.

### Build depuis les sources

```bash
git clone https://github.com/mateo-brl/2b2t_addons.git
cd 2b2t_addons
./gradlew build
```

Le jar se trouve dans `build/libs/basefinder-2.0.0.jar`.

> **Important :** Utilisez le jar de `build/libs/` (remapped intermediary), **pas** celui de `build/devlibs/` (Mojang mappings) qui causera des `NoClassDefFoundError` au chargement.

> Le `.jar` est build automatiquement par GitHub Actions a chaque push.

---

## Configuration

### Fichiers et dossiers

```
.minecraft/rusherhack/basefinder/
├── bases.log                         # Bases trouvees
├── screenshots/                      # Captures auto
└── state/
    ├── session.dat                   # Etat de la session
    ├── scanned_chunks.dat            # Chunks deja scannes (binaire)
    └── discord_webhook.txt           # URL du webhook Discord
```

### Parametres principaux

| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Sensibilite | 30 | Score minimum pour detecter une base (augmenter si trop de faux positifs) |
| Mode de recherche | Spirale | Pattern de navigation |
| Altitude de croisiere | Configurable | Altitude de vol en elytra |
| Scan d'entites | Active | Detection d'entites joueurs |
| Score cluster | Active | Agregation des chunks voisins |
| Sauvegarde auto | Active | Sauvegarde toutes les 5 minutes |

### Configuration Discord

1. Creer un salon prive sur votre serveur Discord
2. Parametres du salon > Integrations > Webhooks > Nouveau webhook
3. Copier l'URL du webhook
4. La coller dans `.minecraft/rusherhack/basefinder/state/discord_webhook.txt`
5. Taper `*basefinder discord` en jeu pour activer

Pour desactiver : vider le fichier et refaire `*basefinder discord`.

---

## Commandes

Toutes les commandes commencent par `*basefinder` :

| Commande | Description |
|----------|-------------|
| `*basefinder status` | Affiche l'etat actuel (chunks scannes, bases trouvees, waypoint) |
| `*basefinder bases` | Liste les bases trouvees |
| `*basefinder export [fichier]` | Exporte les bases dans un fichier |
| `*basefinder waypoints [format]` | Exporte vers Xaero's Minimap ou VoxelMap |
| `*basefinder zone <minX,maxX,minZ,maxZ>` | Definir les bornes de la zone |
| `*basefinder discord` | Active/affiche les notifications Discord |
| `*basefinder pause` | Met en pause la recherche |
| `*basefinder resume` | Reprend la recherche |
| `*basefinder skip` | Passe au waypoint suivant |
| `*basefinder clear` | Efface toutes les donnees |
| `*basefinder resethud` | Reset la position du HUD |

---

## Guide 24/7

### Preparation

- Equipez une elytra Mending en chestplate
- Mettez des elytras de rechange dans l'inventaire
- Remplissez des shulker boxes de fireworks
- Ayez des totems de l'immortalite
- Ayez de la nourriture (golden apples idealement)

### Configuration recommandee

- Activer tous les parametres de survie 24/7
- Sensibilite a `30` (ajuster selon les resultats)
- Activer la sauvegarde auto
- Configurer Discord pour les alertes sur telephone
- Choisir le mode de recherche adapte a la zone cible

### Lancement

Activez **BaseHunter** et laissez tourner. Le module gere tout automatiquement :
vol, scan, detection, log, survie, et sauvegarde.

---

## Architecture

```
com.basefinder/
├── BaseFinderPlugin.java              # Point d'entree du plugin
├── command/
│   └── BaseFinderCommand.java         # Commandes /basefinder
├── modules/
│   ├── BaseFinderModule.java          # Orchestrateur principal (state machine)
│   ├── ElytraBotModule.java           # Module elytra standalone
│   ├── AutoTravelModule.java          # Voyage intelligent Nether/elytra/marche
│   └── NewChunksModule.java           # Visualisation new/old chunks
├── elytra/
│   └── ElytraBot.java                 # Controleur de vol physique (40-tick lookahead)
├── scanner/
│   ├── ChunkScanner.java              # Orchestrateur d'analyse de chunks
│   ├── BlockAnalyzer.java             # Detection de blocs joueurs
│   ├── EntityScanner.java             # Detection d'entites
│   ├── FreshnessEstimator.java        # Classification ACTIVE/ABANDONNEE/ANCIENNE
│   ├── ChunkAgeAnalyzer.java          # Detection de version MC
│   └── NewChunkDetector.java          # Liquid flow exploit
├── terrain/
│   ├── TerrainPredictor.java          # Prediction combinee heightmap + seed
│   ├── HeightmapCache.java            # Cache de terrain observe
│   └── SeedTerrainGenerator.java      # Generation basee sur la seed 2b2t
├── trail/
│   └── TrailFollower.java             # 3 methodes de suivi de trails
├── navigation/
│   └── NavigationHelper.java          # Generation de waypoints (7 patterns)
├── survival/
│   ├── SurvivalManager.java           # Orchestrateur de survie (priorites)
│   ├── AutoTotem.java                 # Totem en offhand
│   ├── AutoEat.java                   # Manger auto
│   ├── PlayerDetector.java            # Radar joueurs + deconnexion
│   └── FireworkResupply.java          # Reappro fusees depuis shulkers
├── logger/
│   ├── BaseLogger.java                # Log fichier + chat + screenshots
│   └── DiscordNotifier.java           # Webhook Discord
├── persistence/
│   └── StateManager.java              # Sauvegarde/restauration de session
├── hud/
│   └── BaseFinderHud.java             # Panel de stats en jeu
└── util/
    ├── BaseRecord.java                # Modele de base trouvee
    ├── BaseType.java                  # Enum des types de bases
    ├── ChunkAnalysis.java             # Resultat d'analyse de chunk
    ├── BlockAnalyzer.java             # Classification de blocs
    ├── PhysicsSimulator.java          # Simulation physique elytra
    ├── BaritoneController.java        # Integration Baritone (reflection)
    ├── LagDetector.java               # Compensation lag 2b2t
    ├── WaypointExporter.java          # Export Xaero/VoxelMap
    ├── Lang.java                      # Gestion bilingue FR/EN
    ├── Vec2d.java                     # Vecteur 2D
    └── MathUtils.java                 # Utilitaires math
```

### Systeme de scoring

```
Score = (blocs forts × 5) + (blocs moyens × 2) + (blocs faibles × 1) + entites + cluster
```

| Categorie | Blocs | Points |
|-----------|-------|--------|
| **Fort** | Shulkers, beacons, ender chests, concrete, glazed terracotta | 5 |
| **Moyen** | Furnaces, anvils, brewing stands, dispensers | 2 |
| **Faible** | Coffres, barrels, hoppers, rails, ice | 1 |

Bonus entites : item frames (+2), armor stands (+1), minecarts (+3), entites nommees (+5), animaux apprivoises (+3).

### Dependances

| Dependance | Type | Utilisation |
|------------|------|-------------|
| RusherHack API | Compile | API du client |
| Minecraft 1.21.4 | Compile | Via Fabric Loom |
| Parchment | Compile | Mappings deobfusques |
| Baritone | Runtime | Navigation au sol (via reflection) |
| Fabric Loader 0.16.9 | Runtime | Chargement du mod |

---

<h1 id="english">English</h1>

<p align="center">
  <b>Automated base hunting RusherHack plugin for 2b2t</b><br/>
  Autonomous elytra flight · Smart chunk scanning · 24/7 survival · Discord alerts
</p>

---

## Description

BaseFinder is a RusherHack plugin that fully automates base hunting on 2b2t. Just enable the module and it searches for bases on its own: it flies with elytra, scans chunks, follows trails, and logs everything it finds.

Designed for **24/7 unattended operation** with automatic survival systems.

## Key Features

- **7 search modes** — Spiral, Highways, Grid, Zone, Random, Ring, Custom
- **Smart detection** — constructions, storage, stashes, farms, portals, map art, trails, signs
- **Cluster scoring** — aggregates neighbor chunks for multi-chunk base detection
- **Freshness estimation** — `ACTIVE`, `ABANDONED`, or `ANCIENT`
- **Discord notifications** — webhook alerts for bases found + critical alerts (health, elytra, fireworks, emergency disconnect)
- **Session persistence** — scanned chunks, bases, distance, uptime survive restarts
- **Smart waypoint start** — skips to nearest waypoint on startup
- **Physics-based flight** — 40-tick lookahead, 21 pitch angles evaluated per tick, zero-damage guarantee
- **Terrain prediction** — uses 2b2t seed for dynamic cruise altitude
- **24/7 survival** — auto totem, auto eat, player radar, firework resupply, health monitoring, auto-disconnect
- **2b2t lag compensation** — adaptive TPS, deferred chunk retry, safety margins
- **AutoTravel** — Nether shortcut (8x faster), portal detection, auto walk/sprint/swim
- **Memory safe** — bounded collections, automatic cleanup for long sessions

## Modules

| Module | Description |
|--------|-------------|
| **BaseHunter** | Main module — automated base scanning with elytra flight, chunk analysis, trail following |
| **ChunkHistory** | New/old chunk detection and visualization overlay |
| **ElytraBot** | Standalone elytra flight to coordinates with auto-swap and obstacle avoidance |
| **AutoTravel** | Smart travel with Nether shortcuts, elytra/walking, portal detection |

## Commands

| Command | Description |
|---------|-------------|
| `*basefinder status` | Show current state |
| `*basefinder bases` | List found bases |
| `*basefinder export [file]` | Export bases to file |
| `*basefinder waypoints [format]` | Export to Xaero/VoxelMap |
| `*basefinder zone <minX,maxX,minZ,maxZ>` | Set zone bounds |
| `*basefinder discord` | Toggle Discord notifications |
| `*basefinder pause` | Pause hunting |
| `*basefinder resume` | Resume hunting |
| `*basefinder skip` | Skip to next waypoint |
| `*basefinder clear` | Clear all data |
| `*basefinder resethud` | Reset HUD position |

### Discord Setup

1. Create a private channel on your Discord server
2. Channel Settings > Integrations > Webhooks > New Webhook
3. Copy the webhook URL
4. Paste it into `.minecraft/rusherhack/basefinder/state/discord_webhook.txt`
5. Type `*basefinder discord` in-game to activate

## Installation

### From releases (recommended)

1. Go to the **[Releases](../../releases)** tab
2. Download `basefinder-2.0.0.jar`
3. Place in `.minecraft/rusherhack/plugins/`
4. Add the **required JVM arguments** (see below)
5. Launch Minecraft with RusherHack
6. Modules appear in the **External** category

> **Required:** RusherHack + Minecraft 1.21.4

### Required JVM Arguments

The following arguments must be added to your launcher's JVM Arguments for RusherHack to load plugins:

```
-XX:+DisableAttachMechanism -DFabricMcEmu=net.minecraft.client.main.Main --add-opens java.base/java.lang=ALL-UNNAMED -Drusherhack.enablePlugins=true
```

**PrismLauncher / MultiMC:** Right-click instance > Settings > Java > Additional JVM Arguments > paste the arguments.

**Vanilla Launcher:** Installations > Edit > More Options > JVM Arguments > append at the end.

### Build from source

```bash
git clone https://github.com/mateo-brl/2b2t_addons.git
cd 2b2t_addons
./gradlew build
```

Output: `build/libs/basefinder-2.0.0.jar`

> **Important:** Use the jar from `build/libs/` (remapped intermediary), **not** the one from `build/devlibs/` (Mojang mappings) which will cause `NoClassDefFoundError` at load time.

## 24/7 Setup Guide

1. **Gear up** — Mending elytra equipped, spare elytras in inventory, shulkers full of fireworks, totems, food (golden apples)
2. **Configure** — Enable all survival settings, choose search mode, set sensitivity to 30, enable auto-save, set up Discord alerts
3. **Launch** — Enable BaseHunter and let it run

---

<p align="center">
  <b>MIT License</b> · mateo-brl
</p>
