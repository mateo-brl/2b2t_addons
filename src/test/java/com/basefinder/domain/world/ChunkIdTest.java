package com.basefinder.domain.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests purs de {@link ChunkId} : pack/unpack symétriques, distinction de
 * dimension, valeurs négatives correctement encodées.
 */
class ChunkIdTest {

    @Test
    void pack_then_unpack_isIdentity_positive() {
        long key = ChunkId.pack(123, 456);
        assertEquals(123, ChunkId.unpackX(key));
        assertEquals(456, ChunkId.unpackZ(key));
    }

    @Test
    void pack_then_unpack_isIdentity_negative() {
        // 2b2t va loin en X/Z négatifs (highway W/S)
        long key = ChunkId.pack(-1_500_000, -2_750_000);
        assertEquals(-1_500_000, ChunkId.unpackX(key));
        assertEquals(-2_750_000, ChunkId.unpackZ(key));
    }

    @Test
    void pack_handlesIntBoundaries() {
        long minKey = ChunkId.pack(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, ChunkId.unpackX(minKey));
        assertEquals(Integer.MAX_VALUE, ChunkId.unpackZ(minKey));

        long maxKey = ChunkId.pack(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, ChunkId.unpackX(maxKey));
        assertEquals(Integer.MIN_VALUE, ChunkId.unpackZ(maxKey));
    }

    @Test
    void differentDimensions_haveDistinctEqualsHashCode() {
        ChunkId ow = new ChunkId(10, 20, Dimension.OVERWORLD);
        ChunkId nether = new ChunkId(10, 20, Dimension.NETHER);
        assertNotEquals(ow, nether);
        assertNotEquals(ow.hashCode(), nether.hashCode());
    }

    @Test
    void packed_isStablePerCoordinatesIgnoringDimension() {
        // packed() encode (x, z) seulement — la dim doit être traquée séparément
        ChunkId ow = new ChunkId(5, 7, Dimension.OVERWORLD);
        ChunkId nether = new ChunkId(5, 7, Dimension.NETHER);
        assertEquals(ow.packed(), nether.packed());
    }

    @Test
    void blockMin_andMiddleBlock_areCorrectFor16x16() {
        ChunkId id = new ChunkId(3, -2, Dimension.OVERWORLD);
        assertEquals(48, id.blockMinX());
        assertEquals(-32, id.blockMinZ());
        assertEquals(56, id.middleBlockX());
        assertEquals(-24, id.middleBlockZ());
    }
}
