package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.FileLog;
import java.util.Map;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 활동 기록 (예전 Skript 로그와 같은 파일 구조).
 * - 시스템/접속.log, 시스템/접속/<플레이어>.log
 * - 시스템/첫 접속.log, 시스템/첫 접속 좌표제외.log
 * - 시스템/사망.log
 * - 시스템/침대/통합.log, 시스템/침대/<색>.log
 */
public final class ActivityLogFeature implements Listener {

    private static final Map<DyeColor, String> COLOR_NAMES = Map.ofEntries(
            Map.entry(DyeColor.WHITE, "흰색"),
            Map.entry(DyeColor.ORANGE, "주황색"),
            Map.entry(DyeColor.MAGENTA, "자홍색"),
            Map.entry(DyeColor.LIGHT_BLUE, "하늘색"),
            Map.entry(DyeColor.YELLOW, "노란색"),
            Map.entry(DyeColor.LIME, "연두색"),
            Map.entry(DyeColor.PINK, "분홍색"),
            Map.entry(DyeColor.GRAY, "회색"),
            Map.entry(DyeColor.LIGHT_GRAY, "회백색"),
            Map.entry(DyeColor.CYAN, "청록색"),
            Map.entry(DyeColor.PURPLE, "보라색"),
            Map.entry(DyeColor.BLUE, "파란색"),
            Map.entry(DyeColor.BROWN, "갈색"),
            Map.entry(DyeColor.GREEN, "초록색"),
            Map.entry(DyeColor.RED, "빨간색"),
            Map.entry(DyeColor.BLACK, "검은색"));

    private final FileLog log;

    public ActivityLogFeature(FileLog log) {
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        String line = FileLog.line(player, loc, "접속");
        log.write("시스템/접속.log", line);
        log.write("시스템/접속/" + FileLog.safeName(player.getName()) + ".log", line);
        if (!player.hasPlayedBefore()) {
            log.write("시스템/첫 접속.log", FileLog.line(player, loc, "첫 접속"));
            log.write("시스템/첫 접속 좌표제외.log", FileLog.lineWithoutLocation(player, "첫 접속"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        log.write("시스템/사망.log", FileLog.line(player, player.getLocation(), "사망"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (!Tag.BEDS.isTagged(type)) return;
        Player player = event.getPlayer();
        String line = FileLog.line(player, player.getLocation(), "침대 설치");
        log.write("시스템/침대/통합.log", line);
        String color = bedColor(type);
        if (color != null) log.write("시스템/침대/" + color + ".log", line);
    }

    private static String bedColor(Material bed) {
        String name = bed.name(); // 예: LIGHT_BLUE_BED
        String dye = name.substring(0, name.length() - "_BED".length());
        try {
            return COLOR_NAMES.get(DyeColor.valueOf(dye));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
