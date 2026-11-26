package dev.groupsurvival.gss.upgrade;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

/**
 * 강화 단계의 도전과제 조건. 두 가지 형식:
 * - 개수형 (count > 0): 레시피 해금을 뺀 전체 도전과제 중 count 개 이상 달성
 * - 지정형 (advancements 비어있지 않음): 목록의 도전과제를 모두 달성
 */
public final class AdvancementRequirement {

    private final int count;
    private final List<Advancement> advancements;

    private AdvancementRequirement(int count, List<Advancement> advancements) {
        this.count = count;
        this.advancements = advancements;
    }

    public static AdvancementRequirement ofCount(int count) {
        return new AdvancementRequirement(count, List.of());
    }

    /** 키 목록으로 생성. 서버에 없는 키는 missing 에 담아 돌려줌. */
    public static AdvancementRequirement ofKeys(List<String> keys, List<String> missing) {
        List<Advancement> list = new ArrayList<>();
        for (String raw : keys) {
            NamespacedKey key = NamespacedKey.fromString(raw.trim().toLowerCase());
            Advancement advancement = key == null ? null : Bukkit.getAdvancement(key);
            if (advancement == null) missing.add(raw);
            else list.add(advancement);
        }
        return new AdvancementRequirement(0, List.copyOf(list));
    }

    public boolean isSpecific() {
        return !advancements.isEmpty();
    }

    public boolean isEmpty() {
        return count <= 0 && advancements.isEmpty();
    }

    /** 목표 수 (개수형: count, 지정형: 목록 크기) */
    public int target() {
        return isSpecific() ? advancements.size() : count;
    }

    /** 현재 달성 수 */
    public int progress(Player player) {
        if (isSpecific()) {
            int done = 0;
            for (Advancement advancement : advancements) {
                if (player.getAdvancementProgress(advancement).isDone()) done++;
            }
            return done;
        }
        return completedAdvancements(player);
    }

    public boolean met(Player player) {
        return progress(player) >= target();
    }

    public List<Advancement> advancements() {
        return advancements;
    }

    public static Component title(Advancement advancement) {
        return advancement.displayName();
    }

    /** 달성한 도전과제 수 (레시피 해금과 화면에 안 나오는 도전과제는 제외) */
    public static int completedAdvancements(Player player) {
        int done = 0;
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement advancement = it.next();
            if (advancement.getKey().getKey().startsWith("recipes/") || advancement.getDisplay() == null) continue;
            if (player.getAdvancementProgress(advancement).isDone()) done++;
        }
        return done;
    }
}
