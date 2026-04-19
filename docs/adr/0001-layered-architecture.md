# ADR 0001 — Architecture en couches hexagonales pour BaseFinder

## Status

Proposed — 2026-04-19

## Context

BaseFinder est un plugin RusherHack (MC 1.21.4, Java 21, ~12 974 LOC) ciblant 2b2t,
maintenu à 10 h/semaine par un seul développeur. L'audit structurel (`audit/01` à `audit/04`,
HEAD `7d41d32`) a établi un état initial tendu :

- **Godclasses** : `PortalHunterModule` 1558 L, `ElytraBot` 1411 L, `BaseFinderModule` 1177 L,
  `AutoTravelModule` 828 L (`audit/01#3`).
- **Couplage MC omniprésent** : 447 accès `Minecraft.getInstance()` dans 25 fichiers
  (`audit/01#5.2`, `audit/04#3.2`) — 0 test unitaire possible aujourd'hui.
- **Multi-instanciation silencieuse** : 4× `ElytraBot`, 2× `ChunkScanner`, 2× `BaseLogger`
  qui écrivent dans le même fichier (`audit/01#6`).
- **Modèle dégradé** : `BaseRecord` muté après construction, `Dimension` comparé par `String`,
  4 champs flight désynchronisables (`audit/01#4`). Aucun value object protégé.
- **Bugs S1 structurels** : DiscordNotifier leak au disable (`BUG-001`), `ElytraBot.stop()`
  incomplet (`BUG-020`), fireworks `flight:1` non distingués (`BUG-014`), reflection Baritone
  fragile (`BUG-017`) (`audit/02`).
- **Plafonds de perf** : IO save sur game thread (stalls 40-500 ms à 1 M chunks), 1.9 MB/s
  churn GC en vol elytra, `Set<ChunkPos>` consomme 72 B/entrée et bloque la cible 1 M chunks
  (`audit/03#3,#4,#6`).
- **APIs RusherHack sous-utilisées** : `EventLoadWorld`, `EventChunk.Load`, `IRotationManager`,
  `IRenderer3D` jamais utilisés (`audit/04#4`).

Le projet a deux caps à tenir : (a) scanner 2b2t entièrement avec plusieurs bots, (b) exposer
un dashboard web temps réel. Aucun des deux n'est atteignable sans désolidariser la logique
métier du client Minecraft et sans un modèle d'événements sérialisable. Contraintes additionnelles :
10 h/semaine, un seul dev, open-source GPL-3.0, pas de public API externe stable à préserver.

## Decision

Adopter une **architecture Ports & Adapters (hexagonale) event-driven**, organisée en quatre
couches avec une direction de dépendance unique :

```
domain ← application ← adapter ← bootstrap
```

- `domain/` — pur Java (JDK + fastutil), 0 import `net.minecraft.*` ni `dev.rusherhack.*`.
  Héberge les value objects (`ChunkId`, `FlightPlan`, `BaseRecord`, `RoutePattern` sealed,
  `BotEvent` sealed) et les invariants métier.
- `application/` — use cases (`PlanFlightTickUseCase`, `ScanChunkUseCase`, `EmitBaseFoundUseCase`,
  etc.) orchestrant le domaine. Dépend uniquement de `domain` et de ports.
- `adapter/` — anti-corruption layer. Sous-packages `mc/`, `rusherhack/`, `baritone/`, `io/`
  (persistence, discord, telemetry). Seule couche qui importe MC et RH.
- `bootstrap/` — composition root unique. DI manuelle via un `ServiceRegistry`. Une seule
  instance par service. Remplace les 4× `ElytraBot` / 2× `ChunkScanner` / 2× `BaseLogger`.

**Event bus** : on consomme `EventUpdate`, `EventLoadWorld`, `EventChunk.Load` fournis par
RusherHack. On **ne construit pas** un event bus maison. Un `TelemetrySink` (port) reçoit les
`BotEvent` domaine ; un `NdjsonFileSink` (v1) puis `WebSocketSink` (v2) transportent vers
un backend dashboard externe (hors repo BaseFinder).

Migration incrémentale en 8 étapes de ≤10 h chacune (cf `audit/05#5`). L'étape 1 extrait **une
seule** chose (`PlanFlightTickUseCase`) pour valider la méthode avant tout refactor large.

## Consequences

### Positives

1. **Tests JUnit pur domaine possibles** — tout le scoring (`BlockAnalyzer`), la physique
   (`calculateOptimalPitch`), les patterns de routes deviennent testables sans MC. Aujourd'hui :
   0 test. Cible réaliste v1 : 30+ tests couvrant les use cases critiques.
2. **Dashboard web devient trivial** — le `BaseFinderViewModel` produit par le use case est déjà
   le payload de l'event `BotTick`. Le HUD et le WebSocket consomment le même DTO. Plus besoin
   de réécrire la logique métier pour le front.
3. **Multi-bot = N JVMs partageant le modèle** — chaque bot émet ses events vers le backend,
   le backend fait le fan-in. Pas de code partagé autre que le schéma d'events.
4. **Les bugs S1 identifiés sont résolus par design** — `BaseRecord` immuable (fin du timestamp
   perdu), `DiscordNotifier` instance unique (fin du leak PortalHunter), `FlightState` immuable
   + phase typée sealed (fin du state zombie au `stop()`).
5. **L'échelle 1 M chunks devient atteignable** — `Long2ObjectOpenHashMap`, pool de `FlightState`,
   IO async sont des changements localisés aux adapters, sans toucher au domaine.

### Négatives

1. **Coût de migration non trivial** — ~8 étapes × 10 h = ~2 mois réels avant que le plan soit
   significativement déployé. Pendant cette période, code ancien et cible coexistent.
2. **Boilerplate DI manuelle** — pas de Guice / Spring (délibéré : trop lourd pour un plugin
   MC). Le `ServiceRegistry` devra être maintenu à la main. Risque d'oubli au merge.
3. **Ajoute une indirection aux endroits qui marchent déjà** — certaines parties (`AutoMending`,
   `HeightmapCache`) n'ont pas de dette majeure. Les obliger à passer par ports/adapters ajoute
   du code sans gain proportionnel ; on acceptera de les migrer en dernier, voire de laisser
   certaines comme adapters directs MC sans passer par un use case.

### Neutres

1. **Aucune rupture de compat externe** — BaseFinder n'a pas de public API consommée par d'autres
   plugins. Le refactor est interne.
2. **Dépendance fastutil explicite à ajouter** — déjà présent transitivement via MC (`audit/03#7`),
   à passer en `compileOnly` dans `build.gradle`. Risque de conflit de version : faible.

## Alternatives considérées

### A. Garder l'organisation feature-first actuelle (statu quo)

Packages par feature (`elytra/`, `scanner/`, `survival/`, ...) avec couplage MC diffus.
**Rejeté** : aucun des deux caps produit (scan 1 M chunks, dashboard web) n'est atteignable.
Le code ne se teste pas et chaque nouveau feature aggrave les godclasses. Les bugs S1 reviendront
sous d'autres formes parce que l'architecture les autorise.

### B. Microservices / split multi-repo

Un repo "scanner", un repo "flight", un repo "backend dashboard" communiquant par HTTP.
**Rejeté** : overkill pour 1 dev à 10 h/sem. Le bot s'exécute dans un process MC unique — découper
en services n'ajoute que de la latence et de la complexité de déploiement. Le backend dashboard,
lui, vivra dans un repo séparé ; c'est la seule frontière process justifiée.

### C. Full DDD avec Aggregate Roots, CQRS et Event Sourcing

Modélisation riche façon Evans/Vernon : aggregates, repositories, bounded contexts explicites,
event sourcing des findings.
**Rejeté pour v1** : le budget cognitif d'un Aggregate Root complet ne rentre pas dans 10 h/sem.
Les value objects immuables + use cases + events domaine donnent 80 % du bénéfice pour 20 % du
coût. Si le projet tient 12 mois, on pourra promouvoir certains concepts (ex. `ScanSession`
comme aggregate) sans casser la structure.

### D. Garder Minecraft partout mais ajouter une couche `port` par-dessus

Demi-mesure : interfaces Java au-dessus du code existant, sans isoler le domaine.
**Rejeté** : ne résout pas les godclasses, ne rend pas testable, n'aide pas le dashboard. Coût
similaire au refactor complet sans les bénéfices.

## References

- `audit/01-domain-map.md` — carte packages, godclasses, couplages, top 5 extractions.
- `audit/02-bugs-and-risks.md` — BUG-001, BUG-014, BUG-017, BUG-020 S1.
- `audit/03-performance.md` — hot paths, structures fastutil, plafonds 1 M chunks, IO async.
- `audit/04-rusherhack-integration.md` — APIs RH sous-utilisées, reflection Baritone, snapshot
  non épinglée.
- `audit/05-target-architecture.md` — arborescence détaillée, contrats d'événements, chemin
  de migration 8 étapes.
- Alistair Cockburn, *Hexagonal Architecture / Ports and Adapters* (2005).
- Eric Evans, *Domain-Driven Design* (2003) — inspiration, pas application stricte pour v1.
- RusherHack API javadocs — `RusherHackAPI`, `ToggleableModule`, `HudElement`,
  `events.{client,world,render}`.
- 2b2t realities : TPS variable 2-20, queue > 300 joueurs, durée de vol multi-heures attendue.
