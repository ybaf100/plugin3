package dev.groupsurvival.gss;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import dev.groupsurvival.gss.command.GssCommand;
import dev.groupsurvival.gss.feature.AnonymityFeature;
import dev.groupsurvival.gss.feature.ClientCheckFeature;
import dev.groupsurvival.gss.feature.CommunicationFeature;
import dev.groupsurvival.gss.feature.DragonFeature;
import dev.groupsurvival.gss.feature.ElytraFeature;
import dev.groupsurvival.gss.feature.NightVisionFeature;
import dev.groupsurvival.gss.integration.LunarIntegration;
import dev.groupsurvival.gss.packet.AnonymityPacketListener;
import dev.groupsurvival.gss.packet.CoordinateOffsetListener;
import dev.groupsurvival.gss.packet.HiddenWorlds;
import dev.groupsurvival.gss.packet.OffsetManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class GroupSurvivalSystem extends JavaPlugin {

    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();
    private boolean apolloPresent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        apolloPresent = Bukkit.getPluginManager().isPluginEnabled("Apollo-Bukkit");

        HiddenWorlds hiddenWorlds = new HiddenWorlds(this);
        register(hiddenWorlds);

        if (getConfig().getBoolean("night-vision.enabled", true)) {
            NightVisionFeature nightVision = new NightVisionFeature(this);
            register(nightVision);
            Objects.requireNonNull(getCommand("nv")).setExecutor(nightVision);
        }

        ElytraFeature elytra = new ElytraFeature(this);
        register(elytra);
        Objects.requireNonNull(getCommand("gss")).setExecutor(new GssCommand(elytra));

        register(new DragonFeature(this));
        register(new CommunicationFeature(this));

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

        boolean offsetEnabled = getConfig().getBoolean("coordinates.offset.enabled", false);
        boolean reducedDebug = getConfig().getBoolean("coordinates.reduced-debug-info", true);
        if (offsetEnabled || reducedDebug) {
            OffsetManager offsets = new OffsetManager(
                    offsetEnabled,
                    getConfig().getInt("coordinates.offset.min-chunks", 5000),
                    getConfig().getInt("coordinates.offset.max-chunks", 40000));
            CoordinateOffsetListener coords = new CoordinateOffsetListener(offsets, hiddenWorlds, reducedDebug, getLogger());
            // 서버 -> 클라이언트: 다른 플러그인이 실제 좌표를 보도록 가장 마지막에 변환
            packetListeners.add(events.registerListener(coords.outbound(), PacketListenerPriority.HIGHEST));
            // 클라이언트 -> 서버: 다른 플러그인이 실제 좌표를 보도록 가장 먼저 복원
            packetListeners.add(events.registerListener(coords.inbound(), PacketListenerPriority.LOWEST));
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
        if (PacketEvents.getAPI() != null) {
            for (PacketListenerCommon listener : packetListeners) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            }
        }
        packetListeners.clear();
    }

    private void register(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }
}
