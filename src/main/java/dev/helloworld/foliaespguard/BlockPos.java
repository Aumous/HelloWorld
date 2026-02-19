package dev.helloworld.foliaespguard;

import org.bukkit.block.Block;

import java.util.UUID;

public record BlockPos(UUID worldId, int x, int y, int z) {
    public static BlockPos from(Block block) {
        return new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
