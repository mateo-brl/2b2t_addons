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
- **Stashs** : detecte les shulkers isoles sans construction autour
- **Fermes** : detecte les fermes d'animaux, halls de villageois
- **Portails** : detecte les portails du Nether (obsidienne >= 10)
- **Map Art** : identifie les grandes surfaces de blocs colores a toute altitude
- **Trails** : detecte les chemins, routes de glace, rails qui menent aux bases
- **Panneaux** : detecte les panneaux avec du texte

### Optimisations de detection
- **Scan d'entites** : detecte item frames, armor stands, minecarts, villageois, animaux
- **Score cluster** : agregation des scores des chunks voisins pour les bases multi-chunks
- **Estimation de fraicheur** : determine si une base est ACTIVE, ABANDONNEE ou ANCIENNE
- **Sensibilite biome** : reduit les faux positifs dans les villages/temples/ancient cities
- **Distance du spawn** : ponderation par distance (plus loin = plus interessant)
- **Multi-Y scanning** : bonus pour activite a Y=0-10 (bedrock) et Y>200 (sky bases)
- **Shulker garanti** : un shulker est toujours detecte, meme pres du spawn
- **Retry chunks differes** : re-scan automatique des chunks partiellement charges (lag 2b2t)
- **Auto capture d'ecran** : screenshot automatique a chaque detection
- **Export waypoints** : export vers Xaero's Minimap et VoxelMap

### Notifications Discord
- **Webhook Discord** : recevez une notification avec embed colore a chaque base trouvee
- Coords, type, score, shulkers, stockage, notes dans chaque notification
- Envoi asynchrone (pas de lag en jeu)
- Configuration : `*basefinder discord` (voir section Commandes)

### Persistence de session
- **Chunks scannes** : sauvegardes sur disque (~700KB pour 89K chunks), jamais re-scannes au restart
- **Bases trouvees** : restaurees dans le logger sans alertes dupliquees
- **Distance parcourue** : persiste entre les sessions
- **Uptime cumule** : persiste entre les sessions
- **Waypoint intelligent** : au demarrage, saute au waypoint le plus proche au lieu de WP 1

### Vol automatique en Elytra
- Decollage automatique
- Maintien d'altitude de croisiere configurable avec hard cap
- Gestion automatique des fireworks (inventaire, hotbar, timing)
- Auto-swap elytra quand la durabilite est basse (compatible Mending)
- **Evitement d'obstacles** : detection du terrain devant et pull-up d'urgence
- **Prediction terrain** : utilise la seed 2b2t pour anticiper le relief
- **Anti-kick** : micro-variations de trajectoire
- Atterrissage automatique via Baritone

### Voyage intelligent (AutoTravel)

Module de voyage automatique vers n'importe quelles coordonnees :

- **Raccourci Nether** : utilise automatiquement le Nether pour les longs trajets (8x plus rapide)
- **Mode AUTO** : choisit elytra ou marche selon l'equipement et la dimension
- **Mode Marche** : sprint auto, saut auto, nage auto
- **Detection de portails** : scanne les chunks charges pour trouver les portails
- **Transitions de dimension** : detecte automatiquement le changement et continue

### Survie 24/7

Systemes automatiques pour fonctionner sans surveillance :

- **Auto Totem** : garde un Totem of Undying en offhand en permanence
- **Auto Manger** : mange automatiquement quand la faim est basse
- **Radar joueurs** : detecte les joueurs proches et deconnecte instantanement
- **Monitoring sante** : alerte et actions d'urgence quand les PV sont critiques
- **Reapprovisionnement fusees** : refill automatique depuis les shulker boxes
- **Sauvegarde automatique** : sauvegarde l'etat toutes les 5 minutes, restauration apres crash
- **Compensation lag 2b2t** : adapte tous les timings au TPS reel du serveur

### Navigation intelligente
- **Spirale** : recherche en spirale depuis la position actuelle
- **Highways** : suit les 8 highways (4 cardinales + 4 diagonales)
- **Quadrillage (GRID)** : decoupe en carres configurables et scanne systematiquement
- **Zone** : definir X debut/fin et Z debut/fin, quadrille toute la superficie
- **Aleatoire** : positions aleatoires dans un rayon configurable
- **Anneau** : recherche a une distance specifique
- **Custom** : waypoints personnalises

### Detection New/Old Chunks
- **Liquid Flow Exploit** : detecte les chunks jamais visites
- **Detection de version** : identifie les chunks generes dans d'anciennes versions de Minecraft
- **Overlay visuel** : affiche les chunks en couleur (rouge = nouveau, vert = ancien)

### Suivi de trails (3 methodes)
- **Block Trail** : detecte les chemins physiques (ice roads, rails, torches)
- **Chunk Trail** : repere les lignes de old chunks entoures de new chunks
- **Version Border** : detecte les frontieres entre chunks de differentes versions

## Modules

### BaseHunter (categorie External)
Le module principal. Active-le et il fait tout automatiquement.

### ChunkHistory (categorie External)
Detecte et visualise les new/old chunks. Fonctionne seul ou avec BaseHunter.

### ElytraBot (categorie External)
Module standalone pour voler en elytra vers des coordonnees specifiques.

### AutoTravel (categorie External)
Voyage intelligent vers des coordonnees avec raccourci Nether automatique.

## Commandes

Toutes les commandes commencent par `*basefinder` :

| Commande | Description |
|----------|-------------|
| `*basefinder status` | Affiche l'etat actuel |
| `*basefinder bases` | Liste les bases trouvees |
| `*basefinder export [fichier]` | Exporte les bases dans un fichier |
| `*basefinder waypoints [format]` | Exporte vers Xaero/VoxelMap |
| `*basefinder zone <minX,maxX,minZ,maxZ>` | Definir les bornes de la zone |
| `*basefinder discord` | Active/affiche les notifications Discord |
| `*basefinder pause` | Met en pause |
| `*basefinder resume` | Reprend |
| `*basefinder skip` | Passe au waypoint suivant |
| `*basefinder clear` | Efface toutes les donnees |
| `*basefinder resethud` | Reset la position du HUD |

### Configuration Discord

1. Creez un salon prive sur votre serveur Discord
2. Parametres du salon > Integrations > Webhooks > Nouveau webhook
3. Copiez l'URL du webhook
4. Collez-la dans le fichier `.minecraft/rusherhack/basefinder/state/discord_webhook.txt`
5. Tapez `*basefinder discord` en jeu pour activer

Pour desactiver : supprimez le contenu du fichier et refaites `*basefinder discord`.

## Installation

### Methode rapide (recommandee)

1. Aller dans l'onglet **[Releases](../../releases)** du repo
2. Telecharger `basefinder-2.0.0.jar` de la derniere release
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

Le jar se trouve dans `build/libs/basefinder-2.0.0.jar`.

> Le `.jar` est aussi build automatiquement par GitHub Actions a chaque push.

## Guide 24/7

1. **Preparation** :
   - Equipez une elytra Mending en chestplate
   - Mettez des elytras de rechange dans l'inventaire
   - Remplissez des shulker boxes de fireworks
   - Ayez des totems de l'immortalite
   - Ayez de la nourriture (golden apples idealement)

2. **Configuration** :
   - Activez tous les parametres de "Survie 24/7"
   - Choisissez votre mode de recherche
   - Sensibilite a 30 par defaut (augmenter si trop de faux positifs)
   - Activez "Sauvegarde auto" pour recuperer apres un crash
   - Configurez Discord pour recevoir les alertes sur votre telephone

3. **Lancement** : Activez BaseHunter et laissez tourner

---

# English

## Description

BaseFinder is a RusherHack plugin that automates base hunting on 2b2t. Just enable the module and it searches for bases on its own: it flies with elytra, scans chunks, follows trails, and logs everything it finds.

Designed for **24/7 unattended operation** with automatic survival systems.

## Key Features

- **7 search modes**: Spiral, Highways, Grid, Zone, Random, Ring, Custom
- **Smart detection**: constructions, storage, stashes, farms, portals, map art, trails, signs
- **Cluster scoring**: aggregates neighbor chunks for multi-chunk base detection
- **Freshness estimation**: ACTIVE, ABANDONED, or ANCIENT
- **Discord notifications**: webhook alerts with colored embeds for each base found
- **Session persistence**: scanned chunks, bases, distance, uptime survive restarts
- **Smart waypoint start**: skips to nearest waypoint instead of always starting at WP 1
- **Color-coded alerts**: clickable [GOTO] for Baritone with score coloring
- **24/7 survival**: auto totem, auto eat, player radar, firework resupply, health monitoring
- **2b2t lag compensation**: adaptive TPS, deferred chunk retry, safety altitude
- **AutoTravel**: Nether shortcut (8x faster), portal finding, auto walk/sprint/swim
- **Terrain prediction**: uses 2b2t seed for dynamic cruise altitude
- **Memory safe**: bounded collections, automatic cleanup for long sessions

## Modules

| Module | Description |
|--------|-------------|
| **BaseHunter** | Main module - automated base scanning with elytra flight, chunk analysis, trail following |
| **ChunkHistory** | New/old chunk detection and visualization |
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
| `*basefinder discord` | Toggle/show Discord notifications |
| `*basefinder pause` | Pause |
| `*basefinder resume` | Resume |
| `*basefinder skip` | Skip to next waypoint |
| `*basefinder clear` | Clear all data |

### Discord Setup

1. Create a private channel on your Discord server
2. Channel Settings > Integrations > Webhooks > New Webhook
3. Copy the webhook URL
4. Paste it into `.minecraft/rusherhack/basefinder/state/discord_webhook.txt`
5. Type `*basefinder discord` in-game to activate

## Installation

1. Go to **[Releases](../../releases)** tab
2. Download `basefinder-2.0.0.jar`
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

Output: `build/libs/basefinder-2.0.0.jar`
