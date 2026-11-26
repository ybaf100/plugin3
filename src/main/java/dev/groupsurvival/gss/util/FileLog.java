package dev.groupsurvival.gss.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * plugins/GroupSurvivalSystem/logs/ 아래에 로그 파일을 남김 (Skript 의 log ... to "파일" 과 같은 역할).
 * 파일 쓰기는 별도 스레드 하나에서 순서대로 처리.
 */
public final class FileLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    public static String now() {
        return LocalDateTime.now().format(TIME);
    }

    public static String format(Location location) {
        return String.format(java.util.Locale.ROOT, "%s (%.2f, %.2f, %.2f)", location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
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
