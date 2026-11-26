package dev.groupsurvival.gss.feature;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 첫 접속 무작위 스폰 + 영구 개인 스폰.
 * - 처음 접속하는 플레이어는 오버월드 0,0 기준 정사각형(기본 5000x5000) 안의 안전한 무작위 위치에서 바로 스폰
 * - 그 위치가 영구 개인 스폰이 되어, 침대/리스폰 앵커 없이 죽으면 항상 그곳에서 부활
 */
public final class RandomSpawnFeature implements Listener {

    private static final Set<Material> UNSAFE = Set.of(Material.WATER, Material.LAVA, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE, Material.BUBBLE_COLUMN,
            Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);

    private final JavaPlugin plugin;
    private final NamespacedKey homeKey;
    private final int half;
    private final int attempts;
    /** 접속 직전에 골라 둔 위치 (접속하면 플레이어 데이터로 옮김) */
    private final Map<UUID, Location> chosen = new ConcurrentHashMap<>();

    public RandomSpawnFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.homeKey = new NamespacedKey(plugin, "home_spawn");
        int size = Math.max(16, plugin.getConfig().getInt("random-spawn.size", 5000));
        this.half = size / 2;
        this.attempts = Math.max(1, plugin.getConfig().getInt("random-spawn.attempts", 40));
    }

    private World overworld() {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) return world;
        }
        return null;
    }

    // ── 첫 접속: 서버 입장 직전(비동기)에 위치를 골라 바로 그곳에서 스폰 ──
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!event.isNewPlayer()) return;
        World world = overworld();
        if (world == null) return;
        Location location = findSafe(world);
        if (location == null) {
            plugin.getLogger().warning("무작위 스폰 위치를 찾지 못해 기본 스폰을 사용합니다.");
            return;
        }
        event.setSpawnLocation(location);
        UUID id = event.getConnection().getProfile().getId();
        if (id != null) chosen.put(id, location);
    }

    /** 무작위 좌표를 골라 청크를 불러온 뒤(메인 스레드) 안전한지 확인. 이 메서드는 비동기 스레드에서 호출됨 */
    private Location findSafe(World world) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < attempts; i++) {
            int x = random.nextInt(-half, half);
            int z = random.nextInt(-half, half);
            ChunkSnapshot snapshot;
            try {
                snapshot = world.getChunkAtAsync(x >> 4, z >> 4)
                        .thenApply(chunk -> chunk.getChunkSnapshot(true, false, false))
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                continue;
            }
            int lx = x & 15, lz = z & 15;
            int y = snapshot.getHighestBlockYAt(lx, lz);
            if (y <= world.getMinHeight()) continue;
            Material ground = snapshot.getBlockType(lx, y, lz);
            if (!ground.isSolid() || UNSAFE.contains(ground) || Tag.LEAVES.isTagged(ground)) continue;
            if (y + 2 >= world.getMaxHeight()) continue;
            Material feet = snapshot.getBlockType(lx, y + 1, lz);
            Material head = snapshot.getBlockType(lx, y + 2, lz);
            if (!feet.isAir() || !head.isAir()) continue;
            return new Location(world, x + 0.5, y + 1, z + 0.5, random.nextFloat() * 360f, 0f);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location location = chosen.remove(player.getUniqueId());
        if (location != null && !player.getPersistentDataContainer().has(homeKey, PersistentDataType.STRING)) {
            player.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING,
                    location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockZ());
        }
    }

    /** 침대/리스폰 앵커 없이 죽으면 개인 스폰에서 부활 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
        Location home = home(event.getPlayer());
        if (home != null) event.setRespawnLocation(home);
    }

    /** 저장된 개인 스폰 (지금 지형 기준으로 안전한 높이를 다시 계산) */
    public Location home(Player player) {
        String raw = player.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        if (raw == null) return null;
        String[] parts = raw.split(";");
        if (parts.length != 3) return null;
        World world = plugin.getServer().getWorld(parts[0]);
        if (world == null) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            return new Location(world, x + 0.5, top.getY() + 1, z + 0.5);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
