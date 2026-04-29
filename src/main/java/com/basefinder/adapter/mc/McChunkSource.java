package com.basefinder.adapter.mc;

import com.basefinder.application.scan.ChunkSource;
import com.basefinder.domain.scan.ChunkCounts;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Optional;

/**
 * Implémentation MC du port {@link ChunkSource}.
 *
 * Lit un {@link LevelChunk} déjà résident côté client et retourne ses
 * {@link ChunkCounts} purs (sans aucune dépendance MC dans le résultat).
 * Non-bloquant : ne déclenche jamais de chargement réseau (param {@code load=false}).
 *
 * <p>Sémantique :
 * <ul>
 *   <li>{@link Optional#empty()} si la dimension demandée ne correspond pas
 *       au level courant du client (ex: demande OW alors que le joueur est au Nether) ;</li>
 *   <li>{@link Optional#empty()} si le chunk n'est pas chargé localement ;</li>
 *   <li>sinon les comptes produits par {@link BlockAnalyzer#analyzeChunk}.</li>
 * </ul>
 *
 * <p>Audit/05 §5 étape 3 : "ChunkSource mockable. Fixe BUG-004 (snapshot)." Cette
 * implémentation n'est pas encore consommée par le hot path (qui appelle
 * {@code BlockAnalyzer.analyzeChunk} directement) — elle prépare le terrain
 * pour le Jalon 2 backend remote où {@code ChunkScannerService} pourra basculer
 * entre une source locale ({@code McChunkSource}) et une source NDJSON replay.
 */
public final class McChunkSource implements ChunkSource {

    private final Minecraft mc;

    public McChunkSource() {
        this(Minecraft.getInstance());
    }

    public McChunkSource(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public Optional<ChunkCounts> countsFor(ChunkId id) {
        if (mc == null || mc.level == null) {
            return Optional.empty();
        }

        Level level = mc.level;
        if (toDomainDimension(level.dimension()) != id.dim()) {
            return Optional.empty();
        }

        LevelChunk chunk = level.getChunkSource().getChunk(id.x(), id.z(), false);
        if (chunk == null) {
            return Optional.empty();
        }

        ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(level, chunk);
        return Optional.ofNullable(analysis.getCounts());
    }

    private static Dimension toDomainDimension(ResourceKey<Level> key) {
        if (key == Level.END) return Dimension.END;
        if (key == Level.NETHER) return Dimension.NETHER;
        return Dimension.OVERWORLD;
    }
}
