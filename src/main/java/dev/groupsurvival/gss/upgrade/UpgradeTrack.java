package dev.groupsurvival.gss.upgrade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 강화 종류 하나 (체력, 성급함). 단계 = level, steps[level] = level -> level+1 에 필요한 재료.
 */
public final class UpgradeTrack {

    public static final int MAX_REQUIREMENTS = 8;

    private final String id;
    private final String name;
    private final int start;
    private final int max;
    private final Map<Integer, List<Requirement>> steps;
    private final IntFunction<String> label;
    private final Map<Integer, AdvancementRequirement> advancements;

    private UpgradeTrack(String id, String name, int start, Map<Integer, List<Requirement>> steps,
                         Map<Integer, AdvancementRequirement> advancements, IntFunction<String> label) {
        this.id = id;
        this.name = name;
        this.start = start;
        this.steps = steps;
        this.advancements = advancements;
        this.label = label;
        int last = start - 1;
        for (int level : steps.keySet()) last = Math.max(last, level);
        this.max = last + 1;
    }

    public static UpgradeTrack load(String id, String name, int start, ConfigurationSection stepsSection,
                                    ConfigurationSection advancementSection, IntFunction<String> label, Logger logger) {
        Map<Integer, List<Requirement>> steps = new TreeMap<>();
        if (stepsSection != null) {
            for (String levelKey : stepsSection.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(levelKey.trim());
                } catch (NumberFormatException e) {
                    logger.warning("[" + name + " 강화] 단계 번호가 숫자가 아님: " + levelKey);
                    continue;
                }
                ConfigurationSection section = stepsSection.getConfigurationSection(levelKey);
                if (section == null) continue;
                List<Requirement> list = new ArrayList<>();
                for (String itemKey : section.getKeys(false)) {
                    int amount = section.getInt(itemKey, 0);
                    if (amount <= 0) continue;
                    try {
                        list.add(Requirement.parse(itemKey, amount));
                    } catch (IllegalArgumentException e) {
                        logger.warning("[" + name + " 강화] " + level + "단계: " + e.getMessage());
                    }
                }
                if (list.size() > MAX_REQUIREMENTS) {
                    logger.warning("[" + name + " 강화] " + level + "단계 재료가 " + MAX_REQUIREMENTS + "개를 넘어 앞의 "
                            + MAX_REQUIREMENTS + "개만 사용합니다.");
                    list = new ArrayList<>(list.subList(0, MAX_REQUIREMENTS));
                }
                if (level >= start && !list.isEmpty()) steps.put(level, Collections.unmodifiableList(list));
            }
        }
        Map<Integer, AdvancementRequirement> advancements = new TreeMap<>();
        if (advancementSection != null) {
            for (String levelKey : advancementSection.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(levelKey.trim());
                } catch (NumberFormatException e) {
                    logger.warning("[" + name + " 강화] 도전과제 조건의 단계 번호가 숫자가 아님: " + levelKey);
                    continue;
                }
                AdvancementRequirement requirement;
                if (advancementSection.isList(levelKey)) {
                    List<String> missing = new ArrayList<>();
                    requirement = AdvancementRequirement.ofKeys(advancementSection.getStringList(levelKey), missing);
                    for (String key : missing) {
                        logger.warning("[" + name + " 강화] " + level + "단계: 없는 도전과제 " + key);
                    }
                } else {
                    requirement = AdvancementRequirement.ofCount(advancementSection.getInt(levelKey, 0));
                }
                if (!requirement.isEmpty()) advancements.put(level, requirement);
            }
        }
        return new UpgradeTrack(id, name, start, steps, advancements, label);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int start() {
        return start;
    }

    public int max() {
        return max;
    }

    public List<Requirement> requirements(int level) {
        return steps.getOrDefault(level, List.of());
    }

    /** level -> level+1 강화의 도전과제 조건 (없으면 null) */
    public AdvancementRequirement advancementRequirement(int level) {
        return advancements.get(level);
    }

    public String label(int level) {
        return label.apply(level);
    }

    public Requirement find(int level, String key) {
        for (Requirement requirement : requirements(level)) {
            if (requirement.key().equals(key)) return requirement;
        }
        return null;
    }
}
