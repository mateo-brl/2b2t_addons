# 02 — Bugs latents et risques

Audit statique BaseFinder v2.1.0 (branche `audit/principal-engineer-review`, HEAD `7d41d32`).
Repérage manuel sur les 5 fichiers prioritaires + échantillonnage (BaseLogger, AutoMending,
SurvivalManager, StateManager, AutoEat, AutoTotem, PlayerDetector, ChunkScanner, BaritoneController, BaseFinderPlugin).

## Résumé

| Sévérité | Nombre | Thématique dominante                                           |
|----------|--------|----------------------------------------------------------------|
| S1       | 7      | Resource leaks, reset manquant, DataComponents, reflection     |
| S2       | 10     | State reset incomplet, null-safety hors `mc.player`, races     |
| S3       | 5      | Throttling, exceptions silencieuses, throttle erratique        |
| **Total**| **22** |                                                                |

## Format

> Chaque entrée : **Sévérité** · `fichier:ligne` · hypothèse · scénario · preuve · correctif.
> `[HYPOTHÈSE À VALIDER]` = besoin d'une vérification runtime/dépôt externe.

---

## 1. Race conditions (threads)

### BUG-001 — DiscordNotifier.shutdown() ne clôt jamais son executor dans PortalHunterModule
- **Sévérité** : S1 (thread leak + webhook queue orpheline)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/PortalHunterModule.java:57` (création), `PortalHunterModule.java:296` (`onDisable`)
- **Hypothèse** : `PortalHunterModule` crée son propre `BaseLogger` (qui instancie un `DiscordNotifier` avec un `ExecutorService`). `onDisable()` ne fait aucun appel `baseLogger.getDiscordNotifier().shutdown()`. Le thread daemon `DiscordNotifier` reste vivant indéfiniment, et toute alerte postée pendant une session précédente peut se déverser après le `toggle off`.
- **Scénario reproducteur** :
  1. Enable PortalHunter, une base est trouvée → `notifyBase()` enfile un POST.
  2. Disable PortalHunter immédiatement.
  3. L'executor continue à tourner, le POST part quand même ; si la session s'est toggle/untoggle N fois, on accumule N executors (chacun avec ses tâches en file).
- **Preuve / indice** : `DiscordNotifier.shutdown()` n'est appelé QUE par `BaseFinderModule.onDisable()` ligne 466. `PortalHunterModule.onDisable` (lignes 295-312) ne l'appelle pas. Voir aussi commit `ed34bd0` "fix: DiscordNotifier shutdown" qui prouve le risque.
- **Correctif esquissé** : Dans `PortalHunterModule.onDisable()`, ajouter `baseLogger.getDiscordNotifier().shutdown();` (après `survivalManager.stop()`).

### BUG-002 — Executor recréé impossible : `shutdownNow()` rend le notifier inutilisable au re-enable
- **Sévérité** : S2 (fonctionnalité perdue)
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/DiscordNotifier.java:21-25` et `187-189`
- **Hypothèse** : L'`ExecutorService` est marqué `final` et construit au constructeur. Après `shutdown()`, toute nouvelle `executor.submit(...)` lève `RejectedExecutionException`. Si le joueur disable puis re-enable `BaseHunter` au cours de la même session, **plus aucun webhook Discord ne part** (l'exception est avalée par le `try/catch` autour du bloc submit — non, en fait l'exception sort parce que `submit()` n'est pas dans le try).
- **Scénario reproducteur** :
  1. Enable `BaseHunter` → webhook fonctionne.
  2. Disable `BaseHunter` → `shutdown()` appelé, `executor` est mort.
  3. Re-enable `BaseHunter` → la même instance de `DiscordNotifier` est réutilisée (c'est un champ final de `BaseLogger` qui est aussi final dans le module).
  4. Une base est trouvée → `notifyBase()` appelle `executor.submit(...)` qui throw.
- **Preuve / indice** :
  ```java
  private final ExecutorService executor = Executors.newSingleThreadExecutor(...); // DiscordNotifier.java:21
  ...
  public void shutdown() { executor.shutdownNow(); } // ligne 187
  ```
  `BaseLogger logger = new BaseLogger()` est champ `final` de `BaseFinderModule`, donc survit aux toggles.
- **Correctif esquissé** : Soit recréer l'executor dans `onEnable()` (retirer le `final`), soit remplacer `shutdownNow()` par un drain gracieux (laisser la queue se vider et ne jamais détruire l'executor).

### BUG-003 — BaseLogger.records : itération concurrente possible dans buildLines HUD
- **Sévérité** : S2 (ConcurrentModificationException possible)
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/BaseLogger.java:257` (`getRecords()`), `BaseLogger.java:234-240` (`exportAll`)
- **Hypothèse** : `records` est `Collections.synchronizedList`. Toutes les mutations sont dans des `synchronized(records)` (bon), sauf `restoreRecord()` (ligne 253-255) qui fait un `records.add(record)` non protégé. Plus critique : `getRecords()` expose la liste sans itérateur sûr — tout code extérieur qui itère (ex. `StateManager.saveState(logger.getRecords(), ...)`) doit synchroniser sur `records`, ce qui n'est fait nulle part.
- **Scénario reproducteur** :
  1. `BaseFinderModule.onUpdate()` tick : `stateManager.saveState(logger.getRecords(), ...)` itère.
  2. En parallèle, `handleScanning` appelle `logger.logBase(record)` depuis le même event bus thread (pas de race ici) — mais **`restoreRecord()` appelé depuis `onEnable` pendant que `saveState` a commencé un auto-save** → peut crasher.
- **Preuve / indice** :
  ```java
  public void restoreRecord(BaseRecord record) { records.add(record); } // pas de synchronized
  ```
  Voir commit `ed34bd0` "BaseLogger race condition" qui atteste d'un bug similaire déjà rencontré.
- **Correctif esquissé** : Ajouter `synchronized(records)` autour du `add` dans `restoreRecord()`, et documenter que `getRecords()` doit être itéré sous `synchronized(logger)`.

### BUG-004 — ConcurrentHashMap scannedChunks modifié pendant getScannedChunksSet() → saveScannedChunks()
- **Sévérité** : S3 (perte partielle d'index, non fatal)
- **Fichier:ligne** : `src/main/java/com/basefinder/scanner/ChunkScanner.java:397` et `persistence/StateManager.java:75-88`
- **Hypothèse** : `getScannedChunksSet()` retourne `Collections.unmodifiableSet(scannedChunks)`. `StateManager.saveScannedChunks()` itère ce set avec un `for (ChunkPos pos : chunks)`. Comme ChunkScanner ajoute des chunks pendant l'itération (le scan tourne sur le même thread game tick, donc pas de race technique), mais `dos.writeInt(chunks.size())` peut différer du nombre d'éléments effectivement itérés si une mutation se glisse entre le `size()` et la boucle. `ConcurrentHashMap.newKeySet()` autorise cette interleave ; le fichier .dat peut être corrompu (count ≠ éléments lus au `loadScannedChunks`).
- **Scénario reproducteur** : Auto-save à 5 min exactement au moment où `scanLoadedChunks()` batche 50 chunks nouveaux. [HYPOTHÈSE À VALIDER] : en pratique tous les appels sont event-bus thread, donc interleave probablement impossible.
- **Correctif esquissé** : Snapshot `new HashSet<>(chunks)` avant `writeInt` dans `StateManager.saveScannedChunks`.

---

## 2. Null safety / NPE latents

### BUG-005 — BaseLogger.writeToFile() NPE si logFile est null
- **Sévérité** : S2 (silent fail mais log perdu indéfiniment)
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/BaseLogger.java:52-54` et `220-227`
- **Hypothèse** : Le constructeur fait `Files.createDirectories(pluginDir)` dans un try/catch qui, en cas d'échec, fait `logFile = Path.of("basefinder_bases.log")`. Mais `screenshotDir` reste `null` en cas de même échec (aucun fallback). Tout appel ultérieur à `takeScreenshot` via `mc.gameDirectory` n'utilise même pas `screenshotDir` donc ça passe, mais `exportAll()` appelle `logFile.getParent().resolve(filename)` ligne 231 — si `logFile` est le fallback `Path.of("basefinder_bases.log")`, `getParent()` retourne **null** sur beaucoup d'OS, d'où NPE.
- **Scénario reproducteur** : `.minecraft/rusherhack/` non inscriptible (droits) → fallback → user tape `*basefinder export` → NPE.
- **Preuve / indice** : `logFile = Path.of("basefinder_bases.log")` ligne 53. `Path.of("foo").getParent()` retourne `null`.
- **Correctif esquissé** : Après fallback, réinitialiser avec `Path.of(".", "basefinder_bases.log")` ; ou guard `if (logFile.getParent() == null)` dans `exportAll`.

### BUG-006 — FireworkResupply.placeShulker() : setSelected slot négatif si shulker en slot 0-8
- **Sévérité** : S2 (selected = slot - 36 = négatif → crash à l'utilisation)
- **Fichier:ligne** : `src/main/java/com/basefinder/survival/FireworkResupply.java:122-128`
- **Hypothèse** : `findShulkerWithFireworks()` itère slots 9..44 (indices inventoryMenu) donc hotbar = slots 36-44 ⇒ `shulkerSlot - 36` va de 0 à 8 : OK. Mais la branche `if (shulkerSlot >= 9)` couvre tout slot de l'inventaire principale (9-35). Les slots 36-44 tombent dans le `else { selected = shulkerSlot - 36 }` : OK. En revanche, `findShulkerWithFireworks` ne scanne PAS la hotbar via l'index joueur direct : `inventoryMenu.getSlot(9..44)` où 36..44 = hotbar (slot 0-8 joueur). La conversion semble correcte mais fragile ; `shulkerSlot = 9` fait `swapSlots(9, 36)` puis `selected = 0` — OK.
- **Scénario reproducteur** : [HYPOTHÈSE À VALIDER] indexation menuSlot vs hotbarSlot. Pas de crash lu mais la double indexation (shulkerSlot entre 9-35 vs 36-44) est confuse et non documentée.
- **Preuve / indice** :
  ```java
  if (shulkerSlot >= 9) { InventoryUtils.swapSlots(shulkerSlot, 36); ... selected = 0; }
  else { mc.player.getInventory().selected = shulkerSlot - 36; } // unreachable (shulkerSlot toujours ≥ 9)
  ```
  La branche `else` ne peut jamais être prise puisque la boucle commence à `i = 9`.
- **Correctif esquissé** : Supprimer la branche morte, ou corriger la boucle `findShulkerWithFireworks` pour scanner aussi la hotbar (slots 36-44 sont déjà inclus dans l'itération, donc c'est la condition `if` qui est surjective).

### BUG-007 — ElytraBot.useFirework() sans vérif mc.level avant inventoryMenu.getSlot()
- **Sévérité** : S3 (NPE improbable)
- **Fichier:ligne** : `src/main/java/com/basefinder/elytra/ElytraBot.java:1198-1220`
- **Hypothèse** : `useFirework` retourne si `mc.player == null` mais utilise `mc.player.inventoryMenu` qui peut être null en plein changement de dimension.
- **Correctif esquissé** : Ajouter `if (mc.player.inventoryMenu == null) return;`.

### BUG-008 — PortalHunter.scanForNetherPortals() itère sans null-check level
- **Sévérité** : S3
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/PortalHunterModule.java:1076-1108`
- **Hypothèse** : La fonction check `mc.player == null || mc.level == null` au début, mais `onDimensionChanged` (appelée juste avant par le même tick) peut nuller le level entre le check et la lecture. Le risque est limité (event bus single-thread pour MC client).
- **Correctif esquissé** : Cacher `mc.level` dans une variable locale après le check.

### BUG-009 — SurvivalManager.checkEquipmentCritical sendAlert sans null-check discordNotifier
- **Sévérité** : S3 (protégé par isEnabled mais fragile)
- **Fichier:ligne** : `src/main/java/com/basefinder/survival/SurvivalManager.java:258-262`
- **Hypothèse** : `sendAlert()` appelle `discordNotifier.isEnabled()` seulement si `discordNotifier != null`. OK. Mais les appelants de `sendAlert` ne vérifient rien : si `setDiscordNotifier()` n'a jamais été appelé (ex. `PortalHunterModule` qui n'utilise pas SurvivalManager.setDiscordNotifier), on rentre dans la branche où `discordNotifier == null` et on skip — mais aucune stat n'est remontée pour alerter l'utilisateur que les alertes sont silencieuses.
- **Correctif esquissé** : Log une warning au premier appel de `sendAlert` si `discordNotifier == null`.

---

## 3. Resource leaks (executors, HTTP, file handles)

### BUG-010 — HttpURLConnection jamais disconnect() en cas d'exception mid-request
- **Sévérité** : S2 (file handle leak par webhook)
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/DiscordNotifier.java:43-75` et `151-184`
- **Hypothèse** : `conn.disconnect()` n'est appelé qu'après le `try/catch` externe dans la **branche normale** (ligne 72). Si `conn.getOutputStream()` ou `conn.getResponseCode()` lève, `disconnect()` n'est jamais appelé et la connexion reste. Idem pour `retry` dans la branche 429 : `retry.disconnect()` n'est appelé qu'après un `retry.getResponseCode()` réussi.
- **Scénario reproducteur** : Webhook Discord lent ou coupure réseau → `SocketTimeoutException` sur `getOutputStream` → pas de `disconnect()`.
- **Preuve / indice** : Le `try/catch (Exception e)` englobe tout mais il n'y a pas de `finally { conn.disconnect(); }`.
- **Correctif esquissé** : Envelopper avec `try { ... } finally { if (conn != null) conn.disconnect(); }`.

### BUG-011 — FireworkResupply : shulker perdu si player déplacé par lag pendant PLACING
- **Sévérité** : S1 (perte d'item shulker → perte permanente de données inventaire)
- **Fichier:ligne** : `src/main/java/com/basefinder/survival/FireworkResupply.java:130-148`
- **Hypothèse** : `placeShulker()` place à `blockPosition()` au tick T, puis `waitForPlacement` vérifie à `placedShulkerPos` au tick T+5. Mais **l'appel `mc.player.getInventory().selected = 0;` combiné à `mc.player.setXRot(90.0f)` et `useItemOn` le TOUT DANS LE MÊME TICK** crée un paquet d'usage avant que le tick serveur propage le slot swap. Résultat : le serveur rejette le `useItemOn` (slot encore shulker côté client, slot encore l'ancien item côté serveur). Après timeout 20 ticks, abort ⇒ le shulker a été déplacé (swap slot 0 / slot d'origine) mais jamais placé, jamais ramassé ⇒ l'état inventaire est incohérent pour la suite.
- **Scénario reproducteur** :
  1. 2b2t TPS bas (5 TPS), bot tente resupply.
  2. Timeout abort, shulker est maintenant en slot 0 de la hotbar au lieu de sa position d'origine.
  3. Prochain cycle de vol : bot "utilise" le shulker comme firework (not an issue: `is(FIREWORK_ROCKET)` filtre), mais le bot considère qu'il lui reste `N` shulkers alors qu'il est déjà déplacé.
- **Preuve / indice** : Le slot swap (`swapSlots(shulkerSlot, 36)`) n'est jamais annulé à l'abort. Voir `abort()` ligne 266-274 qui ne fait que fermer la GUI.
- **Correctif esquissé** : Stocker `originalShulkerSlot` avant `swapSlots`, et dans `abort()` re-swap si `state >= PLACING && state < COLLECTING`.

### BUG-012 — BaseLogger.writeToFile ouvre-ferme fichier à chaque ligne
- **Sévérité** : S3 (performance, pas un leak)
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/BaseLogger.java:220-227`
- **Hypothèse** : Chaque `logBase` (potentiellement 10+/sec sur un scan dense) ouvre+écrit+ferme le fichier. En plus, le `catch (IOException e) { // Silent fail }` supprime toute erreur disque.
- **Correctif esquissé** : Bufferiser via un `BufferedWriter` maintenu ouvert, flushé toutes les N secondes ; ou au minimum loguer via LOGGER.

### BUG-013 — PortalHunterModule : persistance visited_portals.json sans compaction
- **Sévérité** : S3 (croissance disque lente)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/PortalHunterModule.java:1310-1317`
- **Hypothèse** : `markPortalVisited` append + save, mais pas de purge. Après 10000+ portails visités (weeks of 24/7), le JSON pèse plusieurs MB et `isPortalVisited()` devient O(n) linéaire sur chaque scan.
- **Correctif esquissé** : Utiliser un `HashSet<Long>` basé sur `portalKey` pour lookup O(1), ou spatial hash par chunk.

---

## 4. DataComponents 1.20.5+

### BUG-014 — Fireworks utilisés sans vérifier FLIGHT_DURATION du stack
- **Sévérité** : S1 (decrément elytra rapide + possibles flights 1-duration = dégâts)
- **Fichier:ligne** : `src/main/java/com/basefinder/elytra/ElytraBot.java:1198-1233`, `findFireworkInHotbar/Inventory` lignes 1235-1255
- **Hypothèse** : `findFireworkInHotbar()` retourne le premier stack `is(Items.FIREWORK_ROCKET)` SANS lire `DataComponents.FIREWORKS.flightDuration`. Sur 2b2t, les fireworks `flight:1` (donnés par spawners, drops) coexistent avec `flight:3`. Un firework `flight:1` donne à peine un boost, fait consommer une durabilité elytra, et le bot peut stall dès le deploy en takeoff.
- **Scénario reproducteur** :
  1. Joueur ramasse des firework `flight:1` (fausse promesse).
  2. ElytraBot lance un `flight:1` au takeoff.
  3. Le boost est trop court pour établir le vol ⇒ player retombe ⇒ 5 tentatives ⇒ `FlightPhase.IDLE`.
- **Preuve / indice** : Commit `a19931a` mentionne "fireworks DataComponents" fix mais la traque par FLIGHT_DURATION n'a jamais été implémentée. Aucun accès à `DataComponents.FIREWORKS` dans tout le repo (`Grep` confirmé).
- **Correctif esquissé** :
  ```java
  ItemStack s = ...;
  net.minecraft.world.item.component.Fireworks fw = s.get(DataComponents.FIREWORKS);
  if (fw == null || fw.flightDuration() < 2) continue; // prefer flight 2-3
  ```

### BUG-015 — ShulkerBox avec fireworks détecté via CONTAINER mais findShulker ne re-check après placement
- **Sévérité** : S2 (transferFireworks peut tourner à vide)
- **Fichier:ligne** : `src/main/java/com/basefinder/survival/FireworkResupply.java:287-303` puis `194-223`
- **Hypothèse** : `findShulkerWithFireworks` lit `ItemContainerContents contents` (OK — DataComponents 1.21+). Mais une fois le shulker placé + ouvert, le code itère les slots du `containerMenu` par position (0..26) et shift-clique tout slot qui est un firework. Si le shulker contient d'autres items en plus des fireworks, seuls les fireworks sont transférés : correct. En revanche **le shulker avec DataComponents.CONTAINER vide mais ayant contenu des fireworks au load du chunk** passerait `nonEmptyItems()` à vide, donc pas de problème. OK : moins problématique qu'il n'y paraît.
- **Preuve / indice** : La lecture DataComponents est correcte. Seul risque : `DataComponents.CONTAINER` retourne un snapshot du serveur au moment où l'item a été donné au client. Si le shulker a été modifié dans un autre écran sans refresh, la prédiction est fausse. Mineur.

### BUG-016 — AutoMending.hasMending utilise DataComponents.ENCHANTMENTS mais mc.level.registryAccess() peut être null
- **Sévérité** : S2 (NPE au load de monde)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/AutoMendingModule.java:494-506`
- **Hypothèse** : `mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)` — `mc.level` est le client level. Pendant un respawn ou une transition, `mc.level` peut être non-null mais `registryAccess()` peut retourner une registry partielle. Le `try/catch (Exception e)` catche tout et retourne `false` → tous les mending elytras sont invisibles pendant la fenêtre de transition.
- **Scénario reproducteur** : Enable AutoMending pile au changement de dimension → `hasMending` retourne `false` partout → "No damaged Mending elytras found" → toggle off.
- **Correctif esquissé** : Lazy-cache le `Holder<Enchantment>` de MENDING au premier tick où `registryAccess()` est valide, plutôt que de relookuper à chaque item.

---

## 5. Reflection Baritone (fragilité)

### BUG-017 — BaritoneController.setBaritoneSettingBool utilise getSuperclass() sans null-check
- **Sévérité** : S1 (NPE sur une config mal-nommée → plante configureForFastLanding)
- **Fichier:ligne** : `src/main/java/com/basefinder/util/BaritoneController.java:419-441`
- **Hypothèse** :
  ```java
  Field valueField = setting.getClass().getSuperclass().getDeclaredField("value");
  ```
  Si le setting est déjà typé au niveau direct (pas de superclass), ou si Baritone change sa hiérarchie (settings héritent maintenant de `Object` direct), `getSuperclass()` peut pointer vers `Object` qui n'a pas de field `value` → `NoSuchFieldException` → catch Exception → setting silencieusement ignoré. L'utilisateur croit que `allowParkour=true` est actif alors qu'il ne l'est pas.
- **Preuve / indice** : Le catch log juste en `LOGGER.debug(...)` qui n'apparaît jamais en prod (default level INFO).
- **Correctif esquissé** : Remplacer `debug` par `warn` ; parcourir la hiérarchie (`Class<?> c = setting.getClass(); while (c != null) { try { return c.getDeclaredField("value"); } catch (...) { c = c.getSuperclass(); } }`).

### BUG-018 — elytraAvailable detecté via méthode presence sans appel réel
- **Sévérité** : S2 (faux positif si getElytraProcess existe mais retourne null)
- **Fichier:ligne** : `src/main/java/com/basefinder/util/BaritoneController.java:51-59`
- **Hypothèse** : `getClass().getMethod("getElytraProcess")` vérifie juste que la méthode existe, pas qu'elle renvoie non-null. Sur une build Baritone sans nether-pathfinder (mais interface héritée), la méthode existe mais retourne null → `elytraAvailable = true` puis `elytraTo()` silent-fail.
- **Preuve / indice** : L'appel `elytraProcess == null` est bien checké dans `elytraTo` (ligne 331) — donc pas de NPE, mais `isElytraAvailable()` ment. `PortalHunterModule` affiche "Elytra: OUI" alors que ça ne marchera jamais.
- **Correctif esquissé** : À l'init, invoquer réellement `getElytraProcess()` et checker non-null.

### BUG-019 — cancelElytra() fallback cancelAll() peut tuer un autre processus Baritone actif
- **Sévérité** : S2 (Baritone killé en pleine navigation)
- **Fichier:ligne** : `src/main/java/com/basefinder/util/BaritoneController.java:383-397`
- **Hypothèse** : Si `elytraProcess.cancel()` n'existe pas (build obfuscated), le fallback appelle `cancelAll()` qui stoppe TOUT, y compris une `goToXZ` en cours déclenchée juste avant par un autre module. Voir commit `2bb8a81` "fix: cancelAll spam" — prouve que c'est déjà un problème vécu.
- **Scénario reproducteur** : PortalHunter `cancelElytra()` juste avant `goToXZ(currentPortalNether)` → cancelAll tue le goToXZ.
- **Preuve / indice** :
  ```java
  // PortalHunterModule.java:533-534
  baritone.cancelElytra();
  baritone.cancelAll();  // redondant avec la branche fallback
  ```
- **Correctif esquissé** : Utiliser l'API `IBaritoneProcess.onLostControl()` par réflexion si disponible, sinon maintenir un flag "was elytra active" et n'appeler cancelAll que si oui.

---

## 6. State reset incorrect après stop()/disable()

### BUG-020 — ElytraBot.stop() ne reset PAS tous les champs (pendingInventorySwap, stallRecoveryTicks, previousSlotBeforeFirework, lastPosition, stuckTimer, tickCounter)
- **Sévérité** : S1 (un re-enable part avec état zombie)
- **Fichier:ligne** : `src/main/java/com/basefinder/elytra/ElytraBot.java:263-280`
- **Hypothèse** : Plusieurs champs internes restent à leur ancienne valeur après `stop()`. En particulier :
  - `pendingInventorySwap` (true → swap refait au tick suivant sans contexte)
  - `previousSlotBeforeFirework` (peut pointer vers un slot invalide post-dim-change)
  - `lastPosition` (trigger stuck immédiat après re-enable si player a tp)
  - `tickCounter`, `fireworkCooldown`
- **Scénario reproducteur** :
  1. ElytraBot en vol, `pendingFireworkSlot=8`, swap en cours.
  2. PortalHunter dim change → `elytraBot.stop()`.
  3. Au tick suivant (overworld), `onUpdate` appelle `elytraBot.startFlight()` ; `pendingFireworkSlot` est reset à -1 (OK), mais `pendingInventorySwap=true` déjà traité ? Non — `pendingFireworkSlot` est reset mais `pendingInventorySwap` NON. Au prochain `processFireworkUse`, `pendingInventorySwap` sera consommé comme resté actif.
- **Preuve / indice** : `stop()` reset 9 champs ; mais `processFireworkUse` lit `pendingInventorySwap` indépendamment (ligne 1231). Commit `ed34bd0` "ElytraBot takeoff reset" atteste d'un sous-bug de la même famille déjà corrigé.
- **Correctif esquissé** : Centraliser un `resetAllState()` qui remet TOUS les scalaires à leurs defaults, appelé par `stop()` ET par `onDisable()` du module.

### BUG-021 — PortalHunterModule.onDisable : portalQueue/knownPortalKeys/visitedPortals pas effacés
- **Sévérité** : S2 (état persistant au re-enable → portails skippés)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/PortalHunterModule.java:295-312`
- **Hypothèse** : `onDisable` ne clear pas `portalQueue`, `knownPortalKeys`, `sweepWaypoints`, `currentSweepWaypoint`. Le re-enable appelle `onEnable` qui clear explicitement (lignes 255-256) — OK. MAIS entre-temps, si un autre module inspecte l'instance (via `getQueueSize()` exposé au HUD ligne 1530), il voit l'état périmé.
- **Correctif esquissé** : Clear aussi dans `onDisable` par cohérence.

### BUG-022 — AutoMendingModule.onDisable ne reset pas elytrasRepaired/totalElytrasToRepair
- **Sévérité** : S3 (stats stale)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/AutoMendingModule.java:163-174`
- **Hypothèse** : Re-enable hérite des compteurs précédents côté log (pas côté logique, ça c'est reset dans onEnable), mais si l'utilisateur lance `*automending status` entre-temps (hypothétique), il voit les stats de la session précédente.
- **Correctif esquissé** : Reset `elytrasRepaired = 0`, `totalElytrasToRepair = 0` dans `onDisable`.

---

## 7. Gestion d'exceptions silencieuses

### BUG-023 — Cinq `catch (Exception ignored) {}` qui masquent des bugs de registre modules
- **Sévérité** : S2 (bugs silencieux)
- **Fichier:ligne** :
  - `src/main/java/com/basefinder/modules/BaseFinderModule.java:258`
  - `src/main/java/com/basefinder/modules/AutoTravelModule.java:113`
  - `src/main/java/com/basefinder/modules/ElytraBotModule.java:62`
  - `src/main/java/com/basefinder/modules/PortalHunterModule.java:1473`
  - `src/main/java/com/basefinder/util/BaritoneController.java:388`
- **Hypothèse** : Ces catch avalent toute erreur de registre ou d'accès reflection. Exemple `isConflictingModuleActive()` : si l'API RusherHack change et throw, on retourne `false` → on laisse s'activer un module incompatible.
- **Correctif esquissé** : Remplacer par `catch (Exception e) { LOGGER.debug("...", e); }` au minimum, pour repérage.

### BUG-024 — BaseLogger.writeToFile silent fail
- **Sévérité** : S3
- **Fichier:ligne** : `src/main/java/com/basefinder/logger/BaseLogger.java:224-226`
- **Hypothèse** : `catch (IOException e) { // Silent fail }` → disque plein, erreurs droits, fichier verrouillé par antivirus : aucune trace.
- **Correctif esquissé** : Au minimum `LOGGER.warn("[BaseLogger] write failed: {}", e.getMessage())`.

---

## 8. Divers (non classés)

### BUG-025 — BaseFinderModule.onUpdate : appelle lagDetector.tick() mais StateManager.tick() jamais appelé
- **Sévérité** : S3 (tickCounter interne de StateManager gelé, mais shouldAutoSave() utilise `System.currentTimeMillis` donc OK)
- **Fichier:ligne** : `src/main/java/com/basefinder/persistence/StateManager.java:54-63`
- **Hypothèse** : `StateManager.tick()` incrémente `tickCounter` mais n'est jamais appelé par `BaseFinderModule` ni `PortalHunterModule`. Code mort — pas un bug actif mais tromperie : on ne peut pas s'appuyer sur tickCounter pour quoi que ce soit.
- **Correctif esquissé** : Supprimer la méthode ou brancher l'appel dans `onUpdate`.

### BUG-026 — PortalHunterModule : si le portail cible disparaît (griefé) après queue, boucle infinie TRAVELING
- **Sévérité** : S2 (bot stuck à marcher vers un portail inexistant 45s puis skip)
- **Fichier:ligne** : `src/main/java/com/basefinder/modules/PortalHunterModule.java:522-568`
- **Hypothèse** : `currentPortalNether` est la position stockée dans le queue. Si entre le scan et l'arrivée, le portail est cassé (2b2t habituel), le bot arrive à `dist < 5`, appelle `beginEnteringPortal()`, puis timeout 45s avant de skipper. OK fonctionnellement mais potentiellement 45s x N portails fantômes.
- **Scénario reproducteur** : Zone avec portails éphémères (players build-casse).
- **Correctif esquissé** : Avant `beginEnteringPortal`, re-verifier `mc.level.getBlockState(currentPortalNether).getBlock() == Blocks.NETHER_PORTAL` ; sinon, skip immédiat.

---

## Annexe — Bugs déjà fixés (référence git)

| Commit SHA | Bug fixé                                                                 |
|-----------|---------------------------------------------------------------------------|
| `ed34bd0` | DiscordNotifier shutdown, ElytraBot takeoff reset, BaseLogger race, AutoEat key release |
| `a19931a` | dead code, serialization, mutex, fireworks DataComponents (partiel), nav waypoints, log |
| `2bb8a81` | cancelAll spam                                                            |
| `a666fa4` | elytra landing loop + cancelElytra obfuscation fallback                   |
| `c0e7c96` | rotation override causing nosedive                                        |

## Priorisation recommandée

1. **S1 (4 urgences)** : BUG-001 (DiscordNotifier leak PortalHunter), BUG-011 (shulker perdu), BUG-014 (fireworks flight duration), BUG-017 (reflection NPE), BUG-020 (ElytraBot.stop incomplet).
2. **S2 (tests d'intégration)** : BUG-002, BUG-003, BUG-005, BUG-010, BUG-016, BUG-018, BUG-019, BUG-023, BUG-026.
3. **S3 (polish)** : BUG-004, BUG-007, BUG-008, BUG-012, BUG-013, BUG-024, BUG-025.
