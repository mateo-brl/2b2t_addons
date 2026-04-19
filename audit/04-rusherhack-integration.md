# 04 — Intégration RusherHack

> Audit de l'intégration API RusherHack pour le plugin **BaseFinder v2.1.0** (MC 1.21.4).
> Périmètre : 5 modules (`BaseHunter`, `ElytraBot`, `AutoTravel`, `AutoMending`, `PortalHunter`), 1 HUD, 2 commands, 1 wrapper Baritone reflection.
> Sources consultées : code local `src/main/java/**`, javadocs `rusherhack.org/api-javadocs/` (index, `RusherHackAPI`, packages `events.client`, `events.render`, `events.world`, `feature.module`, `feature.hud`, `deprecated-list`, `allclasses-index`, `IRenderer3D`).

---

## 1. Résumé version & build

| Source | Valeur |
|---|---|
| `build.gradle:43` | `rusherhackApi "org.rusherhack:rusherhack-api:$minecraft_version-SNAPSHOT"` |
| `gradle.properties:11` | `minecraft_version=1.21.4` → dépendance résolue `1.21.4-SNAPSHOT` |
| Channel | Maven `snapshots` uniquement (`build.gradle:25`) |
| Access widener | `src/main/resources/rusherhack-plugin.accesswidener` (référencé `build.gradle:50` mais non vérifié dans ce repo) |
| Manifeste | `src/main/resources/rusherhack-plugin.json` — `Plugin-Class: com.basefinder.BaseFinderPlugin`, version `${plugin_version}=2.1.0` |
| Baritone | **aucune dépendance compile**, accédé via `Class.forName("baritone.api.BaritoneAPI")` (`BaritoneController.java:40`) |
| Parchment | `parchment_version=1.21.4:2024.12.07` (`gradle.properties:12`) |
| Java | 21 (`gradle.properties:10`) |

### Décalage API ↔ usage code

- **Pas de version API épinglée** : `$minecraft_version-SNAPSHOT` se résout à la dernière snapshot au build — tout casse silencieusement à la moindre dérive upstream.
- **Aucun `Plugin-RusherHack-Version` dans le manifeste** → si RusherHack introduit un check compat à l'avenir, le plugin sera accepté sans garantie.
- Le code utilise exclusivement des classes présentes dans les javadocs publiques consultées (voir §2), donc pas de divergence *détectée* avec la surface API documentée.

Recommandation immédiate : verrouiller sur une snapshot datée (ex. `1.21.4-2025.xx.xx-SNAPSHOT`) ou convertir en release quand disponible, et ajouter le champ `Plugin-RusherHack-Version` au manifeste.

---

## 2. Usage des APIs — par surface

### 2.1 Modules

- **Pattern** : tous les modules étendent `ToggleableModule` et sont enregistrés via `RusherHackAPI.getModuleManager().registerFeature(...)` (`BaseFinderPlugin.java:29, 38, 47, 56, 65`).
- Exemple canonique : `BaseFinderModule.java:52` (`public class BaseFinderModule extends ToggleableModule`), constructeur `:195` (`super("BaseHunter", "...", ModuleCategory.EXTERNAL)`).
- Catégorie utilisée uniformément : `ModuleCategory.EXTERNAL` (5/5 modules).
- Hooks : `onEnable()`, `onDisable()`, `toggle()`, `isToggled()` — tous présents et idiomatiques.
- **Idiomatique** : OUI.
- **Deprecated** : aucun.
- **Remarque** : les 5 modules incorporent la même logique de lookup cross-module (`isElytraBotInUse()` dans `BaseFinderModule.java:251`, `ElytraBotModule.java:55`, `AutoTravelModule.java:106`) — code dupliqué, mais usage API correct.
- **Recommandation** : extraire l'interlock dans un utilitaire `ElytraBotMutex` et continuer à passer par `RusherHackAPI.getModuleManager().getFeature(name)`.

### 2.2 Settings

- **Pattern** : champs privés finals typés — `BooleanSetting`, `NumberSetting<T>`, `EnumSetting<E>`, `NullSetting` (groupe) — enregistrés en bloc via `this.registerSettings(...)` dans le constructeur, ex. `BaseFinderModule.java:233-245`.
- Groupage via `NullSetting + addSubSettings(...)` correct (`BaseFinderModule.java:199-231`).
- `NumberSetting.incremental(...)` utilisé (`BaseFinderModule.java:95, 96, 100…`) — bonne ergonomie.
- **Idiomatique** : OUI.
- **Deprecated** : aucun.
- **Piège** : la chaîne du nom du setting sert d'identifiant persisté dans la config RusherHack. Plusieurs modules déclarent un `langFr` séparé (`BaseFinderModule.java:183`, `ElytraBotModule.java:35`, `AutoTravelModule.java:54`) — leur config est donc *par module*, avec un état `Lang.setFrench(...)` statique global. Un utilisateur change le langage sur un module, ça impacte tous les autres jusqu'au prochain `onEnable`. Voir §3.
- **Recommandation** : remplacer le `BooleanSetting langFr` par une setting dans un module "config global" (ou dans le plugin lui-même via `ISettingManager` côté plugin si dispo) — hors scope de ce document mais à noter.

### 2.3 Commands

- **Pattern** : extension de `org.rusherhack.client.api.feature.command.Command`, sous-commandes via `@CommandExecutor` et `@CommandExecutor.Argument({...})` (`BaseFinderCommand.java:20, 26, 70, 111-113`, `PortalHunterCommand.java:15-125`).
- Parsing d'arguments groupés via `split(",")` dans une seule string (`BaseFinderCommand.java:171`, `PortalHunterCommand.java:81`) — fonctionne mais contourne le typage RusherHack.
- Prefix `*` (config RusherHack par défaut), utilisable depuis le chat.
- **Idiomatique** : PARTIEL.
- **Deprecated** : aucun.
- **Problème** : `BaseFinderCommand.java:83-87` utilise `Minecraft.getInstance().execute(...)` pour ouvrir un `ChatScreen` avec `#goto x z` — hack pour déléguer à Baritone via son préfixe. Pourrait simplement envoyer le message via `ChatUtils` ou le command manager de RusherHack si exposé.
- **Recommandation** : passer à des arguments typés (`@CommandExecutor.Argument({"minX","maxX","minZ","maxZ"})` avec 4 params Integer) — documenté dans les javadocs `feature.command`.

### 2.4 HUD

- **Pattern** : `BaseFinderHud extends HudElement` (`BaseFinderHud.java:25`), constructeur `super("BaseFinderHud", "BaseFinder")` (`:63`), enregistré via `RusherHackAPI.getHudManager().registerFeature(hud)` (`BaseFinderPlugin.java:74`).
- Override de `renderContent(RenderContext, double, double)` (`:82`) — **bon overload**. L'overload `(RenderContext, int, int)` est marqué `@Deprecated(forRemoval = true)` dans les javadocs → le code n'est pas concerné.
- `getWidth()` / `getHeight()` / `shouldDrawBackground()` overrides corrects.
- Accès font : `getFontRenderer()` → `IFontRenderer` (`:83`). Dessin texte via `font.drawString(...)` : **API correcte**.
- **Idiomatique** : OUI.
- **Deprecated** : aucun détecté, mais la classe ne gère pas de settings persistés (pas d'override `HudElement.registerSetting`). Pour un HUD de cette richesse c'est sous-exploité.
- **Recommandation** :
  1. Exposer ~10 couleurs et `barWidth` (`BaseFinderHud.java:275`) en `ColorSetting`/`NumberSetting` registred sur le `HudElement` — c'est supporté par l'API.
  2. Considérer `ListHudElement` ou `ShortListHudElement` pour la liste des bases trouvées (séparer en 2 HudElements permettrait de les positionner indépendamment).

### 2.5 Events

- **Usage** : un seul type d'event souscrit dans tout le plugin — `EventUpdate` (tick client), via `@Subscribe` du package `org.rusherhack.core.event.subscribe`.
  - `BaseFinderModule.java:581`, `ElytraBotModule.java:145`, `AutoTravelModule.java:178`, `AutoMendingModule.java:176`, `PortalHunterModule.java:318`.
- **Idiomatique** : OUI (usage minimal et correct).
- **Deprecated** : aucun.
- **Sous-utilisation majeure** — voir §4 :
  - `EventLoadWorld` (package `events.world`) existe et remplacerait avantageusement la détection ad-hoc de changement de dimension dans `AutoTravelModule.java:185-188` (`getCurrentDimension() + lastDimension` comparé chaque tick) et `PortalHunterModule.java:360-364`.
  - `EventChunk.Load` / `EventChunk.Unload` remplacerait le scan polling toutes les N ticks dans `ChunkScanner.scanLoadedChunks()` (appelé `BaseFinderModule.java:674, 812, 852, 948`).
  - `EventQuit` remplacerait la logique "déconnexion auto" dans `SurvivalManager` (le code actuel ne capture pas le désabonnement propre).
  - `EventRender2D` / `EventRender3D` ne sont pas utilisés — rendre des waypoints en monde via `IRenderer3D` serait plus visible que le HUD texte.

### 2.6 Rendering

- **Usage actuel** : aucun rendering 3D (ni `IRenderer3D`, ni `IRenderer2D` direct). Seul le HUD texte via `IFontRenderer`.
- **Deprecated** : N/A.
- **Recommandation** : utiliser `RusherHackAPI.getRenderer3D()` dans un nouvel `EventRender3D` pour :
  - Afficher la position des waypoints de scan (`NavigationHelper.getWaypoints()`).
  - Tracer la trajectoire prévue par `PhysicsSimulator`.
  - Mettre en évidence les bases détectées (`BaseLogger.getRecords()`).
  - Méthodes dispo : `drawBox(BlockPos, fill, outline)`, `drawLine(Vec3, Vec3)`, `lineStrip(...)`, `projectToScreen(...)` (javadocs `IRenderer3D`). Batch via `getLinesBuffer()`.

### 2.7 Baritone

- **Pattern** : **100% reflection** dans `BaritoneController.java` (442 lignes, 12 sites de reflection distincts).
- `Class.forName("baritone.api.BaritoneAPI")` (`:40`), `getProvider()` → `getPrimaryBaritone()` (`:41-45`).
- **Vérification javadocs RusherHack** : aucune classe `Baritone*`, `Navigator*`, `IPathing*` dans `allclasses-index.html`. **RusherHack n'expose PAS d'API Baritone officielle.** La reflection est donc *actuellement* la seule voie — mais cela signifie que tout repose sur la stabilité des signatures Baritone internes.
- **Idiomatique côté RusherHack** : N/A (hors API).
- **Dette technique majeure** : voir §3.1.

---

## 3. Hacks et dette technique

### 3.1 Reflection Baritone — inventaire complet

Tous dans `src/main/java/com/basefinder/util/BaritoneController.java` :

| Ligne | Méthode invoquée | Classe / interface ciblée | Risque |
|---:|---|---|---|
| 40 | `Class.forName` | `baritone.api.BaritoneAPI` | Rupture si renommage du package |
| 41 | `getProvider()` | `BaritoneAPI` (static) | Signature critique, stable historiquement |
| 44 | `getPrimaryBaritone()` | `IBaritoneProvider` | Idem |
| 52 | `getElytraProcess()` | `IBaritone` | **Peut ne pas exister** → test fallback l:56-59 (OK) |
| 82 | Packet `START_FALL_FLYING` | `net.minecraft.network.protocol...` | Hack direct MC, pas Baritone |
| 96 | `BaritoneAPI.getSettings()` | static | OK |
| 101-104 | `setBaritoneSettingBool` | `Settings$Setting.value` via `getDeclaredField("value").setAccessible(true)` | **TRÈS fragile** — traverse `superclass` à la main (`:423`), casse à toute refonte `Settings` |
| 128 | `getCustomGoalProcess()` | `IBaritone` | Stable |
| 132-138 | `new GoalNear(BlockPos,int)` + `setGoalAndPath` | `baritone.api.pathing.goals.*` | Classe publique, API documentée, OK |
| 192-195 | `getPathingBehavior().isPathing()` | | Stable |
| 211-214 | `cancelEverything()` | `IPathingBehavior` | Stable |
| 239 | `getCommandManager().execute(String)` | | Stable |
| 259-263 | `getMineProcess().isActive()` | | Stable |
| 276-285 | `GoalXZ(int,int)` + `setGoalAndPath` | idem GoalNear | OK |
| 298-306 | `GoalNear(BlockPos,int)` + `setGoalAndPath` | idem | OK |
| 329-344 | `getElytraProcess().pathTo(Goal)` | `IElytraProcess` | Nether-pathfinder, stable si présent |
| 364 | `getElytraProcess().isActive()` | | Stable |
| 384 | `getElytraProcess().cancel()` | | **Fallback à `cancelAll()` si manquant** (`:388-392`) — OK |
| 419-429 / 431-441 | `setBaritoneSetting{Bool,Int}` réflexion sur `value` | Internals Baritone | **Le plus fragile** |

**Risques cumulés** :
- Si Baritone update casse `Settings`, 2 réglages silencieusement ignorés → atterrissages plus agressifs (fall damage > attendu).
- Si `getElytraProcess()` disparaît, fallback correct l:56-59 (OK).
- Aucun test de présence de `BaritoneAPI.getProvider` au moment du `getInstance()` lors des appels hot path (ex. `isPathing()` l:189) → NPE silencieux en catch Exception.

**Recommandations** :
1. Ajouter la dépendance Baritone en **`compileOnly`** (elle est bundled runtime donc ok) pour supprimer la reflection sur les classes publiques (`BaritoneAPI`, `GoalXZ`, `GoalNear`, `Goal`).
2. Isoler la reflection *strictement* sur la couche `Settings.value` (le seul vrai hack non-public).
3. Un test "smoke" en démarrage qui vérifie `GoalNear`, `setGoalAndPath`, `cancelEverything` ET `allowParkour`/`maxFallHeightNoWater` pour prévenir à l'enable (pas au milieu d'un vol).

### 3.2 `Minecraft.getInstance()` hors couche adapter

**22 occurrences sur 20 fichiers** (grep exhaustif). Distribution :

| Layer | Nombre | Exemple |
|---|---:|---|
| `elytra/ElytraBot.java` | 1 | `:46` |
| `scanner/*` (ChunkScanner, EntityScanner, FreshnessEstimator, ChunkAgeAnalyzer) | 4 | `ChunkScanner.java:26` |
| `survival/*` (SurvivalManager, AutoTotem, AutoEat, PlayerDetector, FireworkResupply) | 5 | `AutoTotem.java:16` |
| `trail/TrailFollower.java` | 1 | `:28` |
| `terrain/HeightmapCache.java` | 1 | `:17` |
| `navigation/NavigationHelper.java` | 1 | `:15` |
| `logger/BaseLogger.java` | 3 | `:44, :90, :126` (dont `gameDirectory.toPath()`) |
| `persistence/StateManager.java` | 1 | `:41` |
| `util/{BaritoneController, WaypointExporter, LagDetector}.java` | 4 | `BaritoneController.java:20` |
| `command/BaseFinderCommand.java` | 1 | `:83` |

RusherHack fournit `org.rusherhack.client.api.utils.{PlayerUtils, InventoryUtils, RotationUtils, WorldUtils, EntityUtils, InputUtils}` et `RusherHackAPI.getConfigPath()` pour la partie IO — utilisés **zéro fois**.

Ce n'est pas un bug fonctionnel mais :
- Double source de vérité (toute classe ayant `mc.player` doit tester null → fait partout, OK mais bruyant).
- Impossible de tester unitairement (aucune abstraction).
- Pas d'uniformité avec l'écosystème RusherHack.

**Recommandation** : au minimum, migrer les chemins IO vers `RusherHackAPI.getConfigPath()` + utiliser `PlayerUtils` pour les lookups d'inventaire/santé.

### 3.3 `System.out.println` / `System.err.println`

**Aucun** (`grep -n 'System\\.(out|err)\\.print' src/main/java` → 0 match). Bien.

### 3.4 `printStackTrace()`

**11 occurrences** :
- `BaseFinderPlugin.java:33, 42, 51, 60, 69, 78, 87, 96` (8× dans les try/catch de registration)
- `modules/BaseFinderModule.java:679`
- `modules/PortalHunterModule.java:383`
- `scanner/ChunkScanner.java:244`

Le plugin a par ailleurs un `Logger` SLF4J (`BaseFinderModule.java:664` : `LoggerFactory.getLogger("BaseFinder")`) et RusherHack expose `RusherHackAPI.createLogger(String)`. Les `printStackTrace()` vont vers stderr, contournent la config log.

**Recommandation** : remplacer systématiquement par `LOGGER.error("context", e)`.

### 3.5 Accès à des internals MC non-mappés

Aucun accès à des champs obfusqués ou privés détecté côté MC (tous les appels passent par des méthodes Mojang-mappées grâce à Parchment). Les quelques champs réflexion sont sur **Baritone**, pas MC.

Un access widener est référencé (`build.gradle:50` → `src/main/resources/rusherhack-plugin.accesswidener`) mais son contenu n'a pas été audité ici — à vérifier dans un chapitre dédié.

### 3.6 Autres hacks notables

- **Input simulation via `mc.options.keyUp/keySprint/keyJump.setDown(bool)`** dispersé dans 5 fichiers (`AutoTravelModule`, `BaseFinderModule`, `PortalHunterModule`, `AutoEat`, `FireworkResupply`). RusherHack fournit `InputUtils` (javadoc `api.utils.InputUtils`) : non utilisé. Plus important, la rotation `mc.player.setYRot/setXRot` en dehors d'un `IRotationManager` entre en conflit potentiel avec d'autres modules RusherHack qui font des rotations (AimBot, Freecam, etc.).
- **`ChatScreen` direct pour exécuter `#goto`** (`BaseFinderCommand.java:83-87`) : hack, cf §2.3.
- **Pas de thread safety** autour de `static` mutable `Lang.setFrench(...)` (partagé entre modules, pas thread-safe).

---

## 4. APIs sous-utilisées

> Chaque entrée : API → point d'ancrage actuel → gain attendu.

### 4.1 `IRotationManager` (via `RusherHackAPI.getRotationManager()`)

- **Javadoc** : `RusherHackAPI.getRotationManager()` → `IRotationManager`.
- **Actuellement** : rotations brutes `mc.player.setYRot/setXRot` dans `ElytraBot.java:529-530`, `BaseFinderModule.java:803, 1008, 1053`, `AutoTravelModule.java:639`, `PortalHunterModule.java:1416`, `FireworkResupply.java:131`.
- **Gain** : coordination propre avec les autres modules RusherHack, suppression des conflits de rotation silencieux, pilotable depuis d'autres plugins (policy "silent rotations"). ~15-20 lignes supprimées + robustesse.

### 4.2 `EventLoadWorld` (package `events.world`)

- **Javadoc** : `events.world.EventLoadWorld` — déclenché à chaque changement de monde/dimension.
- **Actuellement** : chaque tick, `AutoTravelModule.java:185-188` et `PortalHunterModule.java:360-364` recalculent `getCurrentDimension()` puis comparent à `lastDimension`.
- **Gain** : event-driven → élimine ~5 lignes + un champ d'état par module, garantit qu'on rate aucune transition.

### 4.3 `EventChunk.Load` (package `events.world`)

- **Javadoc** : `events.world.EventChunk.Load` — callback au chargement d'un chunk.
- **Actuellement** : `ChunkScanner.scanLoadedChunks()` est appelé toutes les N ticks (polling, cf `BaseFinderModule.java:668`) et itère sur les chunks chargés à chaque invocation.
- **Gain** : traitement incrémental (scan *un* chunk à l'event), disparition du polling, baisse CPU sur long-run. ~30-40 lignes simplifiées dans `ChunkScanner`.

### 4.4 `RusherHackAPI.getRenderer3D()` → `IRenderer3D`

- **Javadoc** : `render.IRenderer3D` — `drawBox`, `drawLine`, `drawShape`, `lineStrip`, `projectToScreen`, accès buffers pour batch.
- **Actuellement** : aucun rendu 3D. Les waypoints et les bases sont invisibles à l'écran (juste loggés).
- **Gain** : UX massivement améliorée, debug visuel du `PhysicsSimulator` (trajectoire prédite), des waypoints de zone, des bases détectées. Pas de ligne supprimée mais *feature* critique gagnée à coût faible (~50 lignes nouvelles centralisées).

### 4.5 `org.rusherhack.client.api.utils.PlayerUtils` / `InventoryUtils` / `EntityUtils`

- **Actuellement** : boucles manuelles dans `AutoTotem`, `AutoEat`, `FireworkResupply`, `SurvivalManager` pour chercher items/entités.
- Exemples qui pourraient disparaître : boucle d'inventaire dans `AutoMendingModule.findMostDamagedMendingElytra()`, `ElytraBot.getFireworkCount()`, `scanForPortal` dans `AutoTravelModule.java:709`.
- **Gain** : ~40-80 lignes, consistance.

### 4.6 `IHudElement` persisted settings

- **Javadoc** : `HudElement` accepte des settings comme les modules.
- **Actuellement** : `BaseFinderHud.java:29-56` — 14 couleurs + 3 tailles en constantes.
- **Gain** : configuration utilisateur, persistence automatique via la config RusherHack, cohérence ergonomique avec les autres HUDs.

### 4.7 `INotificationManager` (`RusherHackAPI.getNotificationManager()`)

- **Actuellement** : tout passe par `ChatUtils.print(...)` (143 occurrences). Inconvénient : pollue le chat, illisible sur 2b2t lag.
- **Gain** : notifications toast temporaires pour les événements ponctuels (base trouvée, timeout), chat réservé à ce qui doit rester.

### 4.8 `RusherHackAPI.createLogger(String)` au lieu de `LoggerFactory.getLogger`

- **Actuellement** : `org.slf4j.LoggerFactory.getLogger("BaseFinder")` dans chaque classe (`BaseFinderModule.java:664`, `AutoTravel.java:34`, etc.).
- **Gain** : log routés par le système RusherHack (pouvant inclure une sortie in-game filtrée), cohérence. Remplacement quasi-trivial, ~5 lignes par fichier.

---

## 5. Compatibilité future

### 5.1 Bloquants mise à jour RusherHack

- **Snapshot non épinglée** (`build.gradle:43`) : chaque build peut changer la surface d'API reçue. → Premier verrou à ajouter.
- **Usage surface** : tous les appels API utilisés sont *non-deprecated* d'après `deprecated-list.html` (seul `IHudElement.renderContent(RenderContext,int,int)` est deprecated, et on override la version `(double,double)` → OK).
- **Interlocks maison** (`isElytraBotInUse()`) reposent sur les noms des modules comme strings — si un user renomme via config RusherHack, ça casse. Utiliser `getFeature(Class)` si supporté, sinon la classe directement.

### 5.2 Bloquants mise à jour MC (1.21.5 / 1.22)

- **Accès direct `mc.options.keyXxx.setDown(...)`** : stable historiquement mais peut changer. À migrer vers `InputUtils` dès que possible.
- **Packet `ServerboundPlayerCommandPacket.Action.START_FALL_FLYING`** (`BaritoneController.java:82`, aussi `ElytraBot.java`) : enum stable dans MC depuis 1.8, faible risque.
- **`mc.level.dimension() == Level.OVERWORLD`** (`AutoTravelModule.java:781`) : stable.
- **`chunk.getBlockState(BlockPos)`** (`AutoTravelModule.java:739`) : légèrement obsolète (API préfère `chunk.getBlockState(int,int,int)` relatif), mais fonctionnel.
- **Parchment 1.21.4:2024.12.07** épinglé : à mettre à jour pour MC 1.21.5+.
- **Pas de Mixin maison** dans le code audité ici (access widener référencé mais contenu non vu) → risque MC faible globalement.

### 5.3 Bloquants côté Baritone

- **Reflection sur `Settings.value`** (`BaritoneController.java:419-441`) : la chose la plus fragile du repo. À la moindre refactor Baritone, 2 settings sont silencieusement ignorés. Ajouter Baritone en `compileOnly` le corrige.
- **`getElytraProcess()` peut disparaître** : déjà prévu, fallback OK.

---

## 6. Top 5 changements prioritaires

| # | Changement | Fichier:ligne | Effort | Valeur |
|---:|---|---|---|---|
| 1 | Ajouter Baritone en `compileOnly` et supprimer la reflection sur les classes `baritone.api.*` publiques. Garder la reflection *uniquement* sur `Settings.value`. | `build.gradle:43` + refactor `BaritoneController.java:40-441` | **M** (1-2 j) | Très haute : supprime ~150 lignes de reflection fragile, fait remonter les erreurs au compile. |
| 2 | Remplacer les 11 `e.printStackTrace()` par `LOGGER.error("context", e)`. Enregistrer les exceptions dans le canal RusherHack via `RusherHackAPI.createLogger`. | `BaseFinderPlugin.java:33, 42, 51, 60, 69, 78, 87, 96` + `BaseFinderModule.java:679` + `PortalHunterModule.java:383` + `ChunkScanner.java:244` | **S** (2 h) | Moyenne : observabilité, hygiène. |
| 3 | Épingler `rusherhack-api` sur une snapshot datée et ajouter `Plugin-RusherHack-Version` au manifeste. | `build.gradle:43`, `gradle.properties`, `src/main/resources/rusherhack-plugin.json` | **XS** (30 min) | Haute : reproductibilité + garde-fou futur. |
| 4 | Passer les rotations (`setYRot/setXRot`) et les inputs (`mc.options.keyXxx.setDown`) via `RusherHackAPI.getRotationManager()` et `InputUtils`. | `ElytraBot.java:529-530`, `BaseFinderModule.java:803, 1008, 1053`, `AutoTravelModule.java:639, 642-697`, `PortalHunterModule.java:1416-1439`, `AutoEat.java`, `FireworkResupply.java:131` | **M** (1 j) | Haute : coexistence propre avec autres modules RusherHack (AimBot, etc.), préparation MC update. |
| 5 | Câbler `EventLoadWorld` pour la détection de dimension et `EventChunk.Load` pour le scan incrémental. | `AutoTravelModule.java:185-189`, `PortalHunterModule.java:360-364`, `ChunkScanner.scanLoadedChunks()` appelé depuis `BaseFinderModule.java:674, 812, 852, 948` | **M** (1 j) | Moyenne-haute : baisse CPU, simplification, event-driven idiomatique. |

**Bonus hors top 5** : rendre les waypoints/bases visibles en monde via `IRenderer3D` sous `EventRender3D` (effort M, valeur UX élevée).

---

## Notes de méthodologie

- **Javadocs accessibles** : `RusherHackAPI`, `feature.module`, `feature.hud`, `events.client`, `events.render`, `events.world`, `deprecated-list`, `allclasses-index`, `IRenderer3D`.
- **Pas trouvé** : pas de classe Baritone/Waypoint/Navigator dans `allclasses-index.html` → conclusion ferme qu'il n'y a pas d'API Baritone officielle.
- **Non audité faute de besoin** : `ISettingManager` côté plugin, `IThemeManager`, `IBindManager`, `IServerState`, `IWindowManager` — potentiellement intéressants mais hors scope prioritaire.
- **Non audité** : `src/main/resources/rusherhack-plugin.accesswidener` (contenu), détail complet `PortalHunterModule.java` (audité uniquement l'API boundary).
