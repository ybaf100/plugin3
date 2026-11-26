package dev.groupsurvival.gss.effect;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 시스템이 지급하는 영구 효과(야간투시, 성급함 등) 관리.
 * - 무한 지속, 입자 없음, 아이콘만 표시
 * - 접속/리스폰 때 다시 지급, 우유/토템 등으로 지워지지 않음
 */
public final class PermanentEffects implements Listener {

    /** 효과 하나의 지급 조건. amplifier 가 null 이면 지급하지 않음. */
    public interface Source {
        PotionEffectType type();

        Integer amplifier(Player player);
    }

    private final Plugin plugin;
    private final List<Source> sources = new ArrayList<>();

    public PermanentEffects(Plugin plugin) {
        this.plugin = plugin;
    }

    public void add(Source source) {
        sources.add(source);
    }

    public static PotionEffect systemEffect(PotionEffectType type, int amplifier) {
        // ambient=false, particles=false (입자 꺼짐), icon=true
        return new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, false, false, true);
    }

    private static boolean isSystemEffect(PotionEffect effect) {
        return effect != null && effect.isInfinite() && !effect.hasParticles();
    }

    /** 현재 설정대로 효과를 지급하거나 회수. */
    public void refresh(Player player) {
        for (Source source : sources) {
            Integer amplifier = source.amplifier(player);
            PotionEffect current = player.getPotionEffect(source.type());
            if (amplifier == null) {
                if (isSystemEffect(current)) player.removePotionEffect(source.type());
            } else if (current == null || !isSystemEffect(current) || current.getAmplifier() != amplifier) {
                player.addPotionEffect(systemEffect(source.type(), amplifier));
            }
        }
    }

    private Source sourceFor(PotionEffectType type) {
        for (Source source : sources) {
            if (source.type() == type) return source;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) refresh(player);
        }, null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        Source source = sourceFor(event.getModifiedType());
        if (source == null || source.amplifier(player) == null) return;
        EntityPotionEffectEvent.Cause cause = event.getCause();
        // 플러그인(=끄기)과 사망은 허용. 사망 후에는 리스폰 때 다시 지급
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN || cause == EntityPotionEffectEvent.Cause.DEATH) return;
        if (cause == EntityPotionEffectEvent.Cause.EXPIRATION) {
            // 다른 효과(신호기 등)가 끝나며 사라진 경우 다음 틱에 다시 지급
            player.getScheduler().run(plugin, task -> refresh(player), null);
            return;
        }
        event.setCancelled(true);
    }
}
