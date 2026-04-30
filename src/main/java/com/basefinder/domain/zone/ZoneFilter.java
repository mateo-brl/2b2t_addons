package com.basefinder.domain.zone;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * État partagé : la liste des zones actives qu'un utilisateur a définies
 * depuis le dashboard. Mise à jour de façon atomique par le poller HTTP,
 * lue par le scanner pendant les ticks.
 *
 * Sémantique :
 * - Aucune zone (vide) → pas de contrainte, le bot scanne partout.
 * - Au moins une zone {@code active} pour la dimension courante →
 *   {@link #allows(String, int, int)} ne retourne {@code true} que pour
 *   les chunks dont le centre est dans une de ces zones.
 *
 * Cette politique "AT LEAST ONE" implique que créer une zone restreint
 * immédiatement le bot ; supprimer toutes les zones le re-libère.
 */
public final class ZoneFilter {

    private final AtomicReference<List<SearchZone>> zones =
            new AtomicReference<>(Collections.emptyList());

    public void setZones(List<SearchZone> zones) {
        this.zones.set(List.copyOf(zones));
    }

    public List<SearchZone> getZones() {
        return zones.get();
    }

    /**
     * Vérifie si le centre du chunk donné est dans une zone active pour la
     * dimension courante. Si aucune zone n'est définie pour cette
     * dimension, la méthode retourne {@code true} (pas de contrainte).
     */
    public boolean allows(String dimension, int chunkX, int chunkZ) {
        List<SearchZone> all = zones.get();
        if (all.isEmpty()) return true;
        boolean anyForDim = false;
        int wx = (chunkX << 4) + 8;
        int wz = (chunkZ << 4) + 8;
        for (SearchZone z : all) {
            if (!z.active) continue;
            if (!z.dimension.equals(dimension)) continue;
            anyForDim = true;
            if (z.contains(wx, wz)) return true;
        }
        return !anyForDim;
    }

    public boolean hasActiveZonesFor(String dimension) {
        for (SearchZone z : zones.get()) {
            if (z.active && z.dimension.equals(dimension)) return true;
        }
        return false;
    }

    /**
     * Bounding box d'union des zones actives pour la dimension donnée, en
     * world blocks {@code [minX, maxX, minZ, maxZ]}. Retourne {@code null}
     * si aucune zone active.
     */
    public int[] unionBoundingBox(String dimension) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (SearchZone z : zones.get()) {
            if (!z.active || !z.dimension.equals(dimension)) continue;
            int[] b = z.boundingBox();
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[1]);
            minZ = Math.min(minZ, b[2]);
            maxZ = Math.max(maxZ, b[3]);
            any = true;
        }
        return any ? new int[] { minX, maxX, minZ, maxZ } : null;
    }
}
