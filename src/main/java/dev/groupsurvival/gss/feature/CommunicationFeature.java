package dev.groupsurvival.gss.feature;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
 * 모든 통신 수단 차단: 채팅, 명령어(/msg 등), 책, 모루 이름 변경, 이름표.
 * 표지판은 0,0 청크 주변(기본 3x3 청크)에서만 사용 가능.
 */
public final class CommunicationFeature implements Listener {

    private static final String BYPASS_COMMANDS = "gss.bypass.commands";
    private static final String BYPASS_CHAT = "gss.bypass.chat";

    private final boolean blockChat;
    private final boolean blockBooks;
    private final boolean blockAnvil;
    private final boolean blockNameTags;
    private final Set<String> allowedCommands = new HashSet<>();
    private final int signRadius;
    private final Set<String> signWorlds = new HashSet<>();
    private final List<Component> signReplacement;

    public CommunicationFeature(JavaPlugin plugin) {
        var config = plugin.getConfig();
        this.blockChat = config.getBoolean("communication.block-chat", true);
        this.blockBooks = config.getBoolean("communication.block-books", true);
        this.blockAnvil = config.getBoolean("communication.block-anvil-rename", true);
        this.blockNameTags = config.getBoolean("communication.block-name-tags", true);
        for (String command : config.getStringList("communication.allowed-commands")) {
            allowedCommands.add(command.toLowerCase(Locale.ROOT));
        }
        this.signRadius = Math.max(0, config.getInt("communication.signs.zone-radius-chunks", 1));
        for (String world : config.getStringList("communication.signs.worlds")) {
            signWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        List<String> lines = config.getStringList("communication.signs.replacement");
        if (lines.isEmpty()) lines = List.of("표지판은", "0,0 근처 청크에서만", "사용이 가능합니다", "");
        this.signReplacement = lines.stream().limit(4).map(line -> (Component) Component.text(line)).toList();
    }

    // ── 채팅 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!blockChat || event.getPlayer().hasPermission(BYPASS_CHAT)) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("채팅은 사용할 수 없습니다.", NamedTextColor.RED));
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
        String message = event.getMessage();
        String label = message.substring(1).split(" ", 2)[0];
        if (!allowedCommands.contains(baseLabel(label))) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("사용할 수 없는 명령어입니다.", NamedTextColor.RED));
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
        event.getPlayer().sendActionBar(Component.text("책은 작성할 수 없습니다.", NamedTextColor.RED));
    }

    // ── 모루 이름 변경 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!blockAnvil) return;
        String rename = event.getView().getRenameText();
        if (rename == null || rename.isBlank()) return; // 이름 제거/변경 없음은 허용
        ItemStack first = event.getInventory().getFirstItem();
        if (first != null && !first.getType().isAir()) {
            String current = PlainTextComponentSerializer.plainText().serialize(first.effectiveName());
            if (current.equals(rename)) return; // 이름을 바꾸지 않은 수리/인챈트
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
            event.getPlayer().sendActionBar(Component.text("이름표는 사용할 수 없습니다.", NamedTextColor.RED));
        }
    }

    // ── 표지판 ──
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Block block = event.getBlock();
        if (!signWorlds.isEmpty() && !signWorlds.contains(block.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        if (Math.abs(chunkX) <= signRadius && Math.abs(chunkZ) <= signRadius) return;

        boolean hasText = false;
        for (Component line : event.lines()) {
            if (line != null && !PlainTextComponentSerializer.plainText().serialize(line).isBlank()) {
                hasText = true;
                break;
            }
        }
        if (!hasText) return;
        for (int i = 0; i < 4; i++) {
            event.line(i, i < signReplacement.size() ? signReplacement.get(i) : Component.empty());
        }
    }
}
