package dev.groupsurvival.gss.feature;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 엔더드래곤 최대 체력 배율 적용 (기본 5배). */
public final class DragonFeature implements Listener {

    /** 바닐라 엔더드래곤 기본 최대 체력 */
    private static final double BASE_HEALTH = 200.0;

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final double multiplier;

    public DragonFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dragon_scaled");
        this.multiplier = plugin.getConfig().getDouble("dragon.health-multiplier", 5.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            dragon.getScheduler().runDelayed(plugin, task -> apply(dragon), null, 1L);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof EnderDragon dragon) apply(dragon);
        }
    }

    private void apply(EnderDragon dragon) {
        if (!dragon.isValid() || dragon.isDead()) return;
        AttributeInstance attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double target = BASE_HEALTH * multiplier;
        boolean scaled = dragon.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
        if (!scaled) {
            attribute.setBaseValue(target);
            dragon.setHealth(Math.min(target, attribute.getValue()));
            dragon.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        } else if (attribute.getBaseValue() != target) {
            // 배율 설정이 바뀐 경우 체력 비율 유지
            double ratio = dragon.getHealth() / attribute.getValue();
            attribute.setBaseValue(target);
            dragon.setHealth(Math.max(1.0, Math.min(attribute.getValue(), attribute.getValue() * ratio)));
        }
    }
}
