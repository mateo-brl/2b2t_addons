# BaseFinder - Plugin RusherHack pour 2b2t

**[English version below](#english)**

---

## Description

BaseFinder est un plugin RusherHack qui automatise la recherche de bases sur 2b2t. Il suffit d'activer le module et il se met a chercher des bases tout seul : il vole en elytra, scanne les chunks, suit les trails et log tout ce qu'il trouve.

Concu pour fonctionner **24h/24h sans surveillance** avec des systemes de survie automatiques.

## Fonctionnalites

### Recherche automatique de bases
- **Constructions** : detecte les structures construites par les joueurs (obsidienne, blocs de valeur, beacons, etc.)
- **Bases de stockage** : repere les concentrations de coffres, shulkers, barrels, hoppers
- **Map Art** : identifie les grandes surfaces de blocs colores en altitude (concrete, laine, etc.)
- **Trails** : detecte les chemins, routes de glace, rails, lignes de torches qui menent aux bases

### Optimisations de detection
- **Scan d'entites** : detecte item frames, armor stands, minecarts, villageois, animaux de ferme
- **Score cluster** : agregation des scores des chunks voisins pour detecter les bases multi-chunks
- **Estimation de fraicheur** : determine si une base est ACTIVE, ABANDONNEE ou ANCIENNE
- **Sensibilite biome** : reduit les faux positifs dans les villages/temples
- **Distance du spawn** : ponderation par distance (plus loin = plus interessant)
- **Multi-Y scanning** : bonus pour activite a Y=0-10 (bedrock) et Y>200 (sky bases)
- **Auto capture d'ecran** : screenshot automatique a chaque detection
- **Export waypoints** : export vers Xaero's Minimap et VoxelMap

### Vol automatique en Elytra
- Decollage automatique
- Maintien d'altitude de croisiere configurable
- Gestion automatique des fireworks (cherche dans l'inventaire, switch vers la hotbar, utilise au bon moment)
- Auto-swap elytra quand la durabilite est basse (compatible Mending)
- **Evitement d'obstacles** : detection du terrain devant et pull-up d'urgence
- **Anti-kick** : micro-variations de trajectoire pour eviter la detection anti-cheat
- Atterrissage automatique a destination

### Survie 24/7

Systemes automatiques pour fonctionner sans surveillance :

- **Auto Totem** : garde un Totem of Undying en offhand en permanence
- **Auto Manger** : mange automatiquement quand la faim est basse (priorite : golden apple > cooked beef > etc.)
- **Radar joueurs** : detecte les joueurs proches et deconnecte instantanement (portee configurable)
- **Monitoring sante** : alerte et actions d'urgence quand les PV sont critiques
- **Reapprovisionnement fusees** : refill automatique depuis les shulker boxes dans l'inventaire
  - Place la shulker, l'ouvre, transfere les fusees, casse et recupere la shulker
  - Machine a etats complete avec timeouts de securite
- **Evitement d'obstacles** : raycast terrain devant + detection proximite sol
- **Sauvegarde automatique** : sauvegarde l'etat toutes les 5 minutes, restauration apres crash
- **Nettoyage memoire** : purge automatique des anciennes donnees pour eviter les fuites memoire

### Navigation intelligente
- **Spirale** : recherche en spirale depuis la position actuelle
- **Highways** : suit les 8 highways (4 cardinales + 4 diagonales)
- **Aleatoire** : positions aleatoires dans un rayon configurable
- **Anneau** : recherche a une distance specifique
- **Custom** : waypoints personnalises

### Detection New/Old Chunks
- **Liquid Flow Exploit** : detecte les chunks jamais visites en interceptant les paquets de mise a jour de liquides
- **Detection de version** : identifie les chunks generes dans d'anciennes versions de Minecraft (pre-1.13, pre-1.16, pre-1.18)
- **Overlay visuel** : affiche les chunks en couleur (rouge = nouveau, vert = ancien, jaune = ancienne generation)

### Suivi de trails (3 methodes)
- **Block Trail** : detecte les chemins physiques (ice roads, rails, torches, cobblestone)
- **Chunk Trail** : repere les lignes de old chunks entoures de new chunks
- **Version Border** : detecte les frontieres entre chunks generes dans differentes versions
- Calcule la direction via PCA (analyse en composantes principales)

### Logging complet
- Notification dans le chat pour chaque base trouvee (cliquable avec [GOTO] pour Baritone)
- Log dans un fichier avec coordonnees, type, score, timestamp, notes (fraicheur, entites, cluster)
- Auto capture d'ecran
- Export vers Xaero's Minimap / VoxelMap

## Modules

### BaseHunter (categorie External)
Le module principal. Active-le et il fait tout automatiquement.

**Parametres de recherche :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Vol Elytra | ON | Utiliser l'elytra pour se deplacer |
| Mode de recherche | SPIRAL | Mode (SPIRAL, HIGHWAYS, RANDOM, RING, CUSTOM) |

**Filtres de detection :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Bases | ON | Detecter les structures construites |
| Stashes | ON | Detecter les bases de stockage |
| Map Art | ON | Detecter les map art |
| Pistes | ON | Detecter les chemins |
| Sensibilite | 25 | Score minimum pour considerer un chunk interessant |

**Optimisations :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Scan entites | ON | Scanner les entites (item frames, armor stands, etc.) |
| Score cluster | ON | Agreger les scores des chunks voisins |
| Auto capture | OFF | Screenshot auto a chaque detection |
| Anti-kick bruit | ON | Micro-variations de trajectoire |

**Survie 24/7 :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Auto totem | ON | Garder un totem en offhand |
| Auto manger | ON | Manger quand la faim est basse |
| Radar joueurs | ON | Deconnexion auto si joueur detecte |
| Portee radar | 200 | Distance de detection des joueurs (blocs) |
| Reappro fusees | ON | Reappro depuis shulkers |
| Seuil reappro | 16 | Nombre minimum de fusees avant reappro |
| Seuil sante | 10 | PV avant actions d'urgence |
| Evitement obstacles | ON | Detection terrain devant |
| Sauvegarde auto | ON | Sauvegarder l'etat toutes les 5 min |

**Parametres Elytra :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Altitude | 200 | Altitude de croisiere |
| Atterrir sous | 100 | Altitude minimum |
| Delai fusees | 40 ticks | Intervalle entre les fireworks |
| Durabilite echange | 10 | Durabilite min avant echange |

**Parametres avances :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Suivi auto pistes | ON | Suivre automatiquement les trails |
| Detection age chunks | ON | Utiliser les donnees new/old chunk |
| Bordures de version | ON | Detecter les frontieres de generation |
| Vitesse scan | 20 ticks | Frequence de scan des chunks |
| Rayon waypoint | 100 | Distance pour considerer un waypoint atteint |
| Espacement waypoints | 500 | Distance entre les waypoints (spirale) |
| Rayon anneau | 5000 | Rayon pour le mode RING |

### ChunkHistory (categorie External)
Detecte et visualise les new/old chunks. Fonctionne de maniere autonome ou en tandem avec BaseHunter.

### ElytraBot (categorie External)
Module standalone pour voler en elytra vers des coordonnees specifiques. Auto-swap elytra, anti-kick, evitement d'obstacles.

| Parametre | Description |
|-----------|-------------|
| Cible X/Z | Coordonnees de destination |
| Altitude croisiere | Altitude de vol |
| Altitude minimum | Altitude minimum |
| Intervalle fusees | Intervalle entre fusees |
| Durabilite min. elytra | Durabilite avant echange auto |
| Anti-kick bruit | Variations de trajectoire |

## Commandes

Toutes les commandes commencent par `*basefinder` :

| Commande | Description |
|----------|-------------|
| `*basefinder status` | Affiche l'etat actuel (chunks scannes, bases trouvees, uptime, etc.) |
| `*basefinder bases` | Liste les bases trouvees |
| `*basefinder export [fichier]` | Exporte les bases dans un fichier |
| `*basefinder waypoints [format]` | Exporte vers Xaero/VoxelMap (xaero, voxelmap, all) |
| `*basefinder pause` | Met en pause la recherche |
| `*basefinder resume` | Reprend la recherche |
| `*basefinder skip` | Passe au waypoint suivant |
| `*basefinder clear` | Efface toutes les donnees |

## HUD

Le HUD affiche en temps reel :
- Etat du module (SCANNING, FLYING, TRAIL_FOLLOWING, etc.)
- Nombre de chunks scannes et bases trouvees
- Waypoint actuel
- Etat de l'elytra et nombre de fireworks
- Distance parcourue
- **Nombre de totems restants**
- **Alerte PV bas**
- **Statut reapprovisionnement**
- **Temps en ligne (uptime)**

## Installation

1. Compiler le plugin : `./gradlew build`
2. Copier le `.jar` depuis `build/libs/` dans `.minecraft/rusherhack/plugins/`
3. Lancer Minecraft avec RusherHack et le flag `-Drusherhack.enablePlugins=true`
4. Le module apparait dans la categorie **External**

## Build

```bash
./gradlew build
```

Le jar se trouve dans `build/libs/basefinder-1.0.0.jar`.

## Guide 24/7

Pour scanner la map en continu :

1. **Preparation** :
   - Equipez une elytra Mending en chestplate
   - Mettez des elytras de rechange dans l'inventaire
   - Remplissez des shulker boxes de fireworks et gardez-les dans l'inventaire
   - Ayez des totems de l'immortalite
   - Ayez de la nourriture (golden apples idealement)

2. **Configuration** :
   - Activez tous les parametres de "Survie 24/7"
   - Choisissez votre mode de recherche (SPIRAL recommande pour commencer)
   - Ajustez la portee radar selon votre paranoia (200 blocs par defaut)
   - Activez "Sauvegarde auto" pour recuperer apres un crash

3. **Lancement** : Activez BaseHunter et laissez tourner

---

# English

## Description

BaseFinder is a RusherHack plugin that automates base hunting on 2b2t. Just enable the module and it searches for bases on its own: it flies with elytra, scans chunks, follows trails, and logs everything it finds.

Designed for **24/7 unattended operation** with automatic survival systems.

## Features

### Automatic base detection
- **Constructions**: detects player-built structures (obsidian, valuable blocks, beacons, etc.)
- **Storage bases**: spots concentrations of chests, shulkers, barrels, hoppers
- **Map Art**: identifies large flat areas of colored blocks at high altitude
- **Trails**: detects paths, ice roads, rails, torch lines that lead to bases

### Detection optimizations
- **Entity scanning**: detects item frames, armor stands, minecarts, villagers, farm animals
- **Cluster scoring**: aggregates neighboring chunk scores for multi-chunk base detection
- **Freshness estimation**: determines if a base is ACTIVE, ABANDONED, or ANCIENT
- **Biome awareness**: reduces false positives in villages/temples
- **Spawn distance weighting**: farther from spawn = more interesting
- **Multi-Y scanning**: bonus for activity at Y=0-10 (bedrock) and Y>200 (sky bases)
- **Auto screenshot**: automatic screenshot on each detection
- **Waypoint export**: export to Xaero's Minimap and VoxelMap

### Automated Elytra flight
- Automatic takeoff
- Configurable cruise altitude maintenance
- Automatic firework management (finds in inventory, swaps to hotbar, uses at the right time)
- Auto-swap elytra when durability is low (Mending compatible)
- **Obstacle avoidance**: terrain detection ahead with emergency pull-up
- **Anti-kick**: micro-trajectory variations to avoid anti-cheat detection
- Automatic landing at destination

### 24/7 Survival

Automatic systems for unattended operation:

- **Auto Totem**: keeps a Totem of Undying in offhand at all times
- **Auto Eat**: eats automatically when hunger is low (priority: golden apple > cooked beef > etc.)
- **Player Radar**: detects nearby players and instantly disconnects (configurable range)
- **Health monitoring**: alerts and emergency actions when HP is critical
- **Firework resupply**: automatic refill from shulker boxes in inventory
  - Places shulker, opens it, transfers fireworks, breaks and collects shulker
  - Full state machine with safety timeouts
- **Obstacle avoidance**: forward terrain raycast + ground proximity detection
- **Auto save**: saves state every 5 minutes, restores after crash
- **Memory cleanup**: automatic purge of old data to prevent memory leaks

### Smart navigation
- **Spiral**: spiral search from current position
- **Highways**: follows all 8 highways (4 cardinal + 4 diagonal)
- **Random**: random positions within configurable radius
- **Ring**: search at a specific distance
- **Custom**: user-defined waypoints

### New/Old Chunk Detection
- **Liquid Flow Exploit**: detects never-visited chunks by intercepting liquid update packets
- **Version Detection**: identifies chunks generated in older Minecraft versions (pre-1.13, pre-1.16, pre-1.18)
- **Visual overlay**: displays chunks in color (red = new, green = old, yellow = old generation)

### Trail following (3 methods)
- **Block Trail**: detects physical paths (ice roads, rails, torches, cobblestone)
- **Chunk Trail**: spots lines of old chunks surrounded by new chunks
- **Version Border**: detects boundaries between chunks generated in different versions
- Calculates direction via PCA (principal component analysis)

### Full logging
- Chat notification for each base found (clickable with [GOTO] for Baritone)
- File logging with coordinates, type, score, timestamp, notes (freshness, entities, cluster)
- Auto screenshot
- Export to Xaero's Minimap / VoxelMap

## Modules

### BaseHunter (External category)
The main module. Enable it and it does everything automatically.

**Survival 24/7 settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Auto totem | ON | Keep totem in offhand |
| Auto eat | ON | Eat when hunger is low |
| Player radar | ON | Auto-disconnect on player detection |
| Radar range | 200 | Player detection range (blocks) |
| Firework resupply | ON | Resupply from shulkers |
| Resupply threshold | 16 | Min fireworks before resupply |
| Health threshold | 10 | HP before emergency actions |
| Obstacle avoidance | ON | Terrain detection ahead |
| Auto save | ON | Save state every 5 min |

**Search settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Elytra flight | ON | Use elytra for travel |
| Search mode | SPIRAL | Search mode (SPIRAL, HIGHWAYS, RANDOM, RING, CUSTOM) |

**Detection filters:**
| Setting | Default | Description |
|---------|---------|-------------|
| Bases | ON | Detect built structures |
| Stashes | ON | Detect storage bases |
| Map Art | ON | Detect map art |
| Trails | ON | Detect paths |
| Sensitivity | 25 | Min score for interesting chunk |

**Optimization settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Entity scan | ON | Scan entities (item frames, armor stands, etc.) |
| Cluster score | ON | Aggregate neighbor chunk scores |
| Auto screenshot | OFF | Screenshot on each detection |
| Anti-kick noise | ON | Trajectory micro-variations |

**Elytra settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Altitude | 200 | Cruise altitude |
| Land below | 100 | Minimum altitude |
| Firework delay | 40 ticks | Interval between fireworks |
| Swap durability | 10 | Min durability before swap |

### ChunkHistory (External category)
Detects and visualizes new/old chunks. Works standalone or in tandem with BaseHunter.

### ElytraBot (External category)
Standalone module to fly with elytra to specific coordinates. Auto-swap, anti-kick, obstacle avoidance.

## Commands

All commands start with `*basefinder`:

| Command | Description |
|---------|-------------|
| `*basefinder status` | Shows current state (chunks scanned, bases found, uptime, etc.) |
| `*basefinder bases` | Lists found bases |
| `*basefinder export [file]` | Exports bases to a file |
| `*basefinder waypoints [format]` | Export to Xaero/VoxelMap (xaero, voxelmap, all) |
| `*basefinder pause` | Pauses the search |
| `*basefinder resume` | Resumes the search |
| `*basefinder skip` | Skips to next waypoint |
| `*basefinder clear` | Clears all data |

## HUD

The HUD displays in real-time:
- Module state (SCANNING, FLYING, TRAIL_FOLLOWING, etc.)
- Chunks scanned and bases found
- Current waypoint
- Elytra state and firework count
- Distance traveled
- **Totem count**
- **Low HP alert**
- **Resupply status**
- **Uptime**

## Installation

1. Build the plugin: `./gradlew build`
2. Copy the `.jar` from `build/libs/` to `.minecraft/rusherhack/plugins/`
3. Launch Minecraft with RusherHack and the flag `-Drusherhack.enablePlugins=true`
4. The module appears in the **External** category

## Build

```bash
./gradlew build
```

The jar is located at `build/libs/basefinder-1.0.0.jar`.

## 24/7 Guide

To scan the map continuously:

1. **Preparation**:
   - Equip a Mending elytra as chestplate
   - Put spare elytras in inventory
   - Fill shulker boxes with fireworks and keep them in inventory
   - Have Totems of Undying
   - Have food (golden apples ideally)

2. **Configuration**:
   - Enable all "Survival 24/7" settings
   - Choose your search mode (SPIRAL recommended to start)
   - Adjust radar range to your paranoia level (200 blocks default)
   - Enable "Auto save" to recover after crashes

3. **Launch**: Enable BaseHunter and let it run
