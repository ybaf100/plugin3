package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.FileLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 손에 든 아이템 + F(손 바꾸기) 기능.
 * - 양털(모든 색): 들고 있는 개수만큼 하얀 현수막으로 1:1 교환
 * - 일반 나침반: 현재 좌표를 채팅으로 확인하고 "좌표 확인이 완료된 나침반" 설명을 붙임 (나침반당 1회)
 *   이미 확인한 나침반이면 안내만 하고 손 바꾸기는 그대로 진행. 자석석 나침반/회복 나침반은 해당 없음
 * 모든 교환/확인은 plugins/GroupSurvivalSystem/logs/시스템/ 에 기록.
 */
public final class HandSwapActionFeature implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final FileLog log;
    private final boolean woolToBanner;
    private final boolean coordinateCheck;
    private final String checkedLore;
    private final String checkedLorePlain;
    private final String coordinateMessage;
    private final String alreadyCheckedMessage;

    public HandSwapActionFeature(JavaPlugin plugin, FileLog log) {
        this.log = log;
        var config = plugin.getConfig();
        this.woolToBanner = config.getBoolean("wool-to-banner.enabled", true);
        this.coordinateCheck = config.getBoolean("compass.coordinate-check", true);
        this.checkedLore = config.getString("compass.checked-lore", "&7좌표 확인이 완료된 나침반");
        this.checkedLorePlain = PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(checkedLore)).trim();
        this.coordinateMessage = config.getString("compass.coordinate-message", "&a%x%, %y%, %z%");
        this.alreadyCheckedMessage = config.getString("compass.already-checked-message", "&7이미 좌표 확인이 완료된 나침반입니다.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        ItemStack tool = inventory.getItemInMainHand();
        Material type = tool.getType();

        if (woolToBanner && Tag.WOOL.isTagged(type)) {
            event.setCancelled(true);
            int amount = tool.getAmount();
            inventory.setItemInMainHand(null);
            Map<Integer, ItemStack> leftover = inventory.addItem(new ItemStack(Material.WHITE_BANNER, amount));
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            log.write("시스템/배너.log", FileLog.now() + "에 " + player.getName() + "가 "
                    + FileLog.format(player.getLocation()) + "에서 배너 " + amount + "개를 교환");
            return;
        }

        if (coordinateCheck && type == Material.COMPASS && !isLodestoneCompass(tool)) {
            Location loc = player.getLocation();
            if (isChecked(tool)) {
                // Skript 원본과 같이 이벤트는 취소하지 않음 (손 바꾸기는 그대로 진행)
                player.sendMessage(LEGACY.deserialize(alreadyCheckedMessage));
                String line = FileLog.now() + "에 " + player.getName() + "가 " + FileLog.format(loc) + "에서 좌표 확인 실패";
                log.write("시스템/나침반좌표확인실패/통합.log", line);
                log.write("시스템/나침반좌표확인실패/" + FileLog.safeName(player.getName()) + ".log", line);
                return;
            }
            event.setCancelled(true);
            ItemMeta meta = tool.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(" "));
            lore.add(LEGACY.deserialize(checkedLore));
            meta.lore(lore);
            tool.setItemMeta(meta);
            inventory.setItemInMainHand(tool);
            player.sendMessage(LEGACY.deserialize(coordinateMessage
                    .replace("%x%", number(loc.getX()))
                    .replace("%y%", number(loc.getY()))
                    .replace("%z%", number(loc.getZ()))));
            log.write("시스템/나침반좌표확인.log", FileLog.now() + "에 " + player.getName() + "가 "
                    + FileLog.format(loc) + "에서 좌표 확인");
        }
    }

    private boolean isChecked(ItemStack item) {
        List<Component> lore = item.hasItemMeta() ? item.getItemMeta().lore() : null;
        if (lore == null) return false;
        for (Component line : lore) {
            if (PlainTextComponentSerializer.plainText().serialize(line).contains(checkedLorePlain)) return true;
        }
        return false;
    }

    /** Skript 처럼 소수점 둘째 자리까지, 불필요한 0 은 제거 */
    private static String number(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        if (text.contains(".")) text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }

    private static boolean isLodestoneCompass(ItemStack item) {
        return item.getItemMeta() instanceof CompassMeta meta && meta.hasLodestone();
    }
}
