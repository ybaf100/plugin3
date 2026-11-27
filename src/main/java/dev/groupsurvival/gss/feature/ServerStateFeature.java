package dev.groupsurvival.gss.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 서버 상태 + 상태별 MOTD (서버 목록 문구).
 * 상태 우선순위: 점검 중 > 오픈 전 > 화이트리스트 > 운영 중
 * - 점검 중 / 오픈 전에는 접속을 막고(OP 제외), MOTD 와 접속 거부 메시지로 이유를 알려줌
 * - 운영 중에는 월드보더 단계, 크기, 다음 단계까지 남은 시간을 표시
 */
public final class ServerStateFeature implements Listener {

    public enum State { MAINTENANCE, BEFORE_OPEN, WHITELIST, RUNNING }

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final WorldBorderFeature border;
    private final ZonedDateTime openAt;
    private volatile boolean maintenance;
    private volatile String reason;

    public ServerStateFeature(JavaPlugin plugin, WorldBorderFeature border) {
        this.plugin = plugin;
        this.border = border;
        var config = plugin.getConfig();
        ZonedDateTime parsed = null;
        String raw = config.getString("server-state.open-at", "");
        if (raw != null && !raw.isBlank()) {
            try {
                parsed = LocalDateTime.parse(raw.trim(), INPUT).atZone(KOREA);
            } catch (DateTimeParseException e) {
                plugin.getLogger().warning("server-state.open-at 형식이 잘못됨 (예: 2026-11-30 20:00): " + raw);
            }
        }
        this.openAt = parsed;
        this.maintenance = config.getBoolean("server-state.maintenance", false);
        this.reason = config.getString("server-state.maintenance-reason", "점검 중입니다");
    }

    public State state() {
        if (maintenance) return State.MAINTENANCE;
        if (openAt != null && ZonedDateTime.now(KOREA).isBefore(openAt)) return State.BEFORE_OPEN;
        if (plugin.getServer().hasWhitelist()) return State.WHITELIST;
        return State.RUNNING;
    }

    // ───────────────────────── 서버 목록 MOTD ─────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("motd.enabled", true)) return;
        String line1 = plugin.getConfig().getString("motd.line1", "&6&lGroup Survival System");
        String line2 = switch (state()) {
            case MAINTENANCE -> plugin.getConfig().getString("motd.maintenance", "&c점검 중 &7· &f%reason%");
            case BEFORE_OPEN -> plugin.getConfig().getString("motd.before-open", "&e오픈 예정 &f%open% &7(%open_countdown% 남음)");
            case WHITELIST -> plugin.getConfig().getString("motd.whitelist", "&6허가된 사람만 접속할 수 있습니다");
            case RUNNING -> border != null && border.nextStage() == null
                    ? plugin.getConfig().getString("motd.running-final", "&a%stage%단계 &7· &f%size%")
                    : plugin.getConfig().getString("motd.running", "&a%stage%단계 &7· &f%size% &7· 다음 단계까지 &f%countdown%");
        };
        event.motd(LEGACY.deserialize(apply(line1) + "\n" + apply(line2)));
    }

    // ───────────────────────── 접속 차단 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        State state = state();
        if (state != State.MAINTENANCE && state != State.BEFORE_OPEN) return;
        if (plugin.getServer().getOfflinePlayer(event.getUniqueId()).isOp()) return;
        String message = state == State.MAINTENANCE
                ? plugin.getConfig().getString("motd.kick-maintenance", "&c지금은 점검 중입니다.\n\n&f%reason%")
                : plugin.getConfig().getString("motd.kick-before-open", "&e아직 오픈 전입니다.\n\n&f오픈: %open% &7(%open_countdown% 남음)");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, LEGACY.deserialize(apply(message)));
    }

    // ───────────────────────── 점검 (콘솔) ─────────────────────────

    public void setMaintenance(boolean on, String newReason) {
        this.maintenance = on;
        if (newReason != null && !newReason.isBlank()) this.reason = newReason;
        plugin.getConfig().set("server-state.maintenance", on);
        plugin.getConfig().set("server-state.maintenance-reason", reason);
        plugin.saveConfig();
        if (!on) return;
        Component kick = LEGACY.deserialize(apply(plugin.getConfig().getString("motd.kick-maintenance",
                "&c지금은 점검 중입니다.\n\n&f%reason%")));
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (!player.isOp()) player.kick(kick);
        }
    }

    public List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("[GSS] 서버 상태: " + switch (state()) {
            case MAINTENANCE -> "점검 중 (" + reason + ")";
            case BEFORE_OPEN -> "오픈 전";
            case WHITELIST -> "화이트리스트";
            case RUNNING -> "운영 중";
        });
        lines.add("  오픈 시각: " + (openAt == null ? "설정 안 됨 (바로 오픈)" : openAt.format(INPUT) + " (한국시간)"));
        return lines;
    }

    // ───────────────────────── 문구 치환 ─────────────────────────

    private String apply(String text) {
        if (text == null) return "";
        String result = text
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%open%", openAt == null ? "-" : openAt.format(SHOW))
                .replace("%open_countdown%", openAt == null ? "-" : countdown(openAt));
        if (border != null) {
            WorldBorderFeature.Stage current = border.targetStage();
            WorldBorderFeature.Stage next = border.nextStage();
            result = result
                    .replace("%stage%", current == null ? "-" : String.valueOf(current.number()))
                    .replace("%size%", current == null ? "-" : WorldBorderFeature.format(current.size()))
                    .replace("%next%", next == null ? "-" : next.at().format(SHOW))
                    .replace("%countdown%", next == null ? "-" : countdown(next.at()));
        } else {
            result = result.replace("%stage%", "-").replace("%size%", "-").replace("%next%", "-").replace("%countdown%", "-");
        }
        return result;
    }

    /** 남은 시간 (예: 2일 3시간, 5시간 20분, 15분, 1분 미만) */
    static String countdown(ZonedDateTime target) {
        Duration left = Duration.between(ZonedDateTime.now(KOREA), target);
        if (left.isNegative() || left.toMinutes() < 1) return "1분 미만";
        long days = left.toDays(), hours = left.toHoursPart(), minutes = left.toMinutesPart();
        if (days > 0) return days + "일 " + hours + "시간";
        if (hours > 0) return hours + "시간 " + minutes + "분";
        return minutes + "분";
    }
}
