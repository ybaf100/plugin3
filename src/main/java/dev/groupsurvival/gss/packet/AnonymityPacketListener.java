package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import dev.groupsurvival.gss.feature.AnonymityFeature;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

/**
 * 패킷 단계 익명화.
 * - 플레이어 목록(PlayerInfo): 다른 플레이어 이름을 익명으로, 탭 목록에서 제외, 표시 이름 제거,
 *   가시 범위 밖 플레이어 항목은 아예 전송하지 않음 (스킨 텍스처는 그대로 → 마주치면 알아볼 수 있음)
 * - 위치 표시줄(waypoint) 패킷 차단
 * - "n/m 명이 자는 중" 메시지 차단 (접속 인원 노출)
 */
public final class AnonymityPacketListener implements PacketListener {

    private final AnonymityFeature anonymity;

    public AnonymityPacketListener(AnonymityFeature anonymity) {
        this.anonymity = anonymity;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) return;
        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            handlePlayerInfo(event);
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
            // 가시 범위 밖이면 항목 자체를 보내지 않음
            if (!anonymity.isShown(viewer, target)) continue;
            if (adding && info.getGameProfile() != null) {
                UserProfile original = info.getGameProfile();
                info.setGameProfile(new UserProfile(original.getUUID(), anonymity.anonymousName(),
                        original.getTextureProperties()));
            }
            info.setListed(false);
            info.setDisplayName(null);
            kept.add(info);
        }
        if (!changed) return;
        if (kept.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        w.setEntries(kept);
        event.markForReEncode(true);
    }
}
