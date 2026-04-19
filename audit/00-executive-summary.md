# 00 — Executive Summary

_Principal Engineer review. 2026-04-19, HEAD `7d41d32` (branche `audit/principal-engineer-review`)._
_Inputs : audits 01→04 (état actuel), 05 + ADR 0001 (architecture cible), 06 (scale plan), 07-09 (plateforme)._

## Diagnostic

BaseFinder est **un projet techniquement impressionnant qui a dépassé sa capacité d'organisation**. ~13 kLOC Java, 6 modules RusherHack fonctionnels, persistence compacte (8 bytes/chunk), AutoTravel Nether. Et pourtant, tu tournes en rond. Les symptômes que j'observe convergent : historique git saturé de commits `fix:`, pas un seul test unitaire, godclasses à 1 400+ lignes, bugs S1 découverts en production chaque semaine, et une vision dashboard web / multi-bot qui reste bloquée depuis plusieurs mois malgré des features empilées.

## 3 causes racine (pas 10)

1. **Pas de domaine pur.** 447 accès directs à `Minecraft.getInstance()` dans 25 fichiers, aucune couche anti-corruption. Impossible de tester quoi que ce soit sans lancer le client MC. Chaque fix est donc validé *en live sur 2b2t*, ce qui produit mécaniquement le cycle fix-bug-fix-bug. Tant que tu ne peux pas écrire `assertEquals(expectedPitch, plan.optimalPitch())` en JUnit, la régression est la seule ressource d'apprentissage. (Ref : audit 01 §5.2, 04 §3.2.)

2. **Pas de contrats d'événements.** Le `BaseFinderHud` tire 20 getters depuis `BaseFinderModule` via `getFeature("BaseHunter")` + résolution par string, et le module expose 13 getters juste pour le HUD. **C'est ce couplage qui bloque concrètement la vision dashboard web.** Tant que l'état bot n'est pas un DTO immuable sérialisable, le multi-bot est impossible à câbler et le dashboard reste une carotte. (Ref : audit 01 §3, 05 §3.)

3. **Multi-instanciation silencieuse de services lourds.** 4× `ElytraBot`, 2× `ChunkScanner`, 2× `BaseLogger` (**qui écrivent sur le même fichier `rusherhack/basefinder/bases.log` depuis deux threads différents** — c'est probablement la source d'une partie des commits "race fix" que tu as déjà appliqués). Protection actuelle : 4 copies manuelles d'une liste en dur `isElytraBotInUse()`. Sans composition root, chaque nouveau module doublera cette dette. (Ref : audit 01 §6, 05 §2.)

## 3 décisions à prendre maintenant

| # | Décision | Recommandation | Irréversible ? |
|--:|---|---|---|
| 1 | **Adopter ADR 0001 (Ports & Adapters + event-driven).** Accepter que le plugin passe de "feature-first" à "domain + adapters", quitte à casser la compat interne. | OUI, c'est la voie de sortie. Sans ça, pas de tests, pas de dashboard, pas de multi-bot. | Non (on peut arrêter après Jalon 1 si ça coince) |
| 2 | **Cible MVP scale = zone 1024² overworld, pas 2b2t-full.** Valider le trajet complet bot→backend→dashboard sur 4 M chunks (stash belt T1) avant de rêver les 21 M de la vision T2. | OUI. 14 mois solo pour T2 vs 12 semaines solo pour T1 — on livre T1 d'abord, on itère. | Non |
| 3 | **Backend en Kotlin monolithe + Postgres, pas microservices.** 1 repo backend, 1 binaire, CX11 Hetzner 5 €/mois, module Gradle `contracts/` partagé bot↔backend. | OUI. Toute autre voie est un luxe que 10h/sem ne paie pas. | Partiellement (splittable plus tard) |

## 4 jalons (détail : `audit/10-roadmap.md`)

| # | Nom | Durée | Effort (h) | Sortie observable |
|--:|---|---|---|---|
| 0 | **Stabilisation** | 1-2 sem | 12-18 | 5 bugs S1 fixés, CI avec tests JUnit verts, ADR 0001 mergé, `PlanFlightTickUseCase` extrait |
| 1 | **Architecture en couches** | 3-4 sem | 30-40 | Étapes migration 2→4 : composition root, `ChunkSource` + `McWorldAdapter`, `BaseFinderViewModel` immuable |
| 2 | **Ingest plateforme** | 2-3 sem | 25-30 | `TelemetrySink` + backend Phase 0 (Kotlin/Ktor) + dashboard Fleet Overview read-only. Bot live visible. |
| 3 | **Scan multi-bot à l'échelle** | 4-6 sem | 40-60 | fastutil + async IO + `WebSocketSink` + shard claim + 3 bots qui se partagent T1 |

**Total ≈ 14-20 semaines calendaires** à 10 h/sem = **Q3 2026 pour la démo multi-bot sur T1**.

## Ce que je livre aujourd'hui

- 10 documents d'audit (`audit/00`→`10`) + 1 ADR (`docs/adr/0001-layered-architecture.md`)
- **Patch démonstratif** sur branche `refactor/extract-flight-controller` (voir Jalon 0) : extraction `PlanFlightTickUseCase` + 3 tests JUnit qui tournent sans Minecraft

## Questions ouvertes (que toi seul peux trancher)

1. **Dashboard : privé ou public ?** Scanner les bases d'autres joueurs est légal, mais publier le dashboard en clair transforme BaseFinder en outil de griefing de masse. Option recommandée : code GPL-3.0 **public**, dashboard **privé derrière Discord OAuth** (cf. audit 06 §10.3).
2. **Multi-compte : quel mix ?** Cible 3 bots V1, 10 bots V2. Vrais alts payants ou bots offline-mode ? 2b2t = online-mode only donc 10 × 30 € = 300 € CapEx si vrais alts. À arbitrer.
3. **Re-scan TTL.** Une base peut apparaître/disparaître. Re-scan mensuel ? Trimestriel ? Event-driven (si un bot repasse à proximité) ? Impact sur les chiffres de scale-plan.
4. **Priorité Jalon 0 vs Jalon 2.** Si tu veux absolument voir un dashboard tourner vite (motivation), on peut inverser partiellement : après Jalon 0, sauter aux étapes 5-7 sur Jalon 2 et revenir au Jalon 1 ensuite. Moins propre techniquement mais plus motivant. À toi de dire.

## Démarrage lundi

**Une seule chose** : merger la branche `refactor/extract-flight-controller`. C'est 8 heures de travail, ça crée le dossier `src/test/`, ça prouve que `domain/` peut exister sans casser le build, ça donne 3 tests verts. Tout le reste de la roadmap découle de ça.

> Si tu passes 8 h cette semaine à cette seule extraction, tu auras plus avancé que les 3 derniers mois.
