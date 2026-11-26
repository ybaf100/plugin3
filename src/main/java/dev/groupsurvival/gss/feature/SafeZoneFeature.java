package dev.groupsurvival.gss.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 오버월드 0,0 무적지역 + 부술 수 없는 고정 엔더상자.
 * 무적지역 안의 플레이어는 피해를 입지 않음 (공허, /kill 제외). 블록 부수기/설치와 몹 스폰은 허용.
 */
public final class SafeZoneFeature implements Listener {

    private final JavaPlugin plugin;
    private final boolean zoneEnabled;
    private final int radiusChunks;
    private Location enderChest;

    public SafeZoneFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();
        this.zoneEnabled = config.getBoolean("safe-zone.enabled", true);
        this.radiusChunks = Math.max(0, config.getInt("safe-zone.radius-chunks", 1));
    }

    public void start() {
        var config = plugin.getConfig();
        if (!config.getBoolean("safe-zone.ender-chest.enabled", true)) return;
        World world = overworld();
        if (world == null) return;
        int x = config.getInt("safe-zone.ender-chest.x", 0);
        int z = config.getInt("safe-zone.ender-chest.z", 0);
        int y;
        if (config.isInt("safe-zone.ender-chest.y")) {
            y = config.getInt("safe-zone.ender-chest.y");
        } else {
            y = world.getHighestBlockYAt(x, z) + 1;
            config.set("safe-zone.ender-chest.y", y);
            plugin.saveConfig();
        }
        enderChest = new Location(world, x, y, z);
        ensureEnderChest();
        // 명령어 등으로 사라졌을 때 복구
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::ensureEnderChest, 20L * 60, 20L * 60);
    }

    private World overworld() {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) return world;
        }
        return null;
    }

    private void ensureEnderChest() {
        if (enderChest == null || !enderChest.isChunkLoaded()) return;
        Block block = enderChest.getBlock();
        if (block.getType() != Material.ENDER_CHEST) block.setType(Material.ENDER_CHEST);
    }

    private boolean isEnderChest(Block block) {
        return enderChest != null
                && block.getWorld() == enderChest.getWorld()
                && block.getX() == enderChest.getBlockX()
                && block.getY() == enderChest.getBlockY()
                && block.getZ() == enderChest.getBlockZ();
    }

    public boolean isInZone(Location location) {
        if (!zoneEnabled || location.getWorld() == null
                || location.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        return Math.abs(location.getBlockX() >> 4) <= radiusChunks && Math.abs(location.getBlockZ() >> 4) <= radiusChunks;
    }

    // ── 무적 ──
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;
        if (isInZone(player.getLocation())) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (enderChest != null && event.getWorld() == enderChest.getWorld()
                && event.getChunk().getX() == enderChest.getBlockX() >> 4
                && event.getChunk().getZ() == enderChest.getBlockZ() >> 4) {
            plugin.getServer().getScheduler().runTask(plugin, this::ensureEnderChest);
        }
    }

    // ── 엔더상자 보호 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (isEnderChest(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isEnderChest);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isEnderChest);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isEnderChest(event.getBlock())) event.setCancelled(true);
    }
}
