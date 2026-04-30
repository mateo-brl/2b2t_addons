package com.basefinder.domain.event;

/**
 * Événement émis ~1 Hz pendant que le bot est actif. Snapshot agrégé du
 * BaseFinderViewModel — ce qui suffit au dashboard pour suivre le bot live.
 *
 * Champs minimaux v1 (audit/05 §3.2). Plus de champs (yaw/pitch, hunger,
 * absorption, fps client) seront ajoutés quand le dashboard les consommera.
 *
 * Idempotency key = bot session + seq.
 *
 * @param posX            coordonnée bloc X du joueur
 * @param posY            altitude du joueur (-64 à 320 typiquement)
 * @param posZ            coordonnée bloc Z du joueur
 * @param dimension       legacy name : "overworld" / "nether" / "end"
 * @param hp              points de vie (0-20)
 * @param tps             TPS estimés observés côté client
 * @param scannedChunks   compteur cumulé de chunks scannés
 * @param basesFound      compteur cumulé de bases trouvées
 * @param flying          true si le bot est en vol elytra
 * @param flightStateName état du FSM ElytraBot (CRUISING, LANDING, …)
 * @param waypointIndex   index de waypoint courant
 * @param waypointTotal   total de waypoints planifiés
 */
public record BotTick(
        long seq,
        long tsUtcMs,
        int posX,
        int posY,
        int posZ,
        String dimension,
        int hp,
        double tps,
        int scannedChunks,
        int basesFound,
        boolean flying,
        String flightStateName,
        int waypointIndex,
        int waypointTotal
) implements BotEvent {

    @Override
    public String type() {
        return "bot_tick";
    }

    @Override
    public String idempotencyKey() {
        return "tick:" + seq;
    }
}
