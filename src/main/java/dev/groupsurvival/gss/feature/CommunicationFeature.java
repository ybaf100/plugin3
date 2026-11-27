package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.ActionBars;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 통신 제한.
 * - 채팅: 반경 안에 있는 플레이어에게만 "[지역] <이름> 내용" 형태로 보임 (서명 없는 시스템 메시지 → 신고 불가)
 * - 명령어: 허용 목록(/nv)만
 * - 책/모루 이름 변경/이름표: 설정으로 금지 가능 (기본 허용)
 * - 표지판: 오버월드 0,0 주변 청크에서만, 네더/엔드는 불가
 */
public final class CommunicationFeature implements Listener {

    private static final String BYPASS_COMMANDS = "gss.bypass.commands";
    private static final List<String> DEFAULT_OVERWORLD_SIGN = List.of("표지판은", "0,0 근처에서만", "사용이 가능합니다", "");
    private static final List<String> DEFAULT_OTHER_SIGN = List.of("표지판은", "오버월드에서만", "사용이 가능합니다", "");

    private final JavaPlugin plugin;
    private final boolean blockBooks;
    private final boolean blockAnvil;
    private final boolean blockNameTags;
    private final Set<String> allowedCommands = new HashSet<>();
    private final double chatRadiusSq;
    private final Component chatPrefix;
    private final int signRadius;
    private final List<Component> overworldSign;
    private final List<Component> otherDimensionSign;

    public CommunicationFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();
        this.blockBooks = config.getBoolean("communication.block-books", false);
        this.blockAnvil = config.getBoolean("communication.block-anvil-rename", false);
        this.blockNameTags = config.getBoolean("communication.block-name-tags", false);
        for (String command : config.getStringList("communication.allowed-commands")) {
            allowedCommands.add(command.toLowerCase(Locale.ROOT));
        }
        double radius = Math.max(1, config.getDouble("communication.local-chat.radius", 32));
        this.chatRadiusSq = radius * radius;
        this.chatPrefix = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(config.getString("communication.local-chat.prefix", "[지역] "));
        this.signRadius = Math.max(0, config.getInt("communication.signs.zone-radius-chunks", 1));
        this.overworldSign = lines(config.getStringList("communication.signs.replacement-overworld"), DEFAULT_OVERWORLD_SIGN);
        this.otherDimensionSign = lines(config.getStringList("communication.signs.replacement-other-dimensions"), DEFAULT_OTHER_SIGN);
    }

    private static List<Component> lines(List<String> configured, List<String> fallback) {
        List<String> source = configured.isEmpty() ? fallback : configured;
        return source.stream().limit(4).map(line -> (Component) Component.text(line)).toList();
    }

    // ── 근처 채팅 ──
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
        Player sender = event.getPlayer();
        Component line = Component.empty()
                .append(chatPrefix)
                .append(Component.text("<"))
                .append(sender.name())
                .append(Component.text("> "))
                .append(event.message());
        // 위치 확인은 메인 스레드에서
        sender.getScheduler().run(plugin, task -> {
            if (!sender.isOnline()) return;
            Location origin = sender.getLocation();
            World world = origin.getWorld();
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (viewer.getWorld() == world && viewer.getLocation().distanceSquared(origin) <= chatRadiusSq) {
                    viewer.sendMessage(line);
                }
            }
            plugin.getServer().getConsoleSender().sendMessage(line);
        }, null);
    }

    // ── 명령어 ──
    private static String baseLabel(String raw) {
        String label = raw.toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_COMMANDS)) return;
        String label = event.getMessage().substring(1).split(" ", 2)[0];
        if (!allowedCommands.contains(baseLabel(label))) {
            event.setCancelled(true);
            ActionBars.send(event.getPlayer(), Component.text("사용할 수 없는 명령어입니다.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandTree(PlayerCommandSendEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_COMMANDS)) return;
        event.getCommands().removeIf(command -> !allowedCommands.contains(baseLabel(command)));
    }

    // ── 책 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBook(PlayerEditBookEvent event) {
        if (!blockBooks) return;
        event.setCancelled(true);
        ActionBars.send(event.getPlayer(), Component.text("책은 작성할 수 없습니다.", NamedTextColor.RED));
    }

    // ── 모루 이름 변경 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!blockAnvil) return;
        String rename = event.getView().getRenameText();
        if (rename == null || rename.isBlank()) return;
        ItemStack first = event.getInventory().getFirstItem();
        if (first != null && !first.getType().isAir()) {
            String current = PlainTextComponentSerializer.plainText().serialize(first.effectiveName());
            if (current.equals(rename)) return;
        }
        event.setResult(null);
    }

    // ── 이름표 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onNameTag(PlayerInteractEntityEvent event) {
        if (!blockNameTags) return;
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (item.getType() == Material.NAME_TAG && item.hasItemMeta() && item.getItemMeta().hasCustomName()) {
            event.setCancelled(true);
            ActionBars.send(event.getPlayer(), Component.text("이름표는 사용할 수 없습니다.", NamedTextColor.RED));
        }
    }

    // ── 표지판 ──
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Block block = event.getBlock();
        List<Component> replacement;
        if (block.getWorld().getEnvironment() == World.Environment.NORMAL) {
            int chunkX = block.getX() >> 4;
            int chunkZ = block.getZ() >> 4;
            if (Math.abs(chunkX) <= signRadius && Math.abs(chunkZ) <= signRadius) return;
            replacement = overworldSign;
        } else {
            replacement = otherDimensionSign;
        }

        boolean hasText = false;
        for (Component line : event.lines()) {
            if (line != null && !PlainTextComponentSerializer.plainText().serialize(line).isBlank()) {
                hasText = true;
                break;
            }
        }
        if (!hasText) return;
        for (int i = 0; i < 4; i++) {
            event.line(i, i < replacement.size() ? replacement.get(i) : Component.empty());
        }
    }
}
