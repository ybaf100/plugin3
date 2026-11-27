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
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.entity.FishHook;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 오버월드 0,0 무적지역 + 부술 수 없는 고정 엔더상자.
 * 무적지역 안의 플레이어는 피해를 입지 않음 (공허, /kill 제외). 블록 부수기/설치와 몹 스폰은 허용.
 * 무적지역 안에는 피스톤(끈끈이 포함)을 설치할 수 없고, 밖의 피스톤도 무적지역 안으로 블록을 밀거나 당기지 못함.
 * 무적지역 안의 플레이어는 밀려나지 않음 (넉백, 돌풍구, 폭발, 낚싯대 당기기 등). 스스로 움직이는 것은 그대로.
 * 무적지역 안에 있는 동안 액션바에 "✓ 안전지역 : PVP 제한" 표시 (강화 준비가 끝났으면 옆에 같이 표시), 나갈 때 "✖ 안전지역 : PVP 가능".
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
    /** 지난번에 강화 준비 줄을 보여 준 플레이어 (준비가 끝나면 바로 지움) */
    private final Set<UUID> showingReady = ConcurrentHashMap.newKeySet();
    /** "PVP 가능"을 보여 주는 동안(밀리초) 밖에서는 강화 준비 줄이 덮지 않음. 다시 들어오면 바로 바뀜 */
    private final java.util.Map<UUID, Long> exitShownUntil = new ConcurrentHashMap<>();
    private boolean zoneBar;
    private boolean readyBar;

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
        zoneBar = zoneEnabled && config.getBoolean("safe-zone.action-bar.enabled", true);
        readyBar = config.getBoolean("upgrade-ready-bar.enabled", true);
        migrateActionBarText();
        if (zoneBar || readyBar) {
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
    private static final String OLD_DEFAULT_TEXT = "&a&l안전구역";
    private static final String DEFAULT_TEXT = "&a&l✓ 안전지역 &f: &cPVP &c&l제한";
    private static final String DEFAULT_EXIT_TEXT = "&c&l✖ 안전지역 &f: &aPVP &a&l가능";

    private static final String DEFAULT_SUFFIX = " &7| &e%upgrades%";
    private static final String DEFAULT_READY_TEXT = "&e%upgrades%";

    /** 예전 버전이 자동으로 넣은 기본 문구는 새 기본 문구로 바꿈 (직접 바꾼 문구는 그대로) */
    private void migrateActionBarText() {
        boolean changed = false;
        changed |= migrate("safe-zone.action-bar.text", OLD_DEFAULT_TEXT, DEFAULT_TEXT);
        // "가능"은 이제 항목마다 붙음 ("체력 강화 가능 | 포화 해금 가능")
        changed |= migrate("safe-zone.action-bar.ready-suffix", " &7| &e%upgrades% 가능", DEFAULT_SUFFIX);
        changed |= migrate("upgrade-ready-bar.text", "&e%upgrades% 가능", DEFAULT_READY_TEXT);
        if (changed) plugin.saveConfig();
    }

    private boolean migrate(String path, String oldValue, String newValue) {
        if (!oldValue.equals(plugin.getConfig().getString(path))) return false;
        plugin.getConfig().set(path, newValue);
        return true;
    }

    /**
     * 상시 액션바 (1초마다):
     * - 무적지역 안: "✓ 안전지역 : PVP 제한" (+ 강화 준비가 끝났으면 " | 체력 강화, 포화 해금 가능")
     * - 무적지역 밖: 강화 준비가 끝난 것이 있으면 "체력 강화, 포화 해금 가능"
     * - 무적지역을 나가는 순간: "✖ 안전지역 : PVP 가능" 한 번
     * 강화 준비 표시는 플레이어가 강화 창에서 끌 수 있음 (readyUpgrades 가 빈 목록을 줌).
     */
    private void showActionBars() {
        for (Player player : plugin.getServer().getOnlinePlayers()) update(player, player.getLocation());
    }

    /** 한 플레이어의 상시 액션바를 지금 위치(where) 기준으로 보냄 */
    private void update(Player player, Location where) {
        var config = plugin.getConfig();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        UUID id = player.getUniqueId();
        boolean inZone = zoneBar && isInZone(where);
        if (!inZone && inside.contains(id)) {
            // 나가는 순간 한 번 "PVP 가능" (다른 안내가 떠 있으면 끝난 뒤에, 잠깐 동안 강화 준비 줄이 덮지 않음)
            if (ActionBars.isHeld(player)) return;
            inside.remove(id);
            String exitText = config.getString("safe-zone.action-bar.exit-text", DEFAULT_EXIT_TEXT);
            if (exitText != null && !exitText.isEmpty()) {
                player.sendActionBar(legacy.deserialize(exitText));
                exitShownUntil.put(id, System.currentTimeMillis() + 2000L);
            } else {
                player.sendActionBar(Component.empty());
            }
            return;
        }
        if (inZone) {
            inside.add(id);
            exitShownUntil.remove(id);
        }
        if (ActionBars.isHeld(player)) return; // 다른 안내 메시지가 떠 있으면 덮지 않음
        Long exitUntil = exitShownUntil.get(id);
        if (exitUntil != null) {
            if (exitUntil > System.currentTimeMillis()) return; // 방금 나감: "PVP 가능"을 잠깐 유지
            exitShownUntil.remove(id);
        }
        List<String> ready = readyBar ? readyUpgrades.apply(player) : List.of();
        String upgrades = String.join(" &7| ", ready); // 강화 가능(금색) | 해금 가능(노란색)
        String line;
        if (inZone) {
            String text = config.getString("safe-zone.action-bar.text", DEFAULT_TEXT);
            line = ready.isEmpty() ? text : text + config.getString("safe-zone.action-bar.ready-suffix", DEFAULT_SUFFIX).replace("%upgrades%", upgrades);
        } else if (!ready.isEmpty()) {
            line = config.getString("upgrade-ready-bar.text", DEFAULT_READY_TEXT).replace("%upgrades%", upgrades);
        } else {
            if (showingReady.remove(id)) player.sendActionBar(Component.empty()); // 준비 표시가 끝나면 바로 지움
            return;
        }
        if (ready.isEmpty()) showingReady.remove(id);
        else showingReady.add(id);
        player.sendActionBar(legacy.deserialize(line));
    }

    /** 무적지역 경계(청크)를 넘는 순간 바로 표시 (1초마다 다시 보내는 것은 화면 유지용) */
    private void checkCrossing(Player player, Location to) {
        if (!zoneBar || to == null) return;
        if (isInZone(to) != inside.contains(player.getUniqueId())) update(player, to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        if (from.getWorld() == to.getWorld() && from.getBlockX() >> 4 == to.getBlockX() >> 4
                && from.getBlockZ() >> 4 == to.getBlockZ() >> 4) return; // 청크가 바뀔 때만 확인
        checkCrossing(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        checkCrossing(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawnBar(PlayerRespawnEvent event) {
        checkCrossing(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
        showingReady.remove(event.getPlayer().getUniqueId());
        exitShownUntil.remove(event.getPlayer().getUniqueId());
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

    // ── 피스톤 금지 ──

    private boolean inZone(Block block) {
        return isInZone(block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type != Material.PISTON && type != Material.STICKY_PISTON) return;
        if (!inZone(event.getBlockPlaced())) return;
        event.setCancelled(true);
        ActionBars.send(event.getPlayer(), LegacyComponentSerializer.legacyAmpersand().deserialize(
                plugin.getConfig().getString("safe-zone.piston-message", "&c안전지역에서는 피스톤을 설치할 수 없습니다")));
    }

    /** 무적지역 안의 피스톤(예전에 놓인 것)이나, 밖에서 무적지역 안으로 미는 피스톤은 작동하지 않음 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        BlockFace face = event.getDirection();
        boolean touches = inZone(event.getBlock()) || inZone(event.getBlock().getRelative(face));
        for (Block moved : event.getBlocks()) {
            if (touches) break;
            touches = inZone(moved) || inZone(moved.getRelative(face));
        }
        if (touches) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        BlockFace face = event.getDirection();
        boolean touches = inZone(event.getBlock());
        for (Block moved : event.getBlocks()) {
            if (touches) break;
            touches = inZone(moved) || inZone(moved.getRelative(face));
        }
        if (touches) event.setCancelled(true);
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
