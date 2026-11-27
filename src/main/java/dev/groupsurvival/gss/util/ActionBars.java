package dev.groupsurvival.gss.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * 액션바 보내기. 안내 메시지(재료 투입, 인증 등)를 보내면 잠시 동안 상시 표시(안전구역 등)가 그 메시지를 덮지 않음.
 */
public final class ActionBars {

    /** 안내 메시지를 보낸 뒤 상시 표시를 멈추는 시간 (밀리초) */
    private static final long HOLD_MILLIS = 2500L;

    private static final Map<UUID, Long> heldUntil = new ConcurrentHashMap<>();

    private ActionBars() {
    }

    /** 안내 메시지 (잠시 상시 표시를 멈춤) */
    public static void send(Player player, Component message) {
        heldUntil.put(player.getUniqueId(), System.currentTimeMillis() + HOLD_MILLIS);
        player.sendActionBar(message);
    }

    /** 안내 메시지가 떠 있는 중인지 */
    public static boolean isHeld(Player player) {
        Long until = heldUntil.get(player.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            heldUntil.remove(player.getUniqueId(), until);
            return false;
        }
        return true;
    }

    public static void forget(Player player) {
        heldUntil.remove(player.getUniqueId());
    }
}
