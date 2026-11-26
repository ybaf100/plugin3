package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPosition;

/**
 * 오버월드 나침반이 항상 0,0 을 가리키게 함.
 * 나침반은 클라이언트가 받은 "월드 스폰 위치"를 가리키므로, 그 패킷의 x/z 만 0,0 으로 바꿔서 보냄.
 * 서버의 실제 월드 스폰은 바뀌지 않음. 네더/엔드 나침반은 바닐라처럼 빙글빙글 돎.
 */
public final class CompassTargetListener implements PacketListener {

    private final HiddenWorlds overworlds;

    public CompassTargetListener(HiddenWorlds overworlds) {
        this.overworlds = overworlds;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) return;
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_POSITION) return;
        var w = new WrapperPlayServerSpawnPosition(event);
        if (w.getDimension() != null && !overworlds.isHidden(w.getDimension())) return;
        Vector3i pos = w.getPosition();
        if (pos.getX() == 0 && pos.getZ() == 0) return;
        w.setPosition(new Vector3i(0, pos.getY(), 0));
        event.markForReEncode(true);
    }
}
