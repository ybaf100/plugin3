package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.integration.LunarIntegration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 바닐라 + Lunar Client 만 허용.
 * 클라이언트 브랜드와 등록 채널, Apollo 핸드셰이크로 판별 (클라이언트가 보고하는 값이므로 위조는 막을 수 없음).
 */
public final class ClientCheckFeature implements Listener {

    private final JavaPlugin plugin;
    private final boolean apolloPresent;
    private final Set<String> allowedBrands = new HashSet<>();
    private final boolean allowLunar;
    private final long delay;
    private final String kickMessage;

    public ClientCheckFeature(JavaPlugin plugin, boolean apolloPresent) {
        this.plugin = plugin;
        this.apolloPresent = apolloPresent;
        var config = plugin.getConfig();
        for (String brand : config.getStringList("client-check.allowed-brands")) {
            allowedBrands.add(brand.toLowerCase(Locale.ROOT));
        }
        if (allowedBrands.isEmpty()) allowedBrands.add("vanilla");
        this.allowLunar = config.getBoolean("client-check.allow-lunar", true);
        this.delay = Math.max(20L, config.getLong("client-check.check-delay-ticks", 100L));
        this.kickMessage = config.getString("client-check.kick-message",
                "이 서버는 바닐라 또는 Lunar Client 로만 접속할 수 있습니다.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gss.bypass.clientcheck")) return;
        player.getScheduler().runDelayed(plugin, task -> check(player), null, delay);
    }

    private void check(Player player) {
        if (!player.isOnline()) return;
        String brand = player.getClientBrandName();
        String lower = brand == null ? "" : brand.toLowerCase(Locale.ROOT);

        if (allowedBrands.contains(lower)) return;
        if (allowLunar && isLunar(player, lower)) return;

        plugin.getLogger().info(player.getName() + " 접속 차단 (클라이언트 브랜드: " + brand + ")");
        player.kick(Component.text(kickMessage));
    }

    private boolean isLunar(Player player, String brand) {
        if (brand.contains("lunar")) return true;
        for (String channel : player.getListeningPluginChannels()) {
            if (channel.toLowerCase(Locale.ROOT).startsWith("lunar")) return true;
        }
        if (apolloPresent) {
            try {
                return LunarIntegration.isLunarPlayer(player.getUniqueId());
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
