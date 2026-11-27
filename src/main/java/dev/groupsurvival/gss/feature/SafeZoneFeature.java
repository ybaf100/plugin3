package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.ActionBars;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.entity.FishHook;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 오버월드 0,0 무적지역 + 부술 수 없는 고정 엔더상자.
 * 무적지역 안의 플레이어는 피해를 입지 않음 (공허, /kill 제외). 블록 부수기/설치와 몹 스폰은 허용.
 * 무적지역 안의 플레이어는 밀려나지 않음 (넉백, 돌풍구, 폭발, 낚싯대 당기기 등). 스스로 움직이는 것은 그대로.
 * 무적지역 안에 있는 동안 액션바에 "안전구역" 표시 (강화 준비가 끝났으면 옆에 같이 표시).
 */
public final class SafeZoneFeature implements Listener {

    private final JavaPlugin plugin;
    private final boolean zoneEnabled;
    private final int radiusChunks;
    private Location enderChest;
    /** 강화 준비가 끝난 항목 (예: "체력 강화", "성급함 해금") */
    private Function<Player, List<String>> readyUpgrades = player -> List.of();
    /** 지난번 확인 때 무적지역 안에 있던 플레이어 (나가면 액션바를 바로 지움) */
    private final Set<UUID> inside = ConcurrentHashMap.newKeySet();

    public SafeZoneFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();
        this.zoneEnabled = config.getBoolean("safe-zone.enabled", true);
        this.radiusChunks = Math.max(0, config.getInt("safe-zone.radius-chunks", 1));
    }

    public void setReadyUpgrades(Function<Player, List<String>> readyUpgrades) {
        this.readyUpgrades = readyUpgrades;
    }

    public void start() {
        var config = plugin.getConfig();
        if (zoneEnabled && config.getBoolean("safe-zone.action-bar.enabled", true)) {
            long interval = Math.max(10L, config.getLong("safe-zone.action-bar.interval-ticks", 20L));
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::showActionBars, interval, interval);
        }
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

    // ── 액션바 ──
    private void showActionBars() {
        var config = plugin.getConfig();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        String text = config.getString("safe-zone.action-bar.text", "&a&l안전구역");
        String suffix = config.getString("safe-zone.action-bar.ready-suffix", " &7| &e%upgrades% 가능");
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isInZone(player.getLocation())) {
                if (inside.remove(player.getUniqueId()) && !ActionBars.isHeld(player)) {
                    player.sendActionBar(Component.empty()); // 나가면 바로 지움
                }
                continue;
            }
            inside.add(player.getUniqueId());
            if (ActionBars.isHeld(player)) continue; // 다른 안내 메시지가 떠 있으면 덮지 않음
            List<String> ready = readyUpgrades.apply(player);
            String line = ready.isEmpty() ? text : text + suffix.replace("%upgrades%", String.join(", ", ready));
            player.sendActionBar(legacy.deserialize(line));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
        ActionBars.forget(event.getPlayer());
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

    // ── 밀려남 방지 ──

    /**
     * 서버가 플레이어를 움직이게 하는 모든 경우(넉백, 돌풍구/폭발, 낚싯대 당기기 등)에 발생.
     * 막으면 움직임이 클라이언트에 전달되지 않음.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (isInZone(event.getPlayer().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player && isInZone(player.getLocation())) event.setCancelled(true);
    }

    /** 낚싯대 찌가 무적지역 안의 플레이어에게 걸리지 않음 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHookHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof FishHook && event.getHitEntity() instanceof Player player
                && isInZone(player.getLocation())) event.setCancelled(true);
    }

    /** 이미 걸린 찌로 무적지역 안의 플레이어를 당기지 못함 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getCaught() instanceof Player player && isInZone(player.getLocation())) {
            event.setCancelled(true);
            event.getHook().remove();
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
