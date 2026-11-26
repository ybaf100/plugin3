package dev.groupsurvival.gss.integration;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.module.modsetting.ModSettingModule;
import com.lunarclient.apollo.option.Option;
import com.lunarclient.apollo.option.Options;
import java.util.List;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Lunar Client(Apollo) 연동. Apollo-Bukkit 플러그인이 있을 때만 이 클래스가 로드됨.
 * 설정의 "Mod이름.옵션" 을 Apollo 의 com.lunarclient.apollo.mods.impl.Mod이름 클래스 필드로 찾아 false 로 강제.
 */
public final class LunarIntegration {

    private static final String MODS_PACKAGE = "com.lunarclient.apollo.mods.impl.Mod";

    private LunarIntegration() {
    }

    public static void apply(Plugin plugin, List<String> disabledOptions) {
        ModSettingModule module = Apollo.getModuleManager().getModule(ModSettingModule.class);
        Options options = module.getOptions();
        ClassLoader loader = ModSettingModule.class.getClassLoader();
        int applied = 0;
        for (String entry : disabledOptions) {
            int dot = entry.indexOf('.');
            if (dot <= 0) {
                plugin.getLogger().warning("잘못된 Lunar 옵션 형식: " + entry + " (예: Minimap.ENABLED)");
                continue;
            }
            String mod = entry.substring(0, dot).trim();
            String field = entry.substring(dot + 1).trim();
            try {
                Class<?> modClass = Class.forName(MODS_PACKAGE + mod, true, loader);
                Object option = modClass.getField(field).get(null);
                options.set((Option<?, ?, ?>) option, Boolean.FALSE);
                applied++;
            } catch (ReflectiveOperationException | ClassCastException e) {
                plugin.getLogger().warning("Lunar 옵션을 찾을 수 없음: " + entry + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (!module.isEnabled()) {
            plugin.getLogger().warning("Apollo 의 mod_setting 모듈이 꺼져 있습니다. Apollo 설정에서 켜야 Lunar 모드 제한이 적용됩니다.");
        }
        plugin.getLogger().info("Lunar Client 모드 옵션 " + applied + "개 비활성화 적용");
    }

    public static boolean isLunarPlayer(UUID uuid) {
        return Apollo.getPlayerManager().hasSupport(uuid);
    }
}
