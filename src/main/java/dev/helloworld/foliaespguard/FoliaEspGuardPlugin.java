package dev.helloworld.foliaespguard;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerChunkLoadEvent;
import org.bukkit.event.player.PlayerChunkUnloadEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FoliaEspGuardPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerVisionData> playerData = new HashMap<>();

    private int radiusChunks;
    private Material maskMaterial;
    private boolean hideAllBlockEntities;
    private boolean hideStorageBlocks;
    private Set<Material> extraHiddenBlocks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("FoliaEspGuard enabled.");
    }

    @Override
    public void onDisable() {
        playerData.clear();
    }

    private void reloadPluginConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        radiusChunks = Math.max(0, config.getInt("radius-chunks", 2));
        maskMaterial = parseMaterial(config.getString("mask-material", "STONE"), Material.STONE);
        hideAllBlockEntities = config.getBoolean("hide-all-block-entities", true);
        hideStorageBlocks = config.getBoolean("hide-storage-blocks", true);

        extraHiddenBlocks = new HashSet<>();
        for (String name : config.getStringList("extra-blocks")) {
            Material material = parseMaterial(name, null);
            if (material != null && material.isBlock()) {
                extraHiddenBlocks.add(material);
            }
        }

        getLogger().info("Loaded config: radius=" + radiusChunks + " chunks, extra blocks=" + extraHiddenBlocks.size());
    }

    private Material parseMaterial(String value, Material fallback) {
        if (value == null) {
            return fallback;
        }

        Material parsed = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
        return parsed != null ? parsed : fallback;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerData.put(event.getPlayer().getUniqueId(), new PlayerVisionData());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerData.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        playerData.put(event.getPlayer().getUniqueId(), new PlayerVisionData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        PlayerVisionData data = playerData.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVisionData());

        Chunk chunk = event.getChunk();
        Set<BlockPos> chunkSensitiveBlocks = scanSensitiveBlocks(chunk);
        data.loadedChunkBlocks.put(new ChunkPos(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()), chunkSensitiveBlocks);

        for (BlockPos blockPos : chunkSensitiveBlocks) {
            evaluateBlock(player, data, blockPos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        Player player = event.getPlayer();
        PlayerVisionData data = playerData.get(player.getUniqueId());
        if (data == null) {
            return;
        }

        Chunk chunk = event.getChunk();
        ChunkPos chunkPos = new ChunkPos(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Set<BlockPos> removed = data.loadedChunkBlocks.remove(chunkPos);
        if (removed == null || removed.isEmpty()) {
            return;
        }

        data.hiddenBlocks.removeAll(removed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }

        if (!Objects.equals(from.getWorld(), to.getWorld())) {
            return;
        }

        if (from.getChunk().getX() == to.getChunk().getX() && from.getChunk().getZ() == to.getChunk().getZ()) {
            return;
        }

        Player player = event.getPlayer();
        PlayerVisionData data = playerData.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVisionData());
        reevaluateAllKnownBlocks(player, data);
    }

    private Set<BlockPos> scanSensitiveBlocks(Chunk chunk) {
        Set<BlockPos> sensitiveBlocks = new HashSet<>();

        for (BlockState tile : chunk.getTileEntities()) {
            if (hideAllBlockEntities || (hideStorageBlocks && tile instanceof InventoryHolder)) {
                sensitiveBlocks.add(BlockPos.from(tile.getBlock()));
            }
        }

        if (extraHiddenBlocks.isEmpty()) {
            return sensitiveBlocks;
        }

        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int y = minY; y < maxY; y++) {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    Block block = world.getBlockAt(baseX + localX, y, baseZ + localZ);
                    if (extraHiddenBlocks.contains(block.getType())) {
                        sensitiveBlocks.add(BlockPos.from(block));
                    }
                }
            }
        }

        return sensitiveBlocks;
    }

    private void reevaluateAllKnownBlocks(Player player, PlayerVisionData data) {
        List<BlockPos> allPositions = new ArrayList<>();
        for (Set<BlockPos> set : data.loadedChunkBlocks.values()) {
            allPositions.addAll(set);
        }

        for (BlockPos blockPos : allPositions) {
            evaluateBlock(player, data, blockPos);
        }
    }

    private void evaluateBlock(Player player, PlayerVisionData data, BlockPos blockPos) {
        boolean shouldHide = shouldHideFromPlayer(player, blockPos);
        boolean isHidden = data.hiddenBlocks.contains(blockPos);

        if (shouldHide && !isHidden) {
            sendMaskedBlock(player, blockPos);
            data.hiddenBlocks.add(blockPos);
            return;
        }

        if (!shouldHide && isHidden) {
            sendRealBlock(player, blockPos);
            data.hiddenBlocks.remove(blockPos);
        }
    }

    private boolean shouldHideFromPlayer(Player player, BlockPos blockPos) {
        World world = player.getWorld();
        if (!world.getUID().equals(blockPos.worldId())) {
            return false;
        }

        int playerChunkX = player.getLocation().getChunk().getX();
        int playerChunkZ = player.getLocation().getChunk().getZ();

        int blockChunkX = blockPos.x() >> 4;
        int blockChunkZ = blockPos.z() >> 4;

        return Math.max(Math.abs(playerChunkX - blockChunkX), Math.abs(playerChunkZ - blockChunkZ)) > radiusChunks;
    }

    private void sendMaskedBlock(Player player, BlockPos blockPos) {
        World world = Bukkit.getWorld(blockPos.worldId());
        if (world == null) {
            return;
        }

        Location location = new Location(world, blockPos.x(), blockPos.y(), blockPos.z());
        player.sendBlockChange(location, maskMaterial.createBlockData());
    }

    private void sendRealBlock(Player player, BlockPos blockPos) {
        World world = Bukkit.getWorld(blockPos.worldId());
        if (world == null) {
            return;
        }

        Block block = world.getBlockAt(blockPos.x(), blockPos.y(), blockPos.z());
        player.sendBlockChange(block.getLocation(), block.getBlockData());
    }

    private static final class PlayerVisionData {
        private final Map<ChunkPos, Set<BlockPos>> loadedChunkBlocks = new HashMap<>();
        private final Set<BlockPos> hiddenBlocks = new HashSet<>();
    }
}
