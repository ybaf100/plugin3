package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import dev.groupsurvival.gss.feature.AnonymityFeature;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.plugin.Plugin;

/**
 * 패킷 단계 익명화.
 *
 * 다른 플레이어의 정보(PlayerInfo)는 평소에는 클라이언트에 보내지 않음.
 * → 탭 목록, P키 소셜 화면, 플레이어 신고 화면에 아무도 나오지 않음 (인원수도 알 수 없음)
 *
 * 그 플레이어의 몸이 화면에 나타날 때(SpawnEntity)만 스킨이 담긴 정보를 바로 앞에 보내고 1초 뒤 목록에서 지움.
 * 클라이언트는 몸이 생긴 첫 틱에 정보를 몸에 붙여 두므로, 목록에서 지운 뒤에도 스킨은 그대로 보임.
 * 이름은 익명, 탭 목록에는 표시하지 않음.
 *
 * 그 밖에: 위치 표시줄(waypoint) 패킷 차단, "n/m 명이 자는 중" 메시지 차단.
 */
public final class AnonymityPacketListener implements PacketListener {

    /** 몸이 나타난 뒤 목록에서 지우기까지의 시간 (틱) */
    private static final long REMOVE_AFTER_TICKS = 20L;

    private final AnonymityFeature anonymity;
    private final Plugin plugin;

    /** 다른 플레이어에게 보낼 스킨 정보 (서버가 보내려던 원래 정보에서 저장) */
    private record Snapshot(List<TextureProperty> textures, GameMode gameMode) {
    }

    private final Map<UUID, Snapshot> profiles = new ConcurrentHashMap<>();

    public AnonymityPacketListener(AnonymityFeature anonymity, Plugin plugin) {
        this.anonymity = anonymity;
        this.plugin = plugin;
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID id = event.getUser().getUUID();
        if (id != null) profiles.remove(id);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) return;
        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            handlePlayerInfo(event);
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            handleSpawn(event);
        } else if (type == PacketType.Play.Server.WAYPOINT) {
            event.setCancelled(true);
        } else if (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            var w = new WrapperPlayServerSystemChatMessage(event);
            if (isSleepStatus(w.getMessage())) event.setCancelled(true);
        } else if (type == PacketType.Play.Server.ACTION_BAR) {
            var w = new WrapperPlayServerActionBar(event);
            if (isSleepStatus(w.getActionBarText())) event.setCancelled(true);
        }
    }

    private static boolean isSleepStatus(Component component) {
        return component instanceof TranslatableComponent translatable
                && translatable.key().startsWith("sleep.players_sleeping");
    }

    /** 다른 플레이어 항목은 모두 빼고(스킨 정보만 저장), 내 항목만 그대로 보냄 */
    private void handlePlayerInfo(PacketSendEvent event) {
        UUID viewer = event.getUser().getUUID();
        if (viewer == null) return;
        var w = new WrapperPlayServerPlayerInfoUpdate(event);
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = w.getActions();
        boolean adding = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER);

        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> kept = new ArrayList<>();
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : w.getEntries()) {
            UUID target = info.getProfileId();
            if (viewer.equals(target)) {
                kept.add(info);
                continue;
            }
            changed = true;
            if (adding && info.getGameProfile() != null) {
                List<TextureProperty> textures = info.getGameProfile().getTextureProperties();
                GameMode mode = info.getGameMode() != null ? info.getGameMode() : GameMode.SURVIVAL;
                profiles.put(target, new Snapshot(textures == null ? List.of() : List.copyOf(textures), mode));
            } else if (actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)
                    && info.getGameMode() != null) {
                profiles.computeIfPresent(target, (id, old) -> new Snapshot(old.textures(), info.getGameMode()));
            }
        }
        if (!changed) return;
        if (kept.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        w.setEntries(kept);
        event.markForReEncode(true);
    }

    /**
     * 다른 플레이어의 몸이 나타날 때: [스킨 정보 → 몸] 순서로 보내고, 1초 뒤 정보만 목록에서 지움.
     */
    private void handleSpawn(PacketSendEvent event) {
        var w = new WrapperPlayServerSpawnEntity(event);
        if (w.getEntityType() != EntityTypes.PLAYER) return;
        Optional<UUID> uuid = w.getUUID();
        UUID viewer = event.getUser().getUUID();
        if (uuid == null || uuid.isEmpty() || viewer == null || viewer.equals(uuid.get())) return;
        UUID target = uuid.get();
        if (!anonymity.isShown(viewer, target)) {
            event.setCancelled(true); // 가시 범위 밖이면 몸도 보내지 않음
            return;
        }
        Snapshot snapshot = profiles.get(target);
        List<TextureProperty> textures = snapshot == null ? List.of() : snapshot.textures();
        GameMode mode = snapshot == null ? GameMode.SURVIVAL : snapshot.gameMode();

        var entry = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                new UserProfile(target, anonymity.anonymousName(), textures), false, 0, mode, null, null);
        var info = new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), List.of(entry));
        var spawn = new WrapperPlayServerSpawnEntity(w.getEntityId(), w.getUUID(), w.getEntityType(), w.getPosition(),
                w.getPitch(), w.getYaw(), w.getHeadYaw(), w.getData(), w.getVelocity());

        event.setCancelled(true);
        User user = event.getUser();
        user.sendPacketSilently(info);
        user.sendPacketSilently(spawn);
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin,
                () -> user.sendPacketSilently(new WrapperPlayServerPlayerInfoRemove(target)), REMOVE_AFTER_TICKS);
    }
}
