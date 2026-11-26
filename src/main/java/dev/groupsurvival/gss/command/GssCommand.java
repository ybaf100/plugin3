package dev.groupsurvival.gss.command;

import dev.groupsurvival.gss.feature.ElytraFeature;
import dev.groupsurvival.gss.feature.WorldBorderFeature;
import dev.groupsurvival.gss.upgrade.UpgradeFeature;
import dev.groupsurvival.gss.upgrade.UpgradeTrack;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 콘솔 전용 관리 명령어.
 *   gss elytra <on|off|status>
 *   gss health <플레이어> [하트]      (하트를 빼면 현재 값 확인)
 *   gss haste <플레이어> [0|1|2]
 *   gss border                       (월드보더 단계 확인)
 */
public final class GssCommand implements CommandExecutor {

    private final ElytraFeature elytra;
    private final UpgradeFeature upgrades;
    private final WorldBorderFeature border;

    public GssCommand(ElytraFeature elytra, UpgradeFeature upgrades, WorldBorderFeature border) {
        this.elytra = elytra;
        this.upgrades = upgrades;
        this.border = border;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("이 명령어는 콘솔에서만 사용할 수 있습니다.");
            return true;
        }
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "elytra" -> elytra(sender, args);
            case "health" -> upgrade(sender, args, upgrades.healthTrack(), true);
            case "haste" -> upgrade(sender, args, upgrades.hasteTrack(), false);
            case "border" -> {
                if (border == null) sender.sendMessage("[GSS] 월드보더 기능이 꺼져 있습니다.");
                else border.status().forEach(sender::sendMessage);
            }
            default -> {
                sender.sendMessage("사용법: gss elytra <on|off|status>");
                sender.sendMessage("        gss health <플레이어> [하트]");
                sender.sendMessage("        gss haste <플레이어> [0|1|2]");
                sender.sendMessage("        gss border  (월드보더 단계 확인)");
            }
        }
        return true;
    }

    private void elytra(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "on" -> {
                elytra.setAllowed(true);
                sender.sendMessage("[GSS] 겉날개 허용: 액자에서 뗄 수 있고 활공 가능");
            }
            case "off" -> {
                elytra.setAllowed(false);
                sender.sendMessage("[GSS] 겉날개 금지: 액자에서 뗄 수 없고 활공 불가");
            }
            default -> sender.sendMessage("[GSS] 겉날개 상태: " + (elytra.isAllowed() ? "허용" : "금지"));
        }
    }

    private void upgrade(CommandSender sender, String[] args, UpgradeTrack track, boolean health) {
        if (args.length < 2) {
            sender.sendMessage("사용법: gss " + track.id() + " <플레이어> [" + track.start() + "~" + track.max() + "]");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage("[GSS] 접속 중인 플레이어만 바꿀 수 있습니다: " + args[1]);
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("[GSS] " + player.getName() + " " + track.name() + ": " + track.label(upgrades.level(player, track)));
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("[GSS] 숫자를 입력하세요: " + args[2]);
            return;
        }
        if (value < track.start() || value > track.max()) {
            sender.sendMessage("[GSS] 범위: " + track.start() + "~" + track.max());
            return;
        }
        if (health) upgrades.setHealthLevel(player, value);
        else upgrades.setHasteLevel(player, value);
        sender.sendMessage("[GSS] " + player.getName() + " " + track.name() + " → " + track.label(value)
                + " (지금 단계에 넣은 재료는 초기화됨)");
    }
}
