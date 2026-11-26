package dev.groupsurvival.gss;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import dev.groupsurvival.gss.command.GssCommand;
import dev.groupsurvival.gss.effect.PermanentEffects;
import dev.groupsurvival.gss.feature.ActivityLogFeature;
import dev.groupsurvival.gss.feature.AnonymityFeature;
import dev.groupsurvival.gss.feature.AuthFeature;
import dev.groupsurvival.gss.feature.ClientCheckFeature;
import dev.groupsurvival.gss.feature.CommunicationFeature;
import dev.groupsurvival.gss.feature.DragonFeature;
import dev.groupsurvival.gss.feature.ElytraFeature;
import dev.groupsurvival.gss.feature.HandSwapActionFeature;
import dev.groupsurvival.gss.feature.NightVisionFeature;
import dev.groupsurvival.gss.feature.PlayerHeadFeature;
import dev.groupsurvival.gss.feature.RandomSpawnFeature;
import dev.groupsurvival.gss.feature.SafeZoneFeature;
import dev.groupsurvival.gss.feature.SilverfishFeature;
import dev.groupsurvival.gss.feature.WorldBorderFeature;
import dev.groupsurvival.gss.integration.LunarIntegration;
import dev.groupsurvival.gss.packet.AnonymityPacketListener;
import dev.groupsurvival.gss.packet.CompassTargetListener;
import dev.groupsurvival.gss.packet.HiddenWorlds;
import dev.groupsurvival.gss.packet.ReducedDebugInfoListener;
import dev.groupsurvival.gss.upgrade.UpgradeFeature;
import dev.groupsurvival.gss.util.FileLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class GroupSurvivalSystem extends JavaPlugin {

    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();
    private boolean apolloPresent;
    private FileLog fileLog;
    private UpgradeFeature upgrades;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fileLog = new FileLog(getDataFolder().toPath(), getLogger());
        apolloPresent = Bukkit.getPluginManager().isPluginEnabled("Apollo-Bukkit");

        HiddenWorlds hiddenWorlds = new HiddenWorlds(this);
        register(hiddenWorlds);

        PermanentEffects effects = new PermanentEffects(this);
        register(effects);

        NightVisionFeature nightVision = null;
        if (getConfig().getBoolean("night-vision.enabled", true)) {
            nightVision = new NightVisionFeature(this, fileLog);
            nightVision.attach(effects);
            Objects.requireNonNull(getCommand("nv")).setExecutor(nightVision);
        }

        upgrades = new UpgradeFeature(this, fileLog, effects, nightVision);
        register(upgrades);
        for (String command : new String[]{"강화", "체력강화", "성급함강화"}) {
            Objects.requireNonNull(getCommand(command)).setExecutor(upgrades);
        }

        ElytraFeature elytra = new ElytraFeature(this, fileLog);
        register(elytra);

        WorldBorderFeature border = null;
        if (getConfig().getBoolean("world-border.enabled", true)) {
            border = new WorldBorderFeature(this, fileLog);
            border.start();
        }
        Objects.requireNonNull(getCommand("gss")).setExecutor(new GssCommand(elytra, upgrades, border));

        register(new DragonFeature(this));
        if (getConfig().getBoolean("silverfish.remove", true)) {
            SilverfishFeature silverfish = new SilverfishFeature(this);
            register(silverfish);
            silverfish.start();
        }
        register(new CommunicationFeature(this));
        register(new HandSwapActionFeature(this, fileLog));
        register(new ActivityLogFeature(fileLog));
        if (getConfig().getBoolean("random-spawn.enabled", true)) register(new RandomSpawnFeature(this));
        if (getConfig().getBoolean("auth.enabled", true)) {
            AuthFeature auth = new AuthFeature(this, fileLog);
            register(auth);
            Objects.requireNonNull(getCommand("인증")).setExecutor(auth);
        }
        if (getConfig().getBoolean("player-head.enabled", true)) register(new PlayerHeadFeature(this));

        SafeZoneFeature safeZone = new SafeZoneFeature(this);
        register(safeZone);
        safeZone.start();

        AnonymityFeature anonymity = new AnonymityFeature(this);
        register(anonymity);
        anonymity.start();

        if (getConfig().getBoolean("client-check.enabled", true)) {
            register(new ClientCheckFeature(this, apolloPresent));
        }

        // 패킷 리스너
        var events = PacketEvents.getAPI().getEventManager();
        packetListeners.add(events.registerListener(
                new AnonymityPacketListener(anonymity), PacketListenerPriority.NORMAL));

        if (getConfig().getBoolean("compass.point-to-origin", true)) {
            packetListeners.add(events.registerListener(
                    new CompassTargetListener(hiddenWorlds), PacketListenerPriority.HIGH));
        }
        if (getConfig().getBoolean("coordinates.reduced-debug-info", true)) {
            packetListeners.add(events.registerListener(
                    new ReducedDebugInfoListener(hiddenWorlds), PacketListenerPriority.HIGH));
        }

        if (apolloPresent && getConfig().getBoolean("lunar.enabled", true)) {
            try {
                LunarIntegration.apply(this, getConfig().getStringList("lunar.disabled-options"));
            } catch (Throwable t) {
                getLogger().warning("Lunar Client(Apollo) 설정 적용 실패: " + t);
            }
        } else if (!apolloPresent) {
            getLogger().warning("Apollo-Bukkit 플러그인이 없습니다. Lunar Client 모드 제한과 Lunar 감지가 비활성화됩니다.");
        }

        getLogger().info("Group Survival System 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (upgrades != null) upgrades.closeAll();
        if (PacketEvents.getAPI() != null) {
            for (PacketListenerCommon listener : packetListeners) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            }
        }
        packetListeners.clear();
        if (fileLog != null) fileLog.close();
    }

    private void register(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }
}
