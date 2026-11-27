package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.ActionBars;
import dev.groupsurvival.gss.util.FileLog;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * 첫 접속 인증. 정해진 키를 입력하기 전에는 움직일 수 없음.
 * - /인증 <키> 또는 채팅에 키를 입력 (인증 전 채팅은 다른 사람에게 보이지 않음)
 * - 인증 전: 이동, 블록 부수기/설치, 상호작용, 아이템 버리기/줍기, 인벤토리 열기, 명령어(/인증 제외),
 *   피해 주고받기, 배고픔 감소 모두 막힘
 * - 한 번 인증하면 플레이어 데이터에 저장되어 다음 접속부터는 필요 없음
 */
public final class AuthFeature implements Listener, CommandExecutor {

    private static final String BYPASS = "gss.bypass.auth";

    private final JavaPlugin plugin;
    private final FileLog log;
    private final NamespacedKey key;
    private final String secret;
    /** 인증을 기다리는 플레이어 (비동기 채팅 이벤트에서도 읽음) */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public AuthFeature(JavaPlugin plugin, FileLog log) {
        this.plugin = plugin;
        this.log = log;
        this.key = new NamespacedKey(plugin, "authenticated");
        this.secret = plugin.getConfig().getString("auth.key", "").trim();
        if (secret.isEmpty() || secret.equals("여기에_키를_입력하세요")) {
            plugin.getLogger().warning("auth.key 가 설정되지 않았습니다. config.yml 에서 인증 키를 정해 주세요.");
        }
        // 인증 대기 중인 플레이어에게 2초마다 안내
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : pending) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) ActionBars.send(player, prompt());
            }
        }, 40L, 40L);
    }

    private static Component prompt() {
        return Component.text("인증이 필요합니다 · ", NamedTextColor.RED)
                .append(Component.text("/인증 <키> 또는 채팅에 키를 입력하세요", NamedTextColor.YELLOW));
    }

    public boolean isPending(Player player) {
        return pending.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;
        if (player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, false)) return;
        pending.add(player.getUniqueId());
        player.getScheduler().runDelayed(plugin, task -> {
            if (!isPending(player)) return;
            player.showTitle(Title.title(Component.text("인증이 필요합니다", NamedTextColor.RED),
                    Component.text("/인증 <키> 또는 채팅에 키를 입력하세요", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofMillis(500))));
            player.sendMessage(Component.text("[인증] ", NamedTextColor.GOLD)
                    .append(Component.text("서버에서 안내받은 키를 입력해야 움직일 수 있습니다.", NamedTextColor.WHITE)));
        }, null, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void attempt(Player player, String input) {
        if (!isPending(player)) {
            player.sendMessage(Component.text("이미 인증되었습니다.", NamedTextColor.GRAY));
            return;
        }
        if (!secret.isEmpty() && input.trim().equals(secret)) {
            pending.remove(player.getUniqueId());
            player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
            player.clearTitle();
            ActionBars.send(player, Component.text("인증되었습니다! 이제 움직일 수 있습니다.", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
            log.write("시스템/인증.log", FileLog.line(player, player.getLocation(), "인증함"));
        } else {
            ActionBars.send(player, Component.text("키가 올바르지 않습니다.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            log.write("시스템/인증실패.log", FileLog.line(player, player.getLocation(), "인증 실패"));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("사용법: /인증 <키>", NamedTextColor.YELLOW));
            return true;
        }
        attempt(player, String.join(" ", args));
        return true;
    }

    // ── 인증 전 채팅 = 키 입력 (다른 사람에게 보이지 않음) ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!pending.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message());
        player.getScheduler().run(plugin, task -> attempt(player, input), null);
    }

    // ── 인증 전 명령어는 /인증 만 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isPending(event.getPlayer())) return;
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.equals("인증") || label.equals("auth")) return;
        event.setCancelled(true);
        ActionBars.send(event.getPlayer(), prompt());
    }

    // ── 인증 전 이동 금지 (고개 돌리기는 허용) ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!isPending(event.getPlayer())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location stay = from.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        event.setTo(stay);
    }

    private void block(Player player, Cancellable event) {
        if (isPending(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) block(player, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) block(player, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) block(player, event);
    }

    /** 인증 전에는 피해를 입지도 주지도 않음 (공허는 예외) */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isPending(player)
                && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = byEntity.getDamager() instanceof Player p ? p
                    : byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
            if (attacker != null && isPending(attacker)) event.setCancelled(true);
        }
    }
}
