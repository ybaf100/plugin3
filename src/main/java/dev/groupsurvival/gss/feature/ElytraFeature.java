package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.FileLog;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 겉날개 금지.
 * - 금지 중에는 겉날개가 걸린 액자를 어떤 방법으로도 떼거나 부술 수 없음
 * - 어떻게든 얻었더라도 활공 불가 (glider 컴포넌트를 가진 모든 아이템 포함)
 */
public final class ElytraFeature implements Listener {

    private final JavaPlugin plugin;
    private final FileLog log;
    private volatile boolean allowed;

    public ElytraFeature(JavaPlugin plugin, FileLog log) {
        this.plugin = plugin;
        this.log = log;
        this.allowed = plugin.getConfig().getBoolean("elytra.allowed", false);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        plugin.getConfig().set("elytra.allowed", allowed);
        plugin.saveConfig();
        if (!allowed) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.getScheduler().run(plugin, task -> {
                    if (player.isGliding()) player.setGliding(false);
                }, null);
            }
        }
    }

    private boolean holdsElytra(Entity entity) {
        if (!(entity instanceof ItemFrame frame)) return false;
        ItemStack item = frame.getItem();
        return item.getType() == Material.ELYTRA;
    }

    /** 시스템/겉날개.log 에 "겉날개 획득 시도" 기록 */
    private void logAttempt(Player player) {
        if (player != null) log.write("시스템/겉날개.log", FileLog.line(player, player.getLocation(), "겉날개 획득 시도"));
    }

    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    // ── 활공 차단 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!allowed && event.isGliding()) event.setCancelled(true);
    }

    // ── 액자 보호 (펀치, 화살, 폭발, 모든 피해) ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameDamage(EntityDamageEvent event) {
        if (!allowed && holdsElytra(event.getEntity())) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity) logAttempt(responsiblePlayer(byEntity.getDamager()));
        }
    }

    // ── 액자 파괴 (지지 블록 파괴, 피스톤, 폭발, 엔티티 등 모든 원인) ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameBreak(HangingBreakEvent event) {
        if (!allowed && holdsElytra(event.getEntity())) {
            event.setCancelled(true);
            if (event instanceof HangingBreakByEntityEvent byEntity) logAttempt(responsiblePlayer(byEntity.getRemover()));
        }
    }

    // ── 액자 아이템 변경 (꺼내기) ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFrameChange(PlayerItemFrameChangeEvent event) {
        if (allowed) return;
        if (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE
                && holdsElytra(event.getItemFrame())) {
            event.setCancelled(true);
            logAttempt(event.getPlayer());
        }
    }

    // ── 크리에이티브 휠 클릭으로 복사 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPick(PlayerPickEntityEvent event) {
        if (!allowed && holdsElytra(event.getEntity())) {
            event.setCancelled(true);
            logAttempt(event.getPlayer());
        }
    }
}
