package dev.groupsurvival.gss.command;

import dev.groupsurvival.gss.feature.ElytraFeature;
import dev.groupsurvival.gss.feature.ServerStateFeature;
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
 *   gss maintenance <on [이유]|off>   (점검 모드)
 *   gss state                        (서버 상태 확인)
 */
public final class GssCommand implements CommandExecutor {

    private final ElytraFeature elytra;
    private final UpgradeFeature upgrades;
    private final WorldBorderFeature border;
    private final ServerStateFeature state;

    public GssCommand(ElytraFeature elytra, UpgradeFeature upgrades, WorldBorderFeature border, ServerStateFeature state) {
        this.elytra = elytra;
        this.upgrades = upgrades;
        this.border = border;
        this.state = state;
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
            case "health" -> upgrade(sender, args, upgrades.healthTrack());
            case "haste" -> upgrade(sender, args, upgrades.hasteTrack());
            case "saturation" -> upgrade(sender, args, upgrades.saturationTrack());
            case "maintenance" -> maintenance(sender, args);
            case "state" -> state.status().forEach(sender::sendMessage);
            case "border" -> {
                if (border == null) sender.sendMessage("[GSS] 월드보더 기능이 꺼져 있습니다.");
                else border.status().forEach(sender::sendMessage);
            }
            default -> {
                sender.sendMessage("사용법: gss elytra <on|off|status>");
                sender.sendMessage("        gss health <플레이어> [하트]");
                sender.sendMessage("        gss haste <플레이어> [0|1|2]");
                sender.sendMessage("        gss saturation <플레이어> [0|1]");
                sender.sendMessage("        gss border  (월드보더 단계 확인)");
                sender.sendMessage("        gss maintenance <on [이유]|off>  (점검 모드)");
                sender.sendMessage("        gss state  (서버 상태 확인)");
            }
        }
        return true;
    }

    private void maintenance(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "on" -> {
                String reason = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;
                state.setMaintenance(true, reason);
                sender.sendMessage("[GSS] 점검 모드 켜짐 (OP 외 접속 차단, 접속 중인 일반 플레이어는 내보냄)");
            }
            case "off" -> {
                state.setMaintenance(false, null);
                sender.sendMessage("[GSS] 점검 모드 꺼짐");
            }
            default -> sender.sendMessage("사용법: gss maintenance on [이유] | gss maintenance off");
        }
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

    private void upgrade(CommandSender sender, String[] args, UpgradeTrack track) {
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
        upgrades.setLevel(player, track, value);
        sender.sendMessage("[GSS] " + player.getName() + " " + track.name() + " → " + track.label(value)
                + " (지금 단계에 넣은 재료는 초기화됨)");
    }
}
