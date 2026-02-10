# BaseFinder - Plugin RusherHack pour 2b2t

**[English version below](#english)**

---

## Description

BaseFinder est un plugin RusherHack qui automatise la recherche de bases sur 2b2t. Il suffit d'activer le module et il se met a chercher des bases tout seul : il vole en elytra, scanne les chunks, suit les trails et log tout ce qu'il trouve.

## Fonctionnalites

### Recherche automatique de bases
- **Constructions** : detecte les structures construites par les joueurs (obsidienne, blocs de valeur, beacons, etc.)
- **Bases de stockage** : repere les concentrations de coffres, shulkers, barrels, hoppers
- **Map Art** : identifie les grandes surfaces de blocs colores en altitude (concrete, laine, etc.)
- **Trails** : detecte les chemins, routes de glace, rails, lignes de torches qui menent aux bases

### Vol automatique en Elytra
- Decollage automatique
- Maintien d'altitude de croisiere configurable
- Gestion automatique des fireworks (cherche dans l'inventaire, switch vers la hotbar, utilise au bon moment)
- Atterrissage automatique a destination

### Navigation intelligente
- **Spirale** : recherche en spirale depuis la position actuelle
- **Highways** : suit les 8 highways (4 cardinales + 4 diagonales)
- **Aleatoire** : positions aleatoires dans un rayon configurable
- **Anneau** : recherche a une distance specifique
- **Custom** : waypoints personnalises

### Detection New/Old Chunks
- **Liquid Flow Exploit** : detecte les chunks jamais visites en interceptant les paquets de mise a jour de liquides. Quand un chunk est genere pour la premiere fois, les liquides n'ont pas encore coule - le serveur envoie des paquets de flow quand le chunk est charge.
- **Detection de version** : identifie les chunks generes dans d'anciennes versions de Minecraft (pre-1.13, pre-1.16, pre-1.18) en analysant les blocs presents (deepslate a Y=0-4 = ancien bedrock, absence de copper ore, etc.)
- **Overlay visuel** : affiche les chunks en couleur (rouge = nouveau, vert = ancien, jaune = ancienne generation)

### Suivi de trails (3 methodes)
- **Block Trail** : detecte les chemins physiques (ice roads, rails, torches, cobblestone)
- **Chunk Trail** : repere les lignes de old chunks entoures de new chunks (= quelqu'un est passe par la)
- **Version Border** : detecte les frontieres entre chunks generes dans differentes versions (= limite d'exploration d'un joueur)
- Calcule la direction via PCA (analyse en composantes principales)
- Continue a scanner pendant le suivi
- Reprend la recherche si le trail est perdu

### Logging complet
- Notification dans le chat pour chaque base trouvee
- Log dans un fichier avec coordonnees, type, score, timestamp
- Commande d'export pour sauvegarder les resultats

## Modules

### BaseFinder (categorie World)
Le module principal. Active-le et il fait tout automatiquement.

**Parametres de recherche :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Pattern | SPIRAL | Mode de recherche (SPIRAL, HIGHWAYS, RANDOM, RING, CUSTOM) |
| Scan Interval | 20 ticks | Frequence de scan des chunks |
| Min Score | 20 | Score minimum pour considerer un chunk comme interessant |
| Waypoint Radius | 100 | Distance pour considerer un waypoint comme atteint |

**Filtres de detection :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Constructions | ON | Detecter les structures construites |
| Storage Bases | ON | Detecter les bases de stockage |
| Map Art | ON | Detecter les map art |
| Trails | ON | Detecter les chemins |
| Follow Trails | ON | Suivre automatiquement les trails |
| Chunk Trails | ON | Utiliser les donnees new/old chunk pour detecter les trails |
| Version Borders | ON | Detecter les frontieres de generation entre versions MC |

**Parametres Elytra :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Use Elytra | ON | Utiliser l'elytra pour se deplacer |
| Cruise Altitude | 200 | Altitude de croisiere |
| Min Altitude | 100 | Altitude minimum avant atterrissage |
| Firework Interval | 40 ticks | Intervalle entre les fireworks |

### NewChunks (categorie Render)
Detecte et visualise les new/old chunks. Fonctionne de maniere autonome ou en tandem avec BaseFinder.

**Methodes de detection :**
- Liquid Flow Exploit (intercepte les paquets de mise a jour de liquides)
- Version Detection (analyse les blocs specifiques a chaque version MC)

**Parametres rendu :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Show New | ON | Afficher les nouveaux chunks (rouge) |
| Show Old | ON | Afficher les anciens chunks (vert) |
| Show Version Borders | ON | Afficher les frontieres de version (jaune) |
| Render Y | 64 | Altitude de l'overlay |
| Render Distance | 16 | Distance de rendu en chunks |

**Parametres detection :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Liquid Detection | ON | Detection par flow de liquides |
| Version Detection | ON | Detection par blocs specifiques a chaque version |
| Classification Delay | 40 ticks | Delai avant de classifier un chunk comme "old" |

### ElytraBot (categorie Movement)
Module standalone pour voler en elytra vers des coordonnees specifiques. Utile independamment de BaseFinder.

| Parametre | Description |
|-----------|-------------|
| Target X/Z | Coordonnees de destination |
| Cruise Altitude | Altitude de vol |
| Min Altitude | Altitude minimum |
| Firework Interval | Intervalle entre fusees |

## Commandes

Toutes les commandes commencent par `*basefinder` :

| Commande | Description |
|----------|-------------|
| `*basefinder status` | Affiche l'etat actuel (chunks scannes, bases trouvees, etc.) |
| `*basefinder bases` | Liste les bases trouvees |
| `*basefinder export [fichier]` | Exporte les bases dans un fichier |
| `*basefinder pause` | Met en pause la recherche |
| `*basefinder resume` | Reprend la recherche |
| `*basefinder skip` | Passe au waypoint suivant |
| `*basefinder clear` | Efface toutes les donnees |

## HUD

Le HUD affiche en temps reel :
- Etat du module (SCANNING, FLYING, TRAIL_FOLLOWING, etc.)
- Nombre de chunks scannes
- Nombre de bases trouvees
- Waypoint actuel
- Etat de l'elytra et nombre de fireworks
- Distance parcourue

## Installation

1. Compiler le plugin : `./gradlew build`
2. Copier le `.jar` depuis `build/libs/` dans `.minecraft/rusherhack/plugins/`
3. Lancer Minecraft avec RusherHack et le flag `-Drusherhack.enablePlugins=true`
4. Le module apparait dans la categorie **World** (BaseFinder) et **Movement** (ElytraBot)

## Build

```bash
./gradlew build
```

Le jar se trouve dans `build/libs/basefinder-1.0.0.jar`.

---

# English

## Description

BaseFinder is a RusherHack plugin that automates base hunting on 2b2t. Just enable the module and it searches for bases on its own: it flies with elytra, scans chunks, follows trails, and logs everything it finds.

## Features

### Automatic base detection
- **Constructions**: detects player-built structures (obsidian, valuable blocks, beacons, etc.)
- **Storage bases**: spots concentrations of chests, shulkers, barrels, hoppers
- **Map Art**: identifies large flat areas of colored blocks at high altitude (concrete, wool, etc.)
- **Trails**: detects paths, ice roads, rails, torch lines that lead to bases

### Automated Elytra flight
- Automatic takeoff
- Configurable cruise altitude maintenance
- Automatic firework management (finds in inventory, swaps to hotbar, uses at the right time)
- Automatic landing at destination

### Smart navigation
- **Spiral**: spiral search from current position
- **Highways**: follows all 8 highways (4 cardinal + 4 diagonal)
- **Random**: random positions within configurable radius
- **Ring**: search at a specific distance
- **Custom**: user-defined waypoints

### New/Old Chunk Detection
- **Liquid Flow Exploit**: detects never-visited chunks by intercepting liquid update packets. When a chunk is first generated, liquids haven't flowed yet - the server sends flow packets when the chunk is loaded.
- **Version Detection**: identifies chunks generated in older Minecraft versions (pre-1.13, pre-1.16, pre-1.18) by analyzing blocks present (deepslate at Y=0-4 = old bedrock, missing copper ore, etc.)
- **Visual overlay**: displays chunks in color (red = new, green = old, yellow = old generation)

### Trail following (3 methods)
- **Block Trail**: detects physical paths (ice roads, rails, torches, cobblestone)
- **Chunk Trail**: spots lines of old chunks surrounded by new chunks (= someone traveled through here)
- **Version Border**: detects boundaries between chunks generated in different versions (= player exploration limit)
- Calculates direction via PCA (principal component analysis)
- Continues scanning while following
- Resumes search if trail is lost

### Full logging
- Chat notification for each base found
- File logging with coordinates, type, score, timestamp
- Export command to save results

## Modules

### BaseFinder (World category)
The main module. Enable it and it does everything automatically.

**Search settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Pattern | SPIRAL | Search mode (SPIRAL, HIGHWAYS, RANDOM, RING, CUSTOM) |
| Scan Interval | 20 ticks | Chunk scanning frequency |
| Min Score | 20 | Minimum score to consider a chunk interesting |
| Waypoint Radius | 100 | Distance to consider a waypoint reached |

**Detection filters:**
| Setting | Default | Description |
|---------|---------|-------------|
| Constructions | ON | Detect built structures |
| Storage Bases | ON | Detect storage bases |
| Map Art | ON | Detect map art |
| Trails | ON | Detect paths |
| Follow Trails | ON | Automatically follow trails |
| Chunk Trails | ON | Use new/old chunk data for trail detection |
| Version Borders | ON | Detect generation borders between MC versions |

**Elytra settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Use Elytra | ON | Use elytra for travel |
| Cruise Altitude | 200 | Cruise altitude |
| Min Altitude | 100 | Minimum altitude before landing |
| Firework Interval | 40 ticks | Interval between fireworks |

### NewChunks (Render category)
Detects and visualizes new/old chunks. Works standalone or in tandem with BaseFinder.

**Detection methods:**
- Liquid Flow Exploit (intercepts liquid update packets)
- Version Detection (analyzes version-specific blocks)

**Render settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Show New | ON | Show new chunks (red) |
| Show Old | ON | Show old chunks (green) |
| Show Version Borders | ON | Show version borders (yellow) |
| Render Y | 64 | Overlay altitude |
| Render Distance | 16 | Render distance in chunks |

**Detection settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Liquid Detection | ON | Detection via liquid flow |
| Version Detection | ON | Detection via version-specific blocks |
| Classification Delay | 40 ticks | Delay before classifying a chunk as "old" |

### ElytraBot (Movement category)
Standalone module to fly with elytra to specific coordinates. Useful independently from BaseFinder.

| Setting | Description |
|---------|-------------|
| Target X/Z | Destination coordinates |
| Cruise Altitude | Flight altitude |
| Min Altitude | Minimum altitude |
| Firework Interval | Interval between rockets |

## Commands

All commands start with `*basefinder`:

| Command | Description |
|---------|-------------|
| `*basefinder status` | Shows current state (chunks scanned, bases found, etc.) |
| `*basefinder bases` | Lists found bases |
| `*basefinder export [file]` | Exports bases to a file |
| `*basefinder pause` | Pauses the search |
| `*basefinder resume` | Resumes the search |
| `*basefinder skip` | Skips to next waypoint |
| `*basefinder clear` | Clears all data |

## HUD

The HUD displays in real-time:
- Module state (SCANNING, FLYING, TRAIL_FOLLOWING, etc.)
- Number of chunks scanned
- Number of bases found
- Current waypoint
- Elytra state and firework count
- Total distance traveled

## Installation

1. Build the plugin: `./gradlew build`
2. Copy the `.jar` from `build/libs/` to `.minecraft/rusherhack/plugins/`
3. Launch Minecraft with RusherHack and the flag `-Drusherhack.enablePlugins=true`
4. The module appears in the **World** category (BaseFinder) and **Movement** category (ElytraBot)

## Build

```bash
./gradlew build
```

The jar is located at `build/libs/basefinder-1.0.0.jar`.
