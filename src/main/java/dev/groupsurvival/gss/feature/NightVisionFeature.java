package dev.groupsurvival.gss.feature;

import dev.groupsurvival.gss.util.ActionBars;
import dev.groupsurvival.gss.effect.PermanentEffects;
import dev.groupsurvival.gss.util.FileLog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/** 영구 야간투시 켜기/끄기 (/nv 또는 강화 창의 스위치). 입자 없이 지급, 죽어도 유지. */
public final class NightVisionFeature implements CommandExecutor, PermanentEffects.Source {

    private final FileLog log;
    private final NamespacedKey key;
    private PermanentEffects effects;

    public NightVisionFeature(Plugin plugin, FileLog log) {
        this.log = log;
        this.key = new NamespacedKey(plugin, "night_vision");
    }

    public void attach(PermanentEffects effects) {
        this.effects = effects;
        effects.add(this);
    }

    @Override
    public PotionEffectType type() {
        return PotionEffectType.NIGHT_VISION;
    }

    @Override
    public Integer amplifier(Player player) {
        return isEnabled(player) ? 0 : null;
    }

    public boolean isEnabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, false);
    }

    /** 켜짐/꺼짐을 바꾸고 새 상태를 돌려줌. */
    public boolean toggle(Player player) {
        boolean enable = !isEnabled(player);
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, enable);
        effects.refresh(player);
        if (enable) {
            ActionBars.send(player, Component.text("야간투시 켜짐", NamedTextColor.GREEN));
            log.write("시스템/야간투시.log", FileLog.line(player, player.getLocation(), "야간투시 획득"));
        } else {
            ActionBars.send(player, Component.text("야간투시 꺼짐", NamedTextColor.GRAY));
        }
        return enable;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        toggle(player);
        return true;
    }
}
