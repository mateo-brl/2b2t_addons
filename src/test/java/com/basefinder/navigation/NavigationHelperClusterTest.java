package com.basefinder.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests pour {@link NavigationHelper#insertClusterWaypoints}.
 *
 * On évite d'instancier MC pour ces tests : on appelle uniquement les
 * méthodes pures qui ne dépendent pas de {@code Minecraft.getInstance()}.
 * Le NavigationHelper construit son champ {@code mc} de façon lazy mais
 * les méthodes testées ici n'y touchent pas.
 */
class NavigationHelperClusterTest {

    @Test
    void insertClusterWaypoints_addsRingsAroundBase() {
        NavigationHelper nav = new NavigationHelper();
        BlockPos base = new BlockPos(1000, 64, 2000);

        int added = nav.insertClusterWaypoints(base, 2000, 800);

        assertTrue(added > 0, "Au moins un waypoint doit être ajouté");
        assertTrue(added < 30, "Pas plus que les rings du carré 2000 blocs");
    }

    @Test
    void insertClusterWaypoints_dedupSameClusterCell() {
        NavigationHelper nav = new NavigationHelper();
        BlockPos a = new BlockPos(1000, 64, 2000);
        BlockPos b = new BlockPos(1500, 64, 2200); // même cell 2k

        int first = nav.insertClusterWaypoints(a, 2000, 800);
        int second = nav.insertClusterWaypoints(b, 2000, 800);

        assertTrue(first > 0);
        assertEquals(0, second, "Cluster déjà investigué dans la même cell 2k → 0 ajouts");
        assertTrue(nav.isClusterInvestigated(a));
        assertTrue(nav.isClusterInvestigated(b));
    }

    @Test
    void insertClusterWaypoints_distantBaseIsNewCluster() {
        NavigationHelper nav = new NavigationHelper();
        BlockPos a = new BlockPos(1000, 64, 2000);
        BlockPos far = new BlockPos(50_000, 64, 50_000); // cell différente

        int first = nav.insertClusterWaypoints(a, 2000, 800);
        int second = nav.insertClusterWaypoints(far, 2000, 800);

        assertTrue(first > 0);
        assertTrue(second > 0, "Cluster éloigné = nouvelle cell = nouveau cluster");
    }

    @Test
    void insertClusterWaypoints_resetClearsDedup() {
        NavigationHelper nav = new NavigationHelper();
        BlockPos base = new BlockPos(1000, 64, 2000);

        int first = nav.insertClusterWaypoints(base, 2000, 800);
        nav.initializeSearch(NavigationHelper.SearchPattern.CUSTOM, base);
        int second = nav.insertClusterWaypoints(base, 2000, 800);

        assertTrue(first > 0);
        assertTrue(second > 0, "Après initializeSearch, le dédup doit être cleared");
        assertFalse(first == 0);
    }
}
