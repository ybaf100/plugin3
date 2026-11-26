package dev.groupsurvival.gss.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/** /nv - 영구 야간투시. 우유/토템/사망 등으로 사라지지 않음. */
public final class NightVisionFeature implements Listener, CommandExecutor {

    private final Plugin plugin;
    private final NamespacedKey key;

    public NightVisionFeature(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "night_vision");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        boolean enable = !isEnabled(player);
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, enable);
        if (enable) {
            apply(player);
            player.sendActionBar(Component.text("야간투시 켜짐", NamedTextColor.GREEN));
        } else {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            player.sendActionBar(Component.text("야간투시 꺼짐", NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean isEnabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, false);
    }

    private void apply(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION, 0, false, false, true));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (isEnabled(event.getPlayer())) apply(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isEnabled(player)) {
            player.getScheduler().runDelayed(plugin, task -> {
                if (player.isOnline()) apply(player);
            }, null, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getModifiedType() != PotionEffectType.NIGHT_VISION) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        EntityPotionEffectEvent.Cause cause = event.getCause();
        // 플러그인(=/nv 끄기)과 사망은 허용, 사망 후에는 리스폰 때 다시 적용
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN || cause == EntityPotionEffectEvent.Cause.DEATH) return;
        if (isEnabled(player)) event.setCancelled(true);
    }
}
