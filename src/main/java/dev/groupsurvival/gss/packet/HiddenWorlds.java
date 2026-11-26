package dev.groupsurvival.gss.packet;

import com.github.retrooper.packetevents.resources.ResourceLocation;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** 좌표를 숨기는 월드(차원 키 기준) 목록. 패킷 스레드에서 읽음. */
public final class HiddenWorlds implements Listener {

    private final Set<String> configuredNames = new HashSet<>();
    private final Set<String> hiddenKeys = ConcurrentHashMap.newKeySet();

    public HiddenWorlds(JavaPlugin plugin) {
        List<String> names = plugin.getConfig().getStringList("coordinates.hidden-worlds");
        for (String name : names) configuredNames.add(name.toLowerCase(Locale.ROOT));
        for (World world : plugin.getServer().getWorlds()) consider(world);
    }

    private void consider(World world) {
        boolean hidden = configuredNames.isEmpty()
                ? world.getEnvironment() == World.Environment.NORMAL
                : configuredNames.contains(world.getName().toLowerCase(Locale.ROOT));
        String key = world.getKey().toString();
        if (hidden) hiddenKeys.add(key);
        else hiddenKeys.remove(key);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        consider(event.getWorld());
    }

    public boolean isHidden(String dimensionKey) {
        if (dimensionKey == null) return false;
        String key = dimensionKey.indexOf(':') >= 0 ? dimensionKey : "minecraft:" + dimensionKey;
        return hiddenKeys.contains(key.toLowerCase(Locale.ROOT));
    }

    public boolean isHidden(ResourceLocation location) {
        return location != null && isHidden(location.toString());
    }
}
