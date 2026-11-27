package dev.groupsurvival.gss.feature;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.groupsurvival.gss.util.FileLog;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * 교전 중 로그아웃 시 잠든 몸 (Rust 방식).
 *
 * - 플레이어에게 피해를 주거나 플레이어에게 피해를 받은 뒤 combat-seconds(기본 30초) 안에 나가면,
 *   그 자리에 잠든 몸(마네킹, 본인 스킨, 이름은 익명)이 남음. 그 밖의 로그아웃은 그냥 나감
 * - 몸은 중력의 영향을 받지 않고 움직이지 않음. 인벤토리(갑옷·왼손 포함)와 경험치를 몸이 가지고 있음
 * - 몸은 플레이어가 일으킨 피해만 받음: 직접 공격, 플레이어가 쏜 화살·삼지창 등, 플레이어가 놓은 용암·불,
 *   플레이어가 터뜨린 TNT 등. 환경 피해·질식·낙하·몹 피해는 받지 않음
 * - 우클릭하면 몸의 인벤토리가 열려 약탈 가능. 죽이면 남은 아이템·경험치·머리를 떨어뜨리고 "사람이 죽었다" 공지
 * - 본인이 다시 접속하면: 몸이 살아 있으면 남은 아이템을 돌려받고 몸은 사라짐, 몸이 죽었으면 접속하자마자 사망
 * - 안전구역 안에서 나가거나, 서버를 끌 때는 몸을 만들지 않음
 */
public final class OfflineBodyFeature implements Listener {

    private static final int SIZE = 45;          // 0~35 인벤토리, 36~39 갑옷(머리→발), 40 왼손, 41~44 칸막이
    private static final int ARMOR_START = 36;
    private static final int OFFHAND = 40;
    private static final long HAZARD_MILLIS = 15 * 60 * 1000L; // 플레이어가 놓은 용암·불을 기억하는 시간
    private static final long FIRE_MILLIS = 10_000L;            // 플레이어 때문에 붙은 불로 보는 시간

    /** 오프라인 중 몸이 죽어서 다시 접속할 때 사망 처리하는 플레이어 (공지·드롭 없이) */
    private static final Set<UUID> silentDeaths = ConcurrentHashMap.newKeySet();

    public static boolean isSilentDeath(UUID id) {
        return silentDeaths.contains(id);
    }

    private final class Body implements InventoryHolder {
        final UUID owner;
        final String name;
        UUID mannequin;
        UUID hitbox;
        Location location;
        List<ProfileProperty> textures = new ArrayList<>();
        int level;
        float exp;
        boolean killed;
        long lastPlayerFire;
        final Inventory inventory;

        Body(UUID owner, String name) {
            this.owner = owner;
            this.name = name;
            this.inventory = Bukkit.createInventory(this, SIZE, Component.text("잠든 " + anonymousName, NamedTextColor.DARK_GRAY));
            for (int i = 41; i < SIZE; i++) inventory.setItem(i, divider());
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final FileLog log;
    private final Predicate<Location> safeZone;
    private final PlayerHeadFeature heads;
    private final NamespacedKey bodyKey;
    private final NamespacedKey dividerKey;
    private final long combatMillis;
    private final String anonymousName;
    private final Component deathMessage;
    private final File dataFile;
    private final boolean debug = Boolean.getBoolean("gss.debug");
    /** 마지막 교전 시각 */
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    /** 주인 → 몸 */
    private final Map<UUID, Body> bodies = new HashMap<>();
    /** 플레이어가 놓은 용암·불 위치 → 시각 */
    private final Map<Location, Long> playerHazards = new HashMap<>();

    public OfflineBodyFeature(JavaPlugin plugin, FileLog log, Predicate<Location> safeZone, PlayerHeadFeature heads) {
        this.plugin = plugin;
        this.log = log;
        this.safeZone = safeZone;
        this.heads = heads;
        this.bodyKey = new NamespacedKey(plugin, "offline_body");
        this.dividerKey = new NamespacedKey(plugin, "body_divider");
        var config = plugin.getConfig();
        this.combatMillis = Math.max(1, config.getLong("offline-body.combat-seconds", 30)) * 1000L;
        String name = config.getString("anonymity.anonymous-name", "익명");
        this.anonymousName = name == null || name.isBlank() ? "익명" : name.length() > 16 ? name.substring(0, 16) : name;
        this.deathMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(config.getString("death-message", "&c&l사람이 죽었다."));
        this.dataFile = new File(plugin.getDataFolder(), "bodies.yml");
        load();
        // 오래된 용암·불 기록 정리
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            playerHazards.values().removeIf(time -> now - time > HAZARD_MILLIS);
        }, 20L * 60, 20L * 60);
    }

    private ItemStack divider() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        meta.getPersistentDataContainer().set(dividerKey, PersistentDataType.BOOLEAN, true);
        pane.setItemMeta(meta);
        return pane;
    }

    private boolean isDivider(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(dividerKey, PersistentDataType.BOOLEAN);
    }

    // ───────────────────────── 교전 기록 ─────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamageSource().getCausingEntity() instanceof Player attacker) || attacker.equals(victim)) return;
        long now = System.currentTimeMillis();
        lastCombat.put(victim.getUniqueId(), now);
        lastCombat.put(attacker.getUniqueId(), now);
    }

    // ───────────────────────── 로그아웃 → 몸 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long combat = lastCombat.remove(player.getUniqueId());
        if (combat == null || System.currentTimeMillis() - combat > combatMillis) return;
        if (Bukkit.isStopping() || player.isDead()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (safeZone.test(player.getLocation())) return;
        if (bodies.containsKey(player.getUniqueId())) return;
        createBody(player);
    }

    private void createBody(Player player) {
        Location loc = player.getLocation();
        Body body = new Body(player.getUniqueId(), player.getName());
        body.location = loc.clone();
        body.level = player.getLevel();
        body.exp = player.getExp();
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (property.getName().equals("textures")) body.textures.add(property);
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < 36 && i < storage.length; i++) body.inventory.setItem(i, storage[i]);
        ItemStack[] armor = inv.getArmorContents(); // 발, 다리, 가슴, 머리
        for (int i = 0; i < 4 && i < armor.length; i++) body.inventory.setItem(ARMOR_START + (3 - i), armor[i]);
        body.inventory.setItem(OFFHAND, inv.getItemInOffHand());

        AttributeInstance playerMax = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = playerMax != null ? playerMax.getValue() : 20.0;
        double health = Math.max(1.0, Math.min(maxHealth, player.getHealth()));
        double armorPoints = value(player, Attribute.ARMOR);
        double toughness = value(player, Attribute.ARMOR_TOUGHNESS);

        Mannequin mannequin = loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setGravity(false);
            m.setImmovable(true);
            m.setPersistent(true);
            m.setSilent(true);
            m.setCustomNameVisible(false);
            m.setDescription(null);
            // 진짜 UUID·닉네임은 넣지 않고 스킨(textures)만 입힘 (익명)
            m.setProfile(ResolvableProfile.resolvableProfile()
                    .uuid(UUID.randomUUID())
                    .addProperties(body.textures)
                    .build());
            m.setPose(Pose.SLEEPING, true);
            setBase(m, Attribute.MAX_HEALTH, maxHealth);
            setBase(m, Attribute.ARMOR, armorPoints);
            setBase(m, Attribute.ARMOR_TOUGHNESS, toughness);
            m.setHealth(health);
            m.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, body.owner.toString());
        });
        // 잠든 자세는 판정이 매우 작아서, 때리기·우클릭용 판정 상자를 따로 둠
        Interaction hitbox = loc.getWorld().spawn(loc.clone().add(0, 0.05, 0), Interaction.class, i -> {
            i.setInteractionWidth(1.4f);
            i.setInteractionHeight(0.6f);
            i.setResponsive(true);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, body.owner.toString());
        });
        body.mannequin = mannequin.getUniqueId();
        body.hitbox = hitbox.getUniqueId();
        bodies.put(body.owner, body);
        save(); // 아이템을 옮기기 전에 먼저 저장 (서버가 갑자기 꺼져도 아이템이 사라지지 않게)

        inv.clear();
        player.setLevel(0);
        player.setExp(0f);
        debug("몸 생성: " + body.name + " 아이템 " + countItems(body) + "개, 레벨 " + body.level + ", 마네킹 " + body.mannequin);
        log.write("시스템/교전종료.log", FileLog.line(player, loc, "교전 중 접속 종료 (잠든 몸이 남음)"));
    }

    private static double value(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    private static void setBase(Mannequin mannequin, Attribute attribute, double value) {
        AttributeInstance instance = mannequin.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    // ───────────────────────── 다시 접속 ─────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Body body = bodies.remove(player.getUniqueId());
        if (body == null) return;
        debug("다시 접속: " + body.name + " killed=" + body.killed + " 아이템 " + countItems(body) + "개");
        closeViewers(body);
        removeEntities(body);
        if (body.killed) {
            // 오프라인 중 몸이 죽음: 아이템·경험치는 이미 몸에서 떨어졌으므로 그대로 사망 처리
            player.getInventory().clear();
            player.setLevel(0);
            player.setExp(0f);
            silentDeaths.add(player.getUniqueId());
            player.getScheduler().runDelayed(plugin, task -> {
                if (player.isOnline() && !player.isDead()) player.setHealth(0);
                else silentDeaths.remove(player.getUniqueId());
            }, () -> silentDeaths.remove(player.getUniqueId()), 2L);
            log.write("시스템/교전종료.log", FileLog.line(player, player.getLocation(), "다시 접속 (몸이 죽어 있어 사망 처리)"));
        } else {
            restore(player, body);
            log.write("시스템/교전종료.log", FileLog.line(player, player.getLocation(), "다시 접속 (몸에서 아이템을 돌려받음)"));
        }
        save();
    }

    private void restore(Player player, Body body) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> leftover = new ArrayList<>();
        for (int i = 0; i < 36; i++) put(inv, i, body.inventory.getItem(i), leftover);
        int[] armorSlots = {39, 38, 37, 36}; // 머리, 가슴, 다리, 발 (플레이어 인벤토리 번호)
        for (int i = 0; i < 4; i++) put(inv, armorSlots[i], body.inventory.getItem(ARMOR_START + i), leftover);
        put(inv, 40, body.inventory.getItem(OFFHAND), leftover);
        for (ItemStack stack : leftover) {
            for (ItemStack rest : inv.addItem(stack).values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        player.setLevel(player.getLevel() + body.level);
        player.setExp(Math.min(0.999f, player.getExp() + body.exp));
    }

    private void put(PlayerInventory inv, int slot, ItemStack stack, List<ItemStack> leftover) {
        if (stack == null || stack.getType().isAir() || isDivider(stack)) return;
        ItemStack current = inv.getItem(slot);
        if (current == null || current.getType().isAir()) inv.setItem(slot, stack);
        else leftover.add(stack);
    }

    /** 오프라인 중 죽은 플레이어의 접속 사망: 떨어뜨릴 것 없음 (공지는 AnonymityFeature 에서 생략) */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSilentDeath(PlayerDeathEvent event) {
        if (!silentDeaths.contains(event.getPlayer().getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSilentDeathDone(PlayerDeathEvent event) {
        silentDeaths.remove(event.getPlayer().getUniqueId());
    }

    // ───────────────────────── 몸 찾기 ─────────────────────────

    private Body bodyOf(Entity entity) {
        if (!(entity instanceof Mannequin) && !(entity instanceof Interaction)) return null;
        String owner = entity.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (owner == null) return null;
        try {
            Body body = bodies.get(UUID.fromString(owner));
            if (body == null || body.killed) return null;
            UUID id = entity.getUniqueId();
            return id.equals(body.mannequin) || id.equals(body.hitbox) ? body : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mannequin mannequin(Body body) {
        return Bukkit.getEntity(body.mannequin) instanceof Mannequin m ? m : null;
    }

    /** 주인이 다시 접속했거나 기록이 없는 몸은 청크가 로드될 때 지움 */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!entity.getPersistentDataContainer().has(bodyKey, PersistentDataType.STRING)) continue;
            if (bodyOf(entity) == null) entity.remove();
        }
    }

    private void removeEntities(Body body) {
        for (UUID id : List.of(body.mannequin, body.hitbox)) {
            Entity entity = id == null ? null : Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    // ───────────────────────── 피해: 플레이어가 일으킨 것만 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyDamage(EntityDamageEvent event) {
        Body body = bodyOf(event.getEntity());
        if (body == null) return;
        if (event.getEntity() instanceof Interaction || !isPlayerCaused(body, event)) event.setCancelled(true);
    }

    private boolean isPlayerCaused(Body body, EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL) return true; // 관리자 /kill
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing instanceof Player player) return !player.getUniqueId().equals(body.owner);
        long now = System.currentTimeMillis();
        switch (cause) {
            case LAVA, FIRE, CAMPFIRE -> {
                if (nearPlayerHazard(event.getEntity().getLocation())) {
                    body.lastPlayerFire = now;
                    return true;
                }
                return false;
            }
            case FIRE_TICK -> {
                return now - body.lastPlayerFire <= FIRE_MILLIS;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean nearPlayerHazard(Location loc) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, Long> entry : playerHazards.entrySet()) {
            Location hazard = entry.getKey();
            if (hazard.getWorld() != loc.getWorld() || now - entry.getValue() > HAZARD_MILLIS) continue;
            if (hazard.distanceSquared(loc) <= 36) return true; // 6블록 (흘러내린 용암 포함)
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLavaPlace(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.LAVA_BUCKET) return;
        Block block = event.getBlockClicked().getRelative(event.getBlockFace());
        playerHazards.put(block.getLocation(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) return;
        playerHazards.put(event.getBlock().getLocation(), System.currentTimeMillis());
    }

    /** 불붙음: 플레이어가 붙인 불(불 붙은 화살, 발화 인챈트, 플레이어가 놓은 용암·불)만 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        Body body = bodyOf(event.getEntity());
        if (body == null || !(event.getEntity() instanceof Mannequin)) return;
        boolean byPlayer = false;
        if (event instanceof EntityCombustByEntityEvent byEntity) {
            Entity combuster = byEntity.getCombuster();
            byPlayer = combuster instanceof Player
                    || (combuster instanceof Projectile projectile && projectile.getShooter() instanceof Player);
        } else if (event instanceof EntityCombustByBlockEvent) {
            byPlayer = nearPlayerHazard(event.getEntity().getLocation());
        }
        if (byPlayer) body.lastPlayerFire = System.currentTimeMillis();
        else event.setCancelled(true);
    }

    /** 판정 상자를 때리면 몸을 때린 것으로 처리 */
    @EventHandler(priority = EventPriority.HIGH) // 판정 상자는 원래 "공격 안 됨"으로 와서 취소된 이벤트도 받아야 함
    public void onAttackHitbox(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction)) return;
        Body body = bodyOf(event.getAttacked());
        if (body == null) return;
        event.setCancelled(true);
        Mannequin mannequin = mannequin(body);
        debug("판정 상자 공격: " + event.getPlayer().getName() + " → " + (mannequin == null ? "마네킹 없음" : "마네킹"));
        if (mannequin != null) event.getPlayer().attack(mannequin);
    }

    /** 판정 상자에 맞은 화살·삼지창은 몸에 피해 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHitbox(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Interaction)) return;
        Body body = bodyOf(event.getHitEntity());
        if (body == null) return;
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)) return;
        Mannequin mannequin = mannequin(body);
        if (mannequin == null) return;
        double damage;
        DamageType type;
        if (projectile instanceof Trident) {
            damage = 8.0;
            type = DamageType.TRIDENT;
        } else if (projectile instanceof AbstractArrow arrow) {
            damage = Math.ceil(arrow.getVelocity().length() * arrow.getDamage());
            type = DamageType.ARROW;
        } else {
            return;
        }
        mannequin.damage(damage, DamageSource.builder(type).withCausingEntity(shooter).withDirectEntity(projectile).build());
    }

    // ───────────────────────── 약탈 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        Body body = bodyOf(event.getRightClicked());
        if (body == null) return;
        event.setCancelled(true);
        event.getPlayer().openInventory(body.inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLootClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Body body)) return;
        if (!bodies.containsValue(body)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getSlot() > OFFHAND) {
            event.setCancelled(true); // 칸막이
        } else if (isDivider(event.getCurrentItem()) || isDivider(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLootDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Body)) return;
        for (int slot : event.getRawSlots()) {
            if (slot > OFFHAND && slot < SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onLootClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Body) save();
    }

    private void closeViewers(Body body) {
        for (HumanEntity viewer : new ArrayList<>(body.inventory.getViewers())) viewer.closeInventory();
    }

    // ───────────────────────── 몸이 죽음 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mannequin)) return;
        Body body = bodyOf(event.getEntity());
        if (body == null) return;
        closeViewers(body);
        Collection<ItemStack> drops = event.getDrops();
        drops.clear();
        for (int i = 0; i <= OFFHAND; i++) {
            ItemStack stack = body.inventory.getItem(i);
            if (stack != null && !stack.getType().isAir() && !isDivider(stack)) drops.add(stack);
        }
        if (heads != null) {
            PlayerProfile profile = Bukkit.createProfile(body.owner, body.name);
            profile.setProperties(body.textures);
            drops.add(heads.head(profile));
        }
        event.setDroppedExp(Math.min(body.level * 7, 100)); // 바닐라 플레이어 사망과 같은 양
        debug("몸 처치: " + body.name + " 드롭 " + drops.size() + "개, 경험치 " + event.getDroppedExp());
        body.inventory.clear();
        body.killed = true;
        Entity hitbox = Bukkit.getEntity(body.hitbox);
        if (hitbox != null) hitbox.remove();
        plugin.getServer().broadcast(deathMessage);
        Player killer = event.getEntity().getKiller();
        log.write("시스템/사망.log", FileLog.line(body.name, event.getEntity().getLocation(),
                "사망 (교전 중 접속 종료 후 잠든 몸" + (killer != null ? ", " + killer.getName() + "에게" : "") + ")"));
        log.write("시스템/교전종료.log", FileLog.line(body.name, event.getEntity().getLocation(),
                "잠든 몸이 죽음" + (killer != null ? " (" + killer.getName() + ")" : "")));
        save();
    }

    private int countItems(Body body) {
        int n = 0;
        for (int i = 0; i <= OFFHAND; i++) {
            ItemStack stack = body.inventory.getItem(i);
            if (stack != null && !stack.getType().isAir() && !isDivider(stack)) n += stack.getAmount();
        }
        return n;
    }

    private void debug(String message) {
        if (debug) plugin.getLogger().info("[DBG 몸] " + message);
    }

    // ───────────────────────── 저장 ─────────────────────────

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Body body : bodies.values()) {
            ConfigurationSection s = yaml.createSection("bodies." + body.owner);
            s.set("name", body.name);
            s.set("mannequin", body.mannequin == null ? null : body.mannequin.toString());
            s.set("hitbox", body.hitbox == null ? null : body.hitbox.toString());
            s.set("location", body.location);
            s.set("level", body.level);
            s.set("exp", (double) body.exp);
            s.set("killed", body.killed);
            List<String> textures = new ArrayList<>();
            for (ProfileProperty property : body.textures) {
                textures.add(property.getValue() + "|" + (property.getSignature() == null ? "" : property.getSignature()));
            }
            s.set("textures", textures);
            ItemStack[] items = new ItemStack[OFFHAND + 1];
            for (int i = 0; i <= OFFHAND; i++) {
                ItemStack stack = body.inventory.getItem(i);
                items[i] = stack == null || isDivider(stack) ? ItemStack.empty() : stack;
            }
            s.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)));
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("bodies.yml 저장 실패: " + e);
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("bodies");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Body body = new Body(UUID.fromString(key), s.getString("name", "?"));
                String mannequin = s.getString("mannequin");
                String hitbox = s.getString("hitbox");
                body.mannequin = mannequin == null ? null : UUID.fromString(mannequin);
                body.hitbox = hitbox == null ? null : UUID.fromString(hitbox);
                body.location = s.getLocation("location");
                body.level = s.getInt("level");
                body.exp = (float) s.getDouble("exp");
                body.killed = s.getBoolean("killed");
                for (String raw : s.getStringList("textures")) {
                    int bar = raw.indexOf('|');
                    String value = bar < 0 ? raw : raw.substring(0, bar);
                    String signature = bar < 0 || bar == raw.length() - 1 ? null : raw.substring(bar + 1);
                    body.textures.add(new ProfileProperty("textures", value, signature));
                }
                String items = s.getString("items");
                if (items != null) {
                    ItemStack[] stacks = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(items));
                    for (int i = 0; i < stacks.length && i <= OFFHAND; i++) {
                        if (stacks[i] != null && !stacks[i].getType().isAir()) body.inventory.setItem(i, stacks[i]);
                    }
                }
                bodies.put(body.owner, body);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("bodies.yml 의 몸 하나를 읽지 못함 (" + key + "): " + e);
            }
        }
    }

    /** 플러그인 종료: 약탈 창을 닫고 저장 */
    public void shutdown() {
        for (Body body : bodies.values()) closeViewers(body);
        save();
    }

    public int count() {
        return bodies.size();
    }
}
