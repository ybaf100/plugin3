package dev.groupsurvival.gss.command;

import dev.groupsurvival.gss.feature.ElytraFeature;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

/** 콘솔 전용 관리 명령어: gss elytra <on|off|status> */
public final class GssCommand implements CommandExecutor {

    private final ElytraFeature elytra;

    public GssCommand(ElytraFeature elytra) {
        this.elytra = elytra;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("이 명령어는 콘솔에서만 사용할 수 있습니다.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("elytra")) {
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
            return true;
        }
        sender.sendMessage("사용법: gss elytra <on|off|status>");
        return true;
    }
}
