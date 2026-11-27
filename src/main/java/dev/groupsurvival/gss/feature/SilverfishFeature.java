package dev.groupsurvival.gss.feature;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TrialSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.entity.TrialSpawnerSpawnEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.spawner.BaseSpawner;

/**
 * 좀벌레 완전 제거. 눈에 띄는 곳 어디에도 나오지 않음.
 * - 좀벌레는 어떤 방법으로도 생성되지 않음 (스포너, 시련 스포너, 벌레 먹은 블록, 스폰 알, 명령어). 이미 있던 것은 제거
 * - 벌레 먹은 블록(산, 요새, 이글루 등)은 청크가 처음 로드될 때 일반 블록으로 바뀜
 * - 좀벌레 스포너(요새)는 빈 스포너로, 좀벌레 시련 스포너(구리 던전)는 다른 몹으로 바뀜 (안에서 도는 모형도 안 보임)
 * - 벌레먹음 상태이상은 걸리지 않고, 벌레먹음 물약은 양조되지 않음
 */
public final class SilverfishFeature implements Listener {

    /** 청크 정리 버전. 규칙이 바뀌면 숫자를 올려 다시 검사하게 함 */
    private static final int CLEAN_VERSION = 1;
    private static final Map<Material, Material> INFESTED = new EnumMap<>(Material.class);

    static {
        INFESTED.put(Material.INFESTED_STONE, Material.STONE);
        INFESTED.put(Material.INFESTED_COBBLESTONE, Material.COBBLESTONE);
        INFESTED.put(Material.INFESTED_STONE_BRICKS, Material.STONE_BRICKS);
        INFESTED.put(Material.INFESTED_MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICKS);
        INFESTED.put(Material.INFESTED_CRACKED_STONE_BRICKS, Material.CRACKED_STONE_BRICKS);
        INFESTED.put(Material.INFESTED_CHISELED_STONE_BRICKS, Material.CHISELED_STONE_BRICKS);
        INFESTED.put(Material.INFESTED_DEEPSLATE, Material.DEEPSLATE);
    }

    private final JavaPlugin plugin;
    private final NamespacedKey cleanedKey;
    private final EntityType trialReplacement;
    private final List<BlockData> infestedStates = new ArrayList<>();
    private final boolean debug = Boolean.getBoolean("gss.debug");

    public SilverfishFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cleanedKey = new NamespacedKey(plugin, "silverfish_clean");
        EntityType replacement;
        try {
            replacement = EntityType.valueOf(plugin.getConfig().getString("silverfish.trial-spawner-replacement", "CAVE_SPIDER").toUpperCase());
        } catch (IllegalArgumentException e) {
            replacement = EntityType.CAVE_SPIDER;
        }
        this.trialReplacement = replacement == EntityType.SILVERFISH ? EntityType.CAVE_SPIDER : replacement;
        for (Material material : INFESTED.keySet()) {
            BlockData data = material.createBlockData();
            if (data instanceof Orientable orientable) {
                for (var axis : orientable.getAxes()) {
                    Orientable copy = (Orientable) data.clone();
                    copy.setAxis(axis);
                    infestedStates.add(copy);
                }
            } else {
                infestedStates.add(data);
            }
        }
    }

    public void start() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(org.bukkit.entity.Silverfish.class)) entity.remove();
            for (Chunk chunk : world.getLoadedChunks()) clean(chunk);
        }
    }

    // ───────────────────────── 좀벌레 생성 차단 ─────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntityType() == EntityType.SILVERFISH) {
            event.setCancelled(true);
            return;
        }
        // 벌레먹음 물약(불길한 시련의 아이템 소환기 등)은 던져지는 순간 없앰, 벌레먹음 구름도 생기지 않음
        if (event.getEntity() instanceof org.bukkit.entity.ThrownPotion potion && isInfested(potion.getPotionMeta())) {
            event.setCancelled(true);
        } else if (event.getEntity() instanceof org.bukkit.entity.AreaEffectCloud cloud && isInfested(cloud)) {
            event.setCancelled(true);
        }
    }

    private static boolean isInfested(PotionMeta meta) {
        if (meta == null) return false;
        if (meta.getBasePotionType() == PotionType.INFESTED) return true;
        return meta.getCustomEffects().stream().anyMatch(effect -> effect.getType() == PotionEffectType.INFESTED);
    }

    private static boolean isInfested(org.bukkit.entity.AreaEffectCloud cloud) {
        if (cloud.getBasePotionType() == PotionType.INFESTED) return true;
        return cloud.getCustomEffects().stream().anyMatch(effect -> effect.getType() == PotionEffectType.INFESTED);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (event.getEntityType() != EntityType.SILVERFISH) return;
        event.setCancelled(true);
        if (event.getSpawner() != null) fixSpawner(event.getSpawner());
    }

    /** 소환 위치 조건과 상관없이 스포너가 좀벌레를 소환하려 하면 바로 고침 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreSpawnerSpawn(PreSpawnerSpawnEvent event) {
        if (event.getType() != EntityType.SILVERFISH) return;
        event.setCancelled(true);
        event.setShouldAbortSpawn(true);
        Location location = event.getSpawnerLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> fixAt(location.getBlock()));
    }

    /** 설치한 스포너(좀벌레가 들어 있는 것 포함)를 바로 검사 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type != Material.SPAWNER && type != Material.TRIAL_SPAWNER) return;
        Block block = event.getBlockPlaced();
        plugin.getServer().getScheduler().runTask(plugin, () -> fixAt(block));
    }

    /** 스포너에 좀벌레 스폰 알 사용 금지 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEggOnSpawner(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Material clicked = event.getClickedBlock().getType();
        if ((clicked == Material.SPAWNER || clicked == Material.TRIAL_SPAWNER)
                && event.getItem() != null && event.getItem().getType() == Material.SILVERFISH_SPAWN_EGG) {
            event.setCancelled(true);
        }
    }

    private void fixAt(Block block) {
        BlockState state = block.getState();
        if (state instanceof CreatureSpawner spawner && hasSilverfish(spawner)) fixSpawner(spawner);
        else if (state instanceof TrialSpawner trial && (hasSilverfish(trial.getNormalConfiguration())
                || hasSilverfish(trial.getOminousConfiguration()))) fixTrialSpawner(trial);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTrialSpawnerSpawn(TrialSpawnerSpawnEvent event) {
        if (event.getEntityType() != EntityType.SILVERFISH) return;
        event.setCancelled(true);
        fixTrialSpawner(event.getTrialSpawner());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity.getType() == EntityType.SILVERFISH) entity.remove();
        }
    }

    /** 도전과제 "몬스터 사냥꾼"(모든 몬스터 처치)에서 좀벌레 조건을 미리 채워 달성 가능하게 유지 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Advancement advancement = plugin.getServer().getAdvancement(NamespacedKey.minecraft("adventure/kill_all_mobs"));
        if (advancement == null) return;
        AdvancementProgress progress = event.getPlayer().getAdvancementProgress(advancement);
        if (progress.getRemainingCriteria().contains("minecraft:silverfish")) progress.awardCriteria("minecraft:silverfish");
    }

    // ───────────────────────── 벌레먹음 상태이상 · 물약 ─────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEffect(EntityPotionEffectEvent event) {
        if (event.getModifiedType() != PotionEffectType.INFESTED) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action == EntityPotionEffectEvent.Action.ADDED || action == EntityPotionEffectEvent.Action.CHANGED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBrew(BrewEvent event) {
        for (ItemStack result : event.getResults()) {
            if (result != null && result.getItemMeta() instanceof PotionMeta meta && meta.getBasePotionType() == PotionType.INFESTED) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ───────────────────────── 벌레 먹은 블록 · 스포너 정리 ─────────────────────────

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (INFESTED.containsKey(block.getType())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> replace(block));
        }
    }

    /** 청크가 로드되는 즉시 정리 (플레이어에게 보내지기 전, 미리 생성 중 바로 언로드되는 청크도 빠짐없이) */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        clean(event.getChunk());
    }

    private void clean(Chunk chunk) {
        Integer version = chunk.getPersistentDataContainer().get(cleanedKey, PersistentDataType.INTEGER);
        if (version != null && version >= CLEAN_VERSION) return;

        int spawners = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof CreatureSpawner spawner && hasSilverfish(spawner)) {
                fixSpawner(spawner);
                spawners++;
                if (debug) plugin.getLogger().info("[DBG] spawner at " + state.getX() + " " + state.getY() + " " + state.getZ());
            } else if (state instanceof TrialSpawner trial && (hasSilverfish(trial.getNormalConfiguration())
                    || hasSilverfish(trial.getOminousConfiguration()))) {
                fixTrialSpawner(trial);
                spawners++;
                if (debug) plugin.getLogger().info("[DBG] trial spawner at " + state.getX() + " " + state.getY() + " " + state.getZ());
            }
        }
        if (debug && spawners > 0) {
            plugin.getLogger().info("[DBG] 좀벌레 스포너 " + spawners + "개 정리: chunk " + chunk.getX() + "," + chunk.getZ());
        }

        boolean hasInfested = false;
        for (BlockData state : infestedStates) {
            if (chunk.contains(state)) {
                hasInfested = true;
                break;
            }
        }
        if (!hasInfested) {
            markClean(chunk);
            return;
        }
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        World world = chunk.getWorld();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        int replaced = 0;
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (INFESTED.containsKey(snapshot.getBlockType(x, y, z))) {
                        replace(chunk.getBlock(x, y, z));
                        replaced++;
                    }
                }
            }
        }
        markClean(chunk);
        if (debug) {
            plugin.getLogger().info("[DBG] 벌레 먹은 블록 " + replaced + "개 정리: chunk " + chunk.getX() + "," + chunk.getZ());
        }
    }

    private void markClean(Chunk chunk) {
        chunk.getPersistentDataContainer().set(cleanedKey, PersistentDataType.INTEGER, CLEAN_VERSION);
    }

    /** 벌레 먹은 블록을 일반 블록으로 (심층암은 방향 유지) */
    private void replace(Block block) {
        Material normal = INFESTED.get(block.getType());
        if (normal == null) return;
        BlockData old = block.getBlockData();
        BlockData data = normal.createBlockData();
        if (old instanceof Orientable from && data instanceof Orientable to) to.setAxis(from.getAxis());
        block.setBlockData(data, false);
    }

    private static boolean hasSilverfish(BaseSpawner spawner) {
        if (spawner.getSpawnedType() == EntityType.SILVERFISH) return true;
        for (SpawnerEntry entry : spawner.getPotentialSpawns()) {
            if (entry.getSnapshot().getEntityType() == EntityType.SILVERFISH) return true;
        }
        return false;
    }

    /** 좀벌레 스포너 → 빈 스포너 (아무것도 소환하지 않고 안에 모형도 없음) */
    private void fixSpawner(CreatureSpawner spawner) {
        spawner.setPotentialSpawns(List.of());
        spawner.setSpawnedType(null);
        spawner.update(true, false);
    }

    /** 좀벌레 시련 스포너 → 다른 몹 (기본 동굴 거미) */
    private void fixTrialSpawner(TrialSpawner trial) {
        for (BaseSpawner config : List.of(trial.getNormalConfiguration(), trial.getOminousConfiguration())) {
            if (!hasSilverfish(config)) continue;
            List<SpawnerEntry> kept = new ArrayList<>();
            for (SpawnerEntry entry : config.getPotentialSpawns()) {
                if (entry.getSnapshot().getEntityType() != EntityType.SILVERFISH) kept.add(entry);
            }
            config.setPotentialSpawns(kept);
            if (kept.isEmpty() || config.getSpawnedType() == EntityType.SILVERFISH) config.setSpawnedType(trialReplacement);
        }
        trial.update(true, false);
    }
}
