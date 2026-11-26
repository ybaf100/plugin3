package dev.groupsurvival.gss.upgrade;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 플레이어별 강화 단계와 "지금 단계에 넣은 재료"를 플레이어 데이터에 저장 (서버 재시작/사망 후에도 유지).
 * 진행 데이터 형식: "단계|재료=개수,재료=개수"
 */
public final class UpgradeData {

    private final Plugin plugin;

    public UpgradeData(Plugin plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey levelKey(UpgradeTrack track) {
        return new NamespacedKey(plugin, track.id() + "_level");
    }

    private NamespacedKey progressKey(UpgradeTrack track) {
        return new NamespacedKey(plugin, track.id() + "_progress");
    }

    private NamespacedKey enabledKey(UpgradeTrack track) {
        return new NamespacedKey(plugin, track.id() + "_enabled");
    }

    public int level(Player player, UpgradeTrack track) {
        int level = player.getPersistentDataContainer().getOrDefault(levelKey(track), PersistentDataType.INTEGER, track.start());
        return Math.max(track.start(), Math.min(track.max(), level));
    }

    public void setLevel(Player player, UpgradeTrack track, int level) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(levelKey(track), PersistentDataType.INTEGER, Math.max(track.start(), Math.min(track.max(), level)));
        pdc.remove(progressKey(track));
    }

    /** 지금 단계에 넣은 재료. 저장된 단계가 다르면 빈 값. */
    public Map<String, Integer> progress(Player player, UpgradeTrack track) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String raw = player.getPersistentDataContainer().get(progressKey(track), PersistentDataType.STRING);
        if (raw == null) return result;
        int bar = raw.indexOf('|');
        if (bar < 0) return result;
        try {
            if (Integer.parseInt(raw.substring(0, bar)) != level(player, track)) return result;
        } catch (NumberFormatException e) {
            return result;
        }
        for (String part : raw.substring(bar + 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                result.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // 잘못된 항목은 무시
            }
        }
        return result;
    }

    public void setProgress(Player player, UpgradeTrack track, Map<String, Integer> progress) {
        StringBuilder sb = new StringBuilder().append(level(player, track)).append('|');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : progress.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (!first) sb.append(',');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        player.getPersistentDataContainer().set(progressKey(track), PersistentDataType.STRING, sb.toString());
    }

    public boolean enabled(Player player, UpgradeTrack track) {
        return player.getPersistentDataContainer().getOrDefault(enabledKey(track), PersistentDataType.BOOLEAN, true);
    }

    public void setEnabled(Player player, UpgradeTrack track, boolean enabled) {
        player.getPersistentDataContainer().set(enabledKey(track), PersistentDataType.BOOLEAN, enabled);
    }
}
