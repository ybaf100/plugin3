package dev.groupsurvival.gss.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 서버에 누가 있는지 알 수 없게 함.
 * - 입장/퇴장/발전과제 메시지 제거, 데스 메시지는 고정 문구로 통일, 서버 목록 인원 숨김, 위치 표시줄(locator bar) 끔
 * - 가까이(visibility-range) 있는 플레이어만 클라이언트에 전송, 나머지는 완전히 숨김
 * - 이름은 익명으로 바뀌고(패킷 단계) 이름표는 항상 숨김, 스킨은 그대로 보임
 */
public final class AnonymityFeature implements Listener {

    private static final String TEAM_NAME = "gss_anonymous";

    private final JavaPlugin plugin;
    private final String anonymousName;
    private final double showRangeSq;
    private final double hideRangeSq;
    private final boolean hideServerList;
    private final Component deathMessage;
    /** viewer -> 이 viewer 에게 현재 보여주고 있는 플레이어들 (패킷 리스너에서 동시 접근) */
    private final Map<UUID, Set<UUID>> shown = new ConcurrentHashMap<>();

    public AnonymityFeature(JavaPlugin plugin) {
        this.plugin = plugin;
        String name = plugin.getConfig().getString("anonymity.anonymous-name", "익명");
        if (name == null || name.isBlank()) name = "익명";
        this.anonymousName = name.length() > 16 ? name.substring(0, 16) : name;
        double range = Math.max(4, plugin.getConfig().getDouble("anonymity.visibility-range", 48));
        this.showRangeSq = range * range;
        this.hideRangeSq = (range + 8) * (range + 8);
        this.hideServerList = plugin.getConfig().getBoolean("anonymity.hide-server-list-players", true);
        this.deathMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(plugin.getConfig().getString("death-message", "&c&l사람이 죽었다."));
    }

    public String anonymousName() {
        return anonymousName;
    }

    /** 패킷 리스너용: viewer 에게 target 을 보여줘도 되는지. */
    public boolean isShown(UUID viewer, UUID target) {
        Set<UUID> set = shown.get(viewer);
        return set != null && set.contains(target);
    }

    public void start() {
        setupTeam();
        for (World world : plugin.getServer().getWorlds()) applyGameRules(world);
        for (Player player : plugin.getServer().getOnlinePlayers()) hideFromEveryone(player);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateVisibility, 20L, 10L);
    }

    private void setupTeam() {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (team == null) team = board.registerNewTeam(TEAM_NAME);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        if (!team.hasEntry(anonymousName)) team.addEntry(anonymousName);
    }

    private void applyGameRules(World world) {
        world.setGameRule(GameRules.LOCATOR_BAR, false);
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        applyGameRules(event.getWorld());
    }

    // ── 가시성 ──
    private void hideFromEveryone(Player player) {
        shown.put(player.getUniqueId(), ConcurrentHashMap.newKeySet());
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == player) continue;
            Set<UUID> otherShown = shown.computeIfAbsent(other.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            otherShown.remove(player.getUniqueId());
            other.hidePlayer(plugin, player);
            player.hidePlayer(plugin, other);
        }
    }

    private void updateVisibility() {
        var players = plugin.getServer().getOnlinePlayers();
        for (Player viewer : players) {
            Set<UUID> set = shown.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            for (Player target : players) {
                if (target == viewer) continue;
                boolean currentlyShown = set.contains(target.getUniqueId());
                boolean near = viewer.getWorld() == target.getWorld()
                        && viewer.getLocation().distanceSquared(target.getLocation())
                        <= (currentlyShown ? hideRangeSq : showRangeSq);
                if (near && !currentlyShown) {
                    set.add(target.getUniqueId()); // 패킷 리스너가 통과시키도록 먼저 등록
                    viewer.showPlayer(plugin, target);
                } else if (!near && currentlyShown) {
                    viewer.hidePlayer(plugin, target);
                    set.remove(target.getUniqueId());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinEarly(PlayerJoinEvent event) {
        hideFromEveryone(event.getPlayer());
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        UUID id = event.getPlayer().getUniqueId();
        shown.remove(id);
        for (Set<UUID> set : shown.values()) set.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent event) {
        event.leaveMessage(Component.empty());
    }

    // ── 메시지 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // 바닐라 데스 메시지(이름/원인 노출)는 없애고, 고정 문구를 직접 전체 공지
        event.deathMessage(null);
        if (OfflineBodyFeature.isSilentDeath(event.getPlayer().getUniqueId())) {
            // 오프라인 중 잠든 몸이 이미 죽어서 공지했음: 다시 공지하지 않음
            event.deathScreenMessageOverride(Component.text("접속을 끊은 사이 잠든 몸이 죽었습니다", NamedTextColor.RED));
            return;
        }
        event.deathScreenMessageOverride(deathMessage);
        plugin.getServer().broadcast(deathMessage);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        event.message(null);
    }

    // ── 서버 목록 ──
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        if (!hideServerList) return;
        event.setHidePlayers(true);
        event.getListedPlayers().clear();
        event.setNumPlayers(0);
    }
}
