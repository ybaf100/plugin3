package dev.groupsurvival.gss.feature;

import java.util.Map;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 손에 든 아이템 + F(손 바꾸기) 기능.
 * - 일반 나침반: 현재 좌표를 채팅으로 확인, 나침반 1개 소모 (자석석/회복 나침반 제외)
 * - 양털(모든 색): 들고 있는 개수만큼 하얀 현수막으로 1:1 변환
 */
public final class HandSwapActionFeature implements Listener {

    private final boolean coordinateCheck;
    private final String coordinateMessage;
    private final boolean woolToBanner;

    public HandSwapActionFeature(JavaPlugin plugin) {
        var config = plugin.getConfig();
        this.coordinateCheck = config.getBoolean("compass.coordinate-check", true);
        this.coordinateMessage = config.getString("compass.coordinate-message",
                "&e[나침반] &f현재 좌표: &aX %x&f, &aY %y&f, &aZ %z");
        this.woolToBanner = config.getBoolean("wool-to-banner.enabled", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        Material type = hand.getType();

        if (coordinateCheck && type == Material.COMPASS && !isLodestoneCompass(hand)) {
            event.setCancelled(true);
            hand.setAmount(hand.getAmount() - 1);
            inventory.setItemInMainHand(hand.getAmount() > 0 ? hand : null);
            Location loc = player.getLocation();
            String text = coordinateMessage
                    .replace("%x", String.valueOf(loc.getBlockX()))
                    .replace("%y", String.valueOf(loc.getBlockY()))
                    .replace("%z", String.valueOf(loc.getBlockZ()));
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
            return;
        }

        if (woolToBanner && Tag.WOOL.isTagged(type)) {
            event.setCancelled(true);
            int amount = hand.getAmount();
            inventory.setItemInMainHand(null);
            Map<Integer, ItemStack> leftover = inventory.addItem(new ItemStack(Material.WHITE_BANNER, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    private static boolean isLodestoneCompass(ItemStack item) {
        return item.getItemMeta() instanceof CompassMeta meta && meta.hasLodestone();
    }
}
