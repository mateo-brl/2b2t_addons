package com.basefinder.scanner;

import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Scans entities in loaded chunks to detect player activity.
 *
 * Entity indicators of bases:
 * - Item frames: often near bases (maps, decorations)
 * - Armor stands: decorative / storage
 * - Minecarts with chests/hoppers: redstone machines or transport
 * - Animals in small areas: farms (pens, breeding)
 * - Villagers far from villages: trading halls
 */
public class EntityScanner {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EntityScanner");
    private final Minecraft mc = Minecraft.getInstance();

    /**
     * Scan entities in a chunk area and add entity-based scores to the analysis.
     * This is additive - it only increases scores, never decreases them.
     *
     * @param analysis the existing chunk analysis to enhance
     */
    public void scanEntities(ChunkAnalysis analysis) {
        if (mc.level == null) return;

        ChunkPos chunkPos = analysis.getChunkPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();

        // Get all entities in this chunk's area
        AABB chunkBox = new AABB(minX, mc.level.getMinY(), minZ,
                minX + 16, mc.level.getMaxY(), minZ + 16);

        List<Entity> entities;
        try {
            entities = mc.level.getEntities((Entity) null, chunkBox, e -> true);
        } catch (Exception e) {
            return; // Silently fail if entity query fails
        }

        if (entities.isEmpty()) return;

        int itemFrameCount = 0;
        int armorStandCount = 0;
        int chestMinecartCount = 0;
        int hopperMinecartCount = 0;
        int farmAnimalCount = 0;
        int villagerCount = 0;

        for (Entity entity : entities) {
            if (entity instanceof ItemFrame) {
                itemFrameCount++;
            } else if (entity instanceof ArmorStand) {
                armorStandCount++;
            } else if (entity instanceof MinecartChest) {
                chestMinecartCount++;
            } else if (entity instanceof MinecartHopper) {
                hopperMinecartCount++;
            } else if (entity instanceof Villager) {
                villagerCount++;
            } else if (isFarmAnimal(entity)) {
                farmAnimalCount++;
            }
        }

        // Calculate entity score
        double entityScore = 0;

        // Item frames: 3 pts each (maps, decorations = player activity)
        entityScore += itemFrameCount * 3.0;

        // Armor stands: 5 pts each (rarely natural)
        entityScore += armorStandCount * 5.0;

        // Chest minecarts: 8 pts each (storage/transport = strong indicator)
        entityScore += chestMinecartCount * 8.0;

        // Hopper minecarts: 10 pts each (redstone machines)
        entityScore += hopperMinecartCount * 10.0;

        // Villagers: need 2+ in one chunk to be a trading hall (not natural village)
        if (villagerCount >= 2) {
            entityScore += villagerCount * 5.0;
        }

        // Farm animals: need 5+ in one chunk to be a farm (natural spawns are sparse)
        if (farmAnimalCount >= 5) {
            entityScore += farmAnimalCount * 2.0;
        }

        // Only apply if significant
        if (entityScore < 5) return;

        // Add entity data to analysis
        analysis.setEntityScore(entityScore);
        analysis.setEntityCount(itemFrameCount + armorStandCount + chestMinecartCount
                + hopperMinecartCount + villagerCount + farmAnimalCount);

        // Upgrade base type if entity evidence is stronger
        if (chestMinecartCount >= 1 || hopperMinecartCount >= 1) {
            if (analysis.getBaseType() == BaseType.NONE || analysis.getBaseType() == BaseType.TRAIL) {
                analysis.setBaseType(BaseType.STORAGE);
            }
        }
        if (armorStandCount >= 3 || itemFrameCount >= 5) {
            if (analysis.getBaseType() == BaseType.NONE) {
                analysis.setBaseType(BaseType.CONSTRUCTION);
            }
        }
        if (villagerCount >= 2 && analysis.getBaseType() == BaseType.NONE) {
            analysis.setBaseType(BaseType.CONSTRUCTION);
        }

        // Add entity score to total
        analysis.setScore(analysis.getScore() + entityScore);

        if (entityScore >= 10) {
            LOGGER.info("[EntityScanner] Chunk ({}, {}) entity score: {} (frames:{}, stands:{}, carts:{}, villagers:{}, animals:{})",
                    chunkPos.x, chunkPos.z, entityScore, itemFrameCount, armorStandCount,
                    chestMinecartCount + hopperMinecartCount, villagerCount, farmAnimalCount);
        }
    }

    private boolean isFarmAnimal(Entity entity) {
        return entity instanceof Cow
                || entity instanceof Sheep
                || entity instanceof Pig
                || entity instanceof Chicken
                || entity instanceof Horse
                || entity instanceof Donkey
                || entity instanceof Llama;
    }
}
