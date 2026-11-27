package dev.groupsurvival.gss.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * plugins/GroupSurvivalSystem/logs/ 아래에 로그 파일을 남김 (예전 Skript 의 log ... to "파일" 과 같은 구조).
 * 한 줄 형식 (한국시간 기준):
 *   [2026-11-26] 17:30:45에 이름가 x: 1752, y: 95, z: -1186, yaw: -92, pitch: 11 in 'world'에서 행동
 * 파일 쓰기는 별도 스레드 하나에서 순서대로 처리.
 */
public final class FileLog {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path root;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GSS-FileLog");
        thread.setDaemon(true);
        return thread;
    });

    public FileLog(Path dataFolder, Logger logger) {
        this.root = dataFolder.resolve("logs");
        this.logger = logger;
    }

    /** "[날짜] 시각에 " (한국시간) */
    private static String timestamp() {
        ZonedDateTime now = ZonedDateTime.now(KOREA);
        return "[" + now.format(DATE) + "] " + now.format(TIME) + "에 ";
    }

    /** Skript 위치 형식 (예: x: 1752, y: 95, z: -1186, yaw: -92, pitch: 11 in 'world') */
    public static String location(Location loc) {
        return "x: " + Math.round(loc.getX()) + ", y: " + Math.round(loc.getY()) + ", z: " + Math.round(loc.getZ())
                + ", yaw: " + Math.round(loc.getYaw()) + ", pitch: " + Math.round(loc.getPitch())
                + " in '" + (loc.getWorld() == null ? "?" : loc.getWorld().getName()) + "'";
    }

    /** "[날짜] 시각에 이름가 위치에서 행동" */
    public static String line(Player player, Location loc, String action) {
        return timestamp() + player.getName() + "가 " + location(loc) + "에서 " + action;
    }

    /** "[날짜] 시각에 이름가 위치에서 행동" (오프라인 플레이어 등 이름만 있을 때) */
    public static String line(String name, Location loc, String action) {
        return timestamp() + name + "가 " + location(loc) + "에서 " + action;
    }

    /** "[날짜] 시각에 내용" */
    public static String line(String text) {
        return timestamp() + text;
    }

    /** "[날짜] 시각에 이름가 행동" (좌표 제외) */
    public static String lineWithoutLocation(Player player, String action) {
        return timestamp() + player.getName() + "가 " + action;
    }

    /** 파일 이름에 쓸 수 없는 문자를 제거 */
    public static String safeName(String name) {
        return name.replaceAll("[^0-9A-Za-z_가-힣-]", "_");
    }

    /** @param file logs 폴더 기준 상대 경로 (예: "시스템/배너.log") */
    public void write(String file, String line) {
        writer.execute(() -> {
            try {
                Path path = root.resolve(file);
                Files.createDirectories(path.getParent());
                Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                // 한글 경로 오류는 대개 서버 OS 로케일이 UTF-8 이 아닐 때 발생 (예: LANG=C.UTF-8 로 실행)
                logger.warning("로그 파일 기록 실패 (" + file + "): " + e);
            }
        });
    }

    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
