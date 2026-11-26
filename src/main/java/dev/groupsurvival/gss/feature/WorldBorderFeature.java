package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.FileLog;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 오버월드 월드보더 단계 (네더/엔드는 제한 없음).
 * - config 의 단계별 시각(한국시간, "yyyy-MM-dd HH:mm")이 되면 자동으로 다음 단계 크기로 넓어짐 (기본 10분에 걸쳐)
 * - 서버가 꺼져 있는 동안 시각이 지났다면 켜질 때 넓어짐
 * - 적용한 단계는 data.yml 에 저장. 벽은 넓어지기만 하고 줄어들지 않음
 */
public final class WorldBorderFeature {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 단계 하나: 크기(한 변 길이)와 시작 시각 (null = 처음부터) */
    public record Stage(int number, double size, ZonedDateTime at) {
    }

    private final JavaPlugin plugin;
    private final FileLog log;
    private final List<Stage> stages = new ArrayList<>();
    private final double centerX;
    private final double centerZ;
    private final long expandTicks;
    private final File dataFile;
    private final YamlConfiguration data;

    public WorldBorderFeature(JavaPlugin plugin, FileLog log) {
        this.plugin = plugin;
        this.log = log;
        var config = plugin.getConfig();
        this.centerX = config.getDouble("world-border.center-x", 0);
        this.centerZ = config.getDouble("world-border.center-z", 0);
        this.expandTicks = Math.max(0, Math.round(config.getDouble("world-border.expand-minutes", 10) * 60 * 20));
        int number = 0;
        for (Map<?, ?> raw : config.getMapList("world-border.stages")) {
            number++;
            Object sizeValue = raw.get("size");
            if (!(sizeValue instanceof Number size) || size.doubleValue() <= 0) {
                plugin.getLogger().warning("[월드보더] " + number + "단계 size 가 잘못됨: " + sizeValue);
                continue;
            }
            Object atValue = raw.get("at");
            ZonedDateTime at = null;
            if (atValue != null && !String.valueOf(atValue).isBlank()) {
                try {
                    at = LocalDateTime.parse(String.valueOf(atValue).trim(), FORMAT).atZone(KOREA);
                } catch (DateTimeParseException e) {
                    plugin.getLogger().warning("[월드보더] " + number + "단계 시각 형식이 잘못됨 (예: 2026-10-22 16:30): " + atValue);
                    continue;
                }
            }
            stages.add(new Stage(number, size.doubleValue(), at));
        }
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void start() {
        if (stages.isEmpty()) {
            plugin.getLogger().warning("[월드보더] 단계가 없어 월드보더를 적용하지 않습니다.");
            return;
        }
        check();
        // 20초마다 시각 확인
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, 20L * 20, 20L * 20);
    }

    private World overworld() {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) return world;
        }
        return null;
    }

    /** 지금 시각 기준으로 적용되어야 하는 단계 (시각이 지난 마지막 단계) */
    public Stage targetStage() {
        ZonedDateTime now = ZonedDateTime.now(KOREA);
        Stage target = null;
        for (Stage stage : stages) {
            if (stage.at() == null || !now.isBefore(stage.at())) target = stage;
        }
        return target;
    }

    /** 다음에 열릴 단계 (없으면 null) */
    public Stage nextStage() {
        ZonedDateTime now = ZonedDateTime.now(KOREA);
        for (Stage stage : stages) {
            if (stage.at() != null && now.isBefore(stage.at())) return stage;
        }
        return null;
    }

    public int appliedStage() {
        return data.getInt("world-border.applied-stage", 0);
    }

    private void check() {
        World world = overworld();
        Stage target = targetStage();
        if (world == null || target == null) return;
        WorldBorder border = world.getWorldBorder();
        int applied = appliedStage();

        if (applied == 0) {
            // 처음 설정: 바로 그 크기로
            border.setCenter(centerX, centerZ);
            border.setSize(target.size());
            save(target.number());
            plugin.getLogger().info("[월드보더] " + target.number() + "단계 적용: " + format(target.size()));
            log.write("시스템/월드보더.log", "[" + now() + "] " + target.number() + "단계 적용 (" + format(target.size()) + ")");
            return;
        }
        if (target.number() <= applied) return; // 이미 적용됨 (줄어들지 않음)

        double from = border.getSize();
        border.setCenter(centerX, centerZ);
        if (expandTicks > 0) border.changeSize(target.size(), expandTicks);
        else border.setSize(target.size());
        save(target.number());

        String duration = duration(expandTicks);
        plugin.getServer().broadcast(Component.text("[월드보더] ", NamedTextColor.GOLD)
                .append(Component.text(target.number() + "단계로 넓어집니다: " + format(from) + " → " + format(target.size())
                        + (expandTicks > 0 ? " (" + duration + "에 걸쳐)" : ""), NamedTextColor.YELLOW)));
        log.write("시스템/월드보더.log", "[" + now() + "] " + target.number() + "단계로 확장 시작 ("
                + format(from) + " -> " + format(target.size()) + ", " + duration + ")");
    }

    private void save(int stage) {
        data.set("world-border.applied-stage", stage);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[월드보더] data.yml 저장 실패: " + e.getMessage());
        }
    }

    private static String now() {
        return ZonedDateTime.now(KOREA).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String duration(long ticks) {
        long seconds = Math.round(ticks / 20.0);
        if (seconds < 60) return seconds + "초";
        long minutes = seconds / 60, rest = seconds % 60;
        return rest == 0 ? minutes + "분" : minutes + "분 " + rest + "초";
    }

    public static String format(double size) {
        long rounded = Math.round(size);
        return rounded + "x" + rounded;
    }

    /** 콘솔 상태 표시용 */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        World world = overworld();
        lines.add("[GSS] 월드보더 현재 크기: " + (world == null ? "?" : format(world.getWorldBorder().getSize()))
                + " (적용된 단계: " + appliedStage() + ")");
        for (Stage stage : stages) {
            lines.add("  " + stage.number() + "단계 " + format(stage.size()) + " - "
                    + (stage.at() == null ? "처음부터" : stage.at().format(FORMAT) + " (한국시간)"));
        }
        Stage next = nextStage();
        lines.add(next == null ? "  다음 단계 없음" : "  다음: " + next.number() + "단계 " + next.at().format(FORMAT));
        return lines;
    }
}
