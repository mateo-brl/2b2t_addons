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
- **Map Art** : identifie les grandes surfaces de blocs colores a toute altitude (concrete, laine, verre, poudre de concrete)
- **Trails** : detecte les chemins, routes de glace, rails, lignes de torches qui menent aux bases
- **Panneaux** : detecte les panneaux avec du texte (souvent indicateurs de bases)

### Optimisations de detection
- **Scan d'entites** : detecte item frames, armor stands, minecarts, villageois, animaux de ferme
- **Score cluster** : agregation des scores des chunks voisins pour detecter les bases multi-chunks
- **Estimation de fraicheur** : determine si une base est ACTIVE, ABANDONNEE ou ANCIENNE
- **Sensibilite biome** : reduit les faux positifs dans les villages/temples
- **Distance du spawn** : ponderation par distance (plus loin = plus interessant)
- **Multi-Y scanning** : bonus pour activite a Y=0-10 (bedrock) et Y>200 (sky bases)
- **Retry chunks differes** : re-scan automatique des chunks partiellement charges (lag 2b2t)
- **Auto capture d'ecran** : screenshot automatique a chaque detection
- **Export waypoints** : export vers Xaero's Minimap et VoxelMap
- **Score en couleur** : vert (>=100), jaune (>=50), gris (<50) avec details dans les alertes

### Vol automatique en Elytra
- Decollage automatique
- Maintien d'altitude de croisiere configurable avec hard cap (ne depasse jamais)
- Gestion automatique des fireworks (cherche dans l'inventaire, switch vers la hotbar, utilise au bon moment)
- Auto-swap elytra quand la durabilite est basse (compatible Mending)
- **Evitement d'obstacles** : detection du terrain devant et pull-up d'urgence
- **Anti-kick** : micro-variations de trajectoire pour eviter la detection anti-cheat
- Atterrissage automatique a destination

### Voyage intelligent (AutoTravel)

Module de voyage automatique vers n'importe quelles coordonnees :

- **Raccourci Nether** : utilise automatiquement le Nether pour les longs trajets Overworld (8x plus rapide)
- **Mode AUTO** : choisit elytra ou marche selon l'equipement et la dimension
- **Mode Marche** : sprint auto, saut auto par-dessus les obstacles, nage auto (eau/lave)
- **Detection de portails** : scanne les chunks charges pour trouver les portails du Nether
- **Transitions de dimension** : detecte automatiquement le changement et continue le trajet
- **Machine a etats** : FINDING_PORTAL -> ENTERING -> NETHER_TRAVEL -> EXIT -> DIRECT_TRAVEL -> ARRIVED

### Survie 24/7

Systemes automatiques pour fonctionner sans surveillance :

- **Auto Totem** : garde un Totem of Undying en offhand en permanence
- **Auto Manger** : mange automatiquement quand la faim est basse (priorite : golden apple > cooked beef > etc.)
- **Radar joueurs** : detecte les joueurs proches et deconnecte instantanement (portee configurable)
- **Monitoring sante** : alerte et actions d'urgence quand les PV sont critiques
- **Reapprovisionnement fusees** : refill automatique depuis les shulker boxes dans l'inventaire
- **Evitement d'obstacles** : raycast terrain devant + detection proximite sol
- **Sauvegarde automatique** : sauvegarde l'etat toutes les 5 minutes, restauration apres crash
- **Nettoyage memoire** : purge automatique des anciennes donnees pour eviter les fuites memoire
- **Compensation lag 2b2t** : adapte tous les timings au TPS reel du serveur

### Navigation intelligente
- **Spirale** : recherche en spirale depuis la position actuelle
- **Highways** : suit les 8 highways (4 cardinales + 4 diagonales)
- **Quadrillage (GRID)** : decoupe en carres configurables (defaut 5000 blocs) et scanne systematiquement
- **Zone** : definir X debut/fin et Z debut/fin, quadrille toute la superficie (espacement configurable)
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

### Logging complet
- Notification dans le chat pour chaque base trouvee (cliquable avec [GOTO] pour Baritone)
- **Score en couleur** dans les alertes : vert bold (fort), jaune bold (moyen), gris (faible)
- Detail dans les alertes : nombre de shulkers, blocs, stockage, panneaux
- Log dans un fichier avec coordonnees, type, score, timestamp, notes

## Modules

### BaseHunter (categorie External)
Le module principal. Active-le et il fait tout automatiquement.

**Parametres de recherche :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Vol Elytra | ON | Utiliser l'elytra pour se deplacer |
| Mode de recherche | SPIRAL | Mode (SPIRAL, HIGHWAYS, GRID, ZONE, RANDOM, RING, CUSTOM) |
| Sensibilite | 30 | Score minimum pour considerer un chunk interessant |

**Parametres ZONE :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| X debut | 0 | Coordonnee X de debut de zone |
| X fin | 500000 | Coordonnee X de fin de zone |
| Z debut | 0 | Coordonnee Z de debut de zone |
| Z fin | 500000 | Coordonnee Z de fin de zone |
| Espacement zone | 1000 | Distance entre les points de scan |

**Parametres GRID :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Taille carres | 5000 | Taille des carres de quadrillage |
| Rayon total | 100000 | Rayon total du quadrillage |

**Survie 24/7 :**
| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Auto totem | ON | Garder un totem en offhand |
| Auto manger | ON | Manger quand la faim est basse |
| Radar joueurs | ON | Deconnexion auto si joueur detecte |
| Portee radar | 200 | Distance de detection (blocs) |
| Reappro fusees | ON | Reappro depuis shulkers |
| Compensation lag 2b2t | ON | Adapter les timings au TPS |
| Sauvegarde auto | ON | Sauvegarder l'etat toutes les 5 min |

### ChunkHistory (categorie External)
Detecte et visualise les new/old chunks. Fonctionne de maniere autonome ou en tandem avec BaseHunter.

### ElytraBot (categorie External)
Module standalone pour voler en elytra vers des coordonnees specifiques. Auto-swap elytra, anti-kick, evitement d'obstacles.

### AutoTravel (categorie External)
Voyage intelligent vers des coordonnees avec raccourci Nether automatique.

| Parametre | Defaut | Description |
|-----------|--------|-------------|
| Cible X/Z | 0 | Coordonnees de destination |
| Dimension | OVERWORLD | Dimension cible (OVERWORLD, NETHER, END) |
| Mode | AUTO | Mode de deplacement (AUTO, ELYTRA, WALK) |
| Raccourci Nether | ON | Utiliser le Nether pour les longs trajets |
| Seuil Nether | 1000 | Distance min pour activer le raccourci |
| Sprint auto | ON | Sprint automatique en marche |
| Saut auto | ON | Sauter par-dessus les obstacles |

**Comment ca marche :**
1. Definis les coordonnees cible et la dimension
2. Le module calcule le meilleur itineraire (direct ou via Nether)
3. Si le Nether est plus rapide, il cherche un portail, entre, voyage a coordonnees/8, puis ressort
4. En mode AUTO : elytra en Overworld (si equipe), marche dans le Nether (plafond a 128)

## Commandes

Toutes les commandes commencent par `*basefinder` :

| Commande | Description |
|----------|-------------|
| `*basefinder status` | Affiche l'etat actuel |
| `*basefinder bases` | Liste les bases trouvees |
| `*basefinder export [fichier]` | Exporte les bases dans un fichier |
| `*basefinder waypoints [format]` | Exporte vers Xaero/VoxelMap |
| `*basefinder pause` | Met en pause |
| `*basefinder resume` | Reprend |
| `*basefinder skip` | Passe au waypoint suivant |
| `*basefinder clear` | Efface toutes les donnees |

## Installation

### Methode rapide (recommandee)

1. Aller dans l'onglet **[Releases](../../releases)** du repo
2. Telecharger `basefinder-1.1.0.jar` de la derniere release
3. Placer le `.jar` dans `.minecraft/rusherhack/plugins/`
4. Lancer Minecraft avec RusherHack
5. Les modules apparaissent dans la categorie **External**

> **Requis :** RusherHack installe + Minecraft 1.21.4

### Build depuis les sources

```bash
git clone <url-du-repo>
cd 2b2t_addons
./gradlew build
```

Le jar se trouve dans `build/libs/basefinder-1.1.0.jar`.

> Le `.jar` est aussi build automatiquement par GitHub Actions a chaque push. Tu peux le telecharger depuis l'onglet **Actions > Build Plugin > Artifacts**.

## Guide 24/7

1. **Preparation** :
   - Equipez une elytra Mending en chestplate
   - Mettez des elytras de rechange dans l'inventaire
   - Remplissez des shulker boxes de fireworks
   - Ayez des totems de l'immortalite
   - Ayez de la nourriture (golden apples idealement)

2. **Configuration** :
   - Activez tous les parametres de "Survie 24/7"
   - Choisissez votre mode de recherche :
     - **HIGHWAYS** : le plus efficace, suit les 8 autoroutes
     - **ZONE** : definir une zone precise a quadriller
     - **GRID** : quadrillage systematique autour de vous
     - **SPIRAL** : bon pour commencer
   - Sensibilite a 30 par defaut (augmenter si trop de faux positifs)
   - Activez "Sauvegarde auto" pour recuperer apres un crash

3. **Lancement** : Activez BaseHunter et laissez tourner

---

# English

## Description

BaseFinder is a RusherHack plugin that automates base hunting on 2b2t. Just enable the module and it searches for bases on its own: it flies with elytra, scans chunks, follows trails, and logs everything it finds.

Designed for **24/7 unattended operation** with automatic survival systems.

## Modules

| Module | Description |
|--------|-------------|
| **BaseHunter** | Main module - automated base scanning with elytra flight, chunk analysis, trail following |
| **ChunkHistory** | New/old chunk detection and visualization |
| **ElytraBot** | Standalone elytra flight to coordinates with auto-swap and obstacle avoidance |
| **AutoTravel** | Smart travel with Nether shortcuts, elytra/walking, portal detection |

## Key Features

- **7 search modes**: Spiral, Highways, Grid, Zone, Random, Ring, Custom
- **Smart detection**: constructions, storage, map art, trails, signs with text
- **Cluster scoring**: aggregates neighbor chunks for multi-chunk base detection
- **Freshness estimation**: ACTIVE, ABANDONED, or ANCIENT
- **Color-coded alerts**: clickable [GOTO] for Baritone with score coloring
- **24/7 survival**: auto totem, auto eat, player radar, firework resupply, health monitoring
- **2b2t lag compensation**: adaptive TPS, deferred chunk retry, safety altitude
- **AutoTravel**: Nether shortcut (8x faster), portal finding, auto walk/sprint/swim
- **Memory safe**: bounded collections, automatic cleanup for long sessions

## Installation

1. Go to **[Releases](../../releases)** tab
2. Download `basefinder-1.1.0.jar`
3. Place in `.minecraft/rusherhack/plugins/`
4. Launch Minecraft with RusherHack
5. Modules appear in **External** category

> **Required:** RusherHack + Minecraft 1.21.4

## Build from source

```bash
git clone <repo-url>
cd 2b2t_addons
./gradlew build
```

Output: `build/libs/basefinder-1.1.0.jar`
