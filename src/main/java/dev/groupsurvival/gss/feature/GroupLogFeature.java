package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.FileLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 3명 이상이 오래 함께 있으면 로그만 남김 (패널티 없음). 로그 파일: 시스템/과밀.log
 *
 * 도배 방지:
 * - 한 무리에 대해 "시작" 한 줄(일정 시간 이상 붙어 있을 때)과 "끝" 한 줄(흩어진 뒤)만 남김
 * - 경계를 들락날락하거나 잠깐 흩어졌다 다시 모여도(end-after-seconds 안) 같은 무리로 이어서 셈
 * - 사람이 바뀌어도 2명 이상 겹치면 같은 무리로 봄 (새로 온 사람은 명단에 추가)
 */
public final class GroupLogFeature {

    private static final String FILE = "시스템/과밀.log";

    private static final class Session {
        final Set<UUID> members = new HashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        final long start;
        long lastSeen;
        boolean logged;
        int maxSize;

        Session(long now) {
            this.start = now;
            this.lastSeen = now;
        }
    }

    private final JavaPlugin plugin;
    private final FileLog log;
    private final Predicate<Location> excluded;
    private final double radiusSq;
    private final int minPlayers;
    private final long startAfterMillis;
    private final long endAfterMillis;
    private final List<Session> sessions = new ArrayList<>();

    public GroupLogFeature(JavaPlugin plugin, FileLog log, Predicate<Location> excluded) {
        this.plugin = plugin;
        this.log = log;
        this.excluded = excluded;
        var config = plugin.getConfig();
        double radius = Math.max(2, config.getDouble("group-log.radius", 24));
        this.radiusSq = radius * radius;
        this.minPlayers = Math.max(2, config.getInt("group-log.min-players", 3));
        this.startAfterMillis = Math.max(10, config.getLong("group-log.start-after-seconds", 60)) * 1000L;
        this.endAfterMillis = Math.max(10, config.getLong("group-log.end-after-seconds", 180)) * 1000L;
    }

    public void start() {
        long interval = Math.max(2, plugin.getConfig().getLong("group-log.check-interval-seconds", 10)) * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, interval, interval);
    }

    private void check() {
        long now = System.currentTimeMillis();
        // 세계별로 가까운 플레이어끼리 묶음 (서로 반경 안이면 같은 무리, 연결된 사람끼리 모두 한 무리)
        Map<World, List<Player>> byWorld = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) continue;
            if (excluded.test(player.getLocation())) continue; // 안전구역 안은 세지 않음
            byWorld.computeIfAbsent(player.getWorld(), w -> new ArrayList<>()).add(player);
        }
        List<List<Player>> clusters = new ArrayList<>();
        for (List<Player> players : byWorld.values()) {
            int n = players.size();
            if (n < minPlayers) continue;
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < n; i++) {
                Location a = players.get(i).getLocation();
                for (int j = i + 1; j < n; j++) {
                    if (a.distanceSquared(players.get(j).getLocation()) <= radiusSq) union(parent, i, j);
                }
            }
            Map<Integer, List<Player>> groups = new HashMap<>();
            for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(players.get(i));
            for (List<Player> group : groups.values()) if (group.size() >= minPlayers) clusters.add(group);
        }

        Set<Session> matched = new HashSet<>();
        for (List<Player> cluster : clusters) {
            Session best = null;
            int bestOverlap = 1;
            for (Session session : sessions) {
                if (matched.contains(session)) continue;
                int overlap = 0;
                for (Player player : cluster) if (session.members.contains(player.getUniqueId())) overlap++;
                if (overlap > bestOverlap) {
                    best = session;
                    bestOverlap = overlap;
                }
            }
            if (best == null) {
                best = new Session(now);
                sessions.add(best);
            }
            matched.add(best);
            for (Player player : cluster) {
                best.members.add(player.getUniqueId());
                best.names.add(player.getName());
            }
            best.lastSeen = now;
            best.maxSize = Math.max(best.maxSize, cluster.size());
            if (!best.logged && now - best.start >= startAfterMillis) {
                best.logged = true;
                List<String> names = new ArrayList<>();
                for (Player player : cluster) names.add(player.getName());
                log.write(FILE, FileLog.line(cluster.size() + "명이 함께 있음 (" + String.join(", ", names) + ") "
                        + FileLog.location(cluster.get(0).getLocation())));
            }
        }

        sessions.removeIf(session -> {
            if (now - session.lastSeen < endAfterMillis) return false;
            if (session.logged) {
                log.write(FILE, FileLog.line("함께 있던 무리가 흩어짐 (최대 " + session.maxSize + "명, 약 "
                        + duration(session.lastSeen - session.start) + ", 명단: " + String.join(", ", session.names) + ")"));
            }
            return true;
        });
    }

    private static String duration(long millis) {
        long minutes = millis / 60000L;
        if (minutes < 1) return "1분 미만";
        if (minutes < 60) return minutes + "분";
        return (minutes / 60) + "시간 " + (minutes % 60) + "분";
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
