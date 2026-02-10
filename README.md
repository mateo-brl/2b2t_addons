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

### Suivi de trails
- Detecte automatiquement les chemins (ice roads, rails, torches, cobblestone)
- Calcule la direction du trail et le suit
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

**Parametres Elytra :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Use Elytra | ON | Utiliser l'elytra pour se deplacer |
| Cruise Altitude | 200 | Altitude de croisiere |
| Min Altitude | 100 | Altitude minimum avant atterrissage |
| Firework Interval | 40 ticks | Intervalle entre les fireworks |

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

### Trail following
- Automatically detects paths (ice roads, rails, torches, cobblestone)
- Calculates trail direction and follows it
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

**Elytra settings:**
| Setting | Default | Description |
|---------|---------|-------------|
| Use Elytra | ON | Use elytra for travel |
| Cruise Altitude | 200 | Cruise altitude |
| Min Altitude | 100 | Minimum altitude before landing |
| Firework Interval | 40 ticks | Interval between fireworks |

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
