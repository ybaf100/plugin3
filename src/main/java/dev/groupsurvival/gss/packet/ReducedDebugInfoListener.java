package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 좌표 숨김 월드(기본: 오버월드)에서는 F3 좌표를 숨기고(reducedDebugInfo), 다른 월드에서는 다시 보이게 함.
 * 클라이언트가 어느 월드에 있는지는 JoinGame/Respawn 패킷으로 추적.
 */
public final class ReducedDebugInfoListener implements PacketListener {

    private static final int STATUS_REDUCED_DEBUG_ON = 22;
    private static final int STATUS_REDUCED_DEBUG_OFF = 23;

    private final HiddenWorlds hidden;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public ReducedDebugInfoListener(HiddenWorlds hidden) {
        this.hidden = hidden;
    }

    private static final class State {
        volatile int entityId = Integer.MIN_VALUE;
        volatile boolean inHiddenWorld;
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID id = event.getUser().getUUID();
        if (id != null) states.remove(id);
    }

    @Override
    public void onPacketSend(PacketSendEvent e) {
        if (e.getConnectionState() != ConnectionState.PLAY) return;
        UUID id = e.getUser().getUUID();
        if (id == null) return;
        PacketTypeCommon t = e.getPacketType();

        if (t == PacketType.Play.Server.JOIN_GAME) {
            State s = states.computeIfAbsent(id, k -> new State());
            var w = new WrapperPlayServerJoinGame(e);
            s.entityId = w.getEntityId();
            s.inHiddenWorld = hidden.isHidden(w.getWorldName());
            w.setReducedDebugInfo(s.inHiddenWorld);
            e.markForReEncode(true);
        } else if (t == PacketType.Play.Server.RESPAWN) {
            State s = states.get(id);
            if (s == null) return;
            var w = new WrapperPlayServerRespawn(e);
            w.getWorldName().ifPresent(name -> s.inHiddenWorld = hidden.isHidden(name));
            if (s.entityId == Integer.MIN_VALUE) return;
            final int entityId = s.entityId;
            final int status = s.inHiddenWorld ? STATUS_REDUCED_DEBUG_ON : STATUS_REDUCED_DEBUG_OFF;
            final User user = e.getUser();
            // 리스폰 처리 후 클라이언트에 F3 상태를 다시 알림
            e.getTasksAfterSend().add(() ->
                    user.sendPacketSilently(new WrapperPlayServerEntityStatus(entityId, status)));
        } else if (t == PacketType.Play.Server.ENTITY_STATUS) {
            State s = states.get(id);
            if (s == null) return;
            var w = new WrapperPlayServerEntityStatus(e);
            int status = w.getStatus();
            if (w.getEntityId() != s.entityId
                    || (status != STATUS_REDUCED_DEBUG_ON && status != STATUS_REDUCED_DEBUG_OFF)) return;
            int wanted = s.inHiddenWorld ? STATUS_REDUCED_DEBUG_ON : STATUS_REDUCED_DEBUG_OFF;
            if (status != wanted) {
                w.setStatus(wanted);
                e.markForReEncode(true);
            }
        }
    }
}
