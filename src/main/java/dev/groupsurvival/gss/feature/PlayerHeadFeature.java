package dev.groupsurvival.gss.feature;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 다른 플레이어에게 죽으면 그 플레이어의 머리를 떨어뜨림.
 * 기본은 바닐라처럼 "닉네임의 머리" (닉네임과 스킨이 그대로 들어감).
 * player-head.anonymous: true 이면 스킨만 담고 닉네임은 넣지 않음.
 */
public final class PlayerHeadFeature implements Listener {

    private final boolean anonymous;

    public PlayerHeadFeature(JavaPlugin plugin) {
        this.anonymous = plugin.getConfig().getBoolean("player-head.anonymous", false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        event.getDrops().add(head(victim));
    }

    private ItemStack head(Player victim) {
        return head(victim.getPlayerProfile());
    }

    /** 이 프로필의 머리 (오프라인 몸이 죽었을 때도 사용) */
    public ItemStack head(PlayerProfile source) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        PlayerProfile profile;
        if (anonymous) {
            // 이름 없이 스킨(textures)만 담은 프로필: 아이템 이름이 "플레이어 머리"로 나옴
            profile = org.bukkit.Bukkit.createProfile(UUID.randomUUID(), null);
            List<ProfileProperty> textures = source.getProperties().stream()
                    .filter(property -> property.getName().equals("textures"))
                    .toList();
            profile.setProperties(textures);
        } else {
            profile = source;
        }
        meta.setPlayerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }
}
