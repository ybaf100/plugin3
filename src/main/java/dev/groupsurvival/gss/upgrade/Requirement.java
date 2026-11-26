package dev.groupsurvival.gss.upgrade;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

/**
 * 강화 재료 하나. key 는 아이템 이름(Material) 또는 그룹(#concrete = 아무 색 콘크리트).
 * 이름이 바뀌었거나 마법이 부여된 아이템도 종류만 맞으면 인정.
 */
public record Requirement(String key, Material icon, int amount, Component name) {

    public static Requirement parse(String rawKey, int amount) {
        String key = rawKey.trim();
        if (key.equalsIgnoreCase("#concrete")) {
            return new Requirement("#concrete", Material.WHITE_CONCRETE, amount, Component.text("콘크리트 (아무 색)"));
        }
        Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("알 수 없는 아이템: " + rawKey);
        }
        return new Requirement(material.name(), material, amount, Component.translatable(material.translationKey()));
    }

    public boolean matches(Material material) {
        if (key.equals("#concrete")) {
            String name = material.name();
            return name.endsWith("_CONCRETE");
        }
        return material.name().equals(key);
    }
}
