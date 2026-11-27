package dev.groupsurvival.gss.upgrade;

import dev.groupsurvival.gss.effect.PermanentEffects;
import dev.groupsurvival.gss.feature.NightVisionFeature;
import dev.groupsurvival.gss.util.ActionBars;
import dev.groupsurvival.gss.util.FileLog;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * 강화 (/강화, /체력강화, /성급함강화, /포화강화) + 성급함 켜기/끄기 (/성급함, /hs).
 * - 강화 화면은 대화창 (진행 상황, 도전과제, 버튼)
 * - [재료 넣기]를 누르면 투입함(상자 창)이 열리고, 인벤토리의 재료를 직접 클릭해야만 들어감
 *   (일반 클릭 = 1개, 쉬프트 클릭 = 그 칸 전부, 자동 투입 없음). 나눠서 넣을 수 있고, 넣은 재료는 돌려주지 않음
 * - 강화/해금은 반드시 [강화하기] 버튼을 눌러야 함 (준비가 끝나면 액션바로 알림)
 * - 대화창 버튼은 마크 기본 클릭 소리, 투입함(상자 창) 클릭은 플러그인이 클릭음을 냄
 * - [강화 알림] 버튼: 강화·해금 준비가 끝난 것을 액션바에 계속 표시할지 (플레이어별)
 */
public final class UpgradeFeature implements Listener, CommandExecutor {

    public static final String HEALTH = "health";
    public static final String HASTE = "haste";
    public static final String SATURATION = "saturation";
    private static final String[] HASTE_NAMES = {"해금하지 않음", "I", "II", "III", "IV", "V"};
    /** 대화창 버튼 식별자: gss:ui/<탭>/<동작> */
    static final String KEY_NAMESPACE = "gss";

    private final JavaPlugin plugin;
    private final FileLog log;
    private final UpgradeData data;
    private final PermanentEffects effects;
    private final NightVisionFeature nightVision;
    private final UpgradeTrack health;
    private final UpgradeTrack haste;
    private final UpgradeTrack saturation;
    private final Map<String, UpgradeTrack> tracks = new LinkedHashMap<>();
    private final UpgradeScreens screens;
    private final NamespacedKey readyBarKey;
    /** 이미 "준비 완료"를 알린 단계 (트랙:단계). 같은 단계에서는 한 번만 알림 */
    private final Map<UUID, Set<String>> readyNotified = new ConcurrentHashMap<>();
    /** 대화창을 연 적이 있는 플레이어 (플러그인 종료 때 닫기용) */
    private final Set<UUID> dialogViewers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingRefresh = ConcurrentHashMap.newKeySet();

    public UpgradeFeature(JavaPlugin plugin, FileLog log, PermanentEffects effects, NightVisionFeature nightVision) {
        this.plugin = plugin;
        this.log = log;
        this.data = new UpgradeData(plugin);
        this.effects = effects;
        this.nightVision = nightVision;
        this.readyBarKey = new NamespacedKey(plugin, "ready_bar");
        var config = plugin.getConfig();
        this.health = UpgradeTrack.load(HEALTH, "체력", config.getInt("health-upgrade.start-hearts", 8),
                config.getConfigurationSection("health-upgrade.steps"),
                config.getConfigurationSection("health-upgrade.advancements"), level -> level + "하트", plugin.getLogger());
        this.haste = UpgradeTrack.load(HASTE, "성급함", 0,
                config.getConfigurationSection("haste-upgrade.steps"),
                config.getConfigurationSection("haste-upgrade.advancements"),
                level -> level >= 0 && level < HASTE_NAMES.length ? HASTE_NAMES[level] : String.valueOf(level), plugin.getLogger());
        this.saturation = UpgradeTrack.load(SATURATION, "포화", 0,
                config.getConfigurationSection("saturation-upgrade.steps"),
                config.getConfigurationSection("saturation-upgrade.advancements"),
                level -> level <= 0 ? "해금하지 않음" : "해금", plugin.getLogger());
        tracks.put(HEALTH, health);
        tracks.put(HASTE, haste);
        tracks.put(SATURATION, saturation);
        this.screens = new UpgradeScreens(this);

        effects.add(effectSource(PotionEffectType.HASTE, haste, true));
        effects.add(effectSource(PotionEffectType.SATURATION, saturation, false));
    }

    /** 영구 효과: 해금(1단계 이상)하고 켜져 있으면 지급. scaled = 단계에 따라 효과 레벨이 오름 */
    private PermanentEffects.Source effectSource(PotionEffectType type, UpgradeTrack track, boolean scaled) {
        return new PermanentEffects.Source() {
            @Override
            public PotionEffectType type() {
                return type;
            }

            @Override
            public Integer amplifier(Player player) {
                int level = data.level(player, track);
                if (level < 1 || !data.enabled(player, track)) return null;
                return scaled ? level - 1 : 0;
            }
        };
    }

    // ───────────────────────── 화면에서 쓰는 정보 ─────────────────────────

    UpgradeTrack track(String id) {
        UpgradeTrack track = tracks.get(id);
        return track != null ? track : health;
    }

    List<UpgradeTrack> tracks() {
        return List.copyOf(tracks.values());
    }

    UpgradeData data() {
        return data;
    }

    NightVisionFeature nightVision() {
        return nightVision;
    }

    boolean isHealth(UpgradeTrack track) {
        return track == health;
    }

    /** 이 트랙의 다음 단계가 "해금"인지 (성급함/포화 0단계) */
    boolean isUnlock(UpgradeTrack track, int level) {
        return track != health && level == 0;
    }

    String actionWord(UpgradeTrack track, int level) {
        return isUnlock(track, level) ? "해금" : "강화";
    }

    String command(UpgradeTrack track) {
        return switch (track.id()) {
            case HASTE -> "/성급함강화";
            case SATURATION -> "/포화강화";
            default -> "/체력강화";
        };
    }

    // ───────────────────────── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (command.getName().equals("성급함")) { // /hs: 강화 창 없이 바로 켜고 끄기 (/nv 처럼)
            toggle(player, haste);
            return true;
        }
        String tab = switch (command.getName()) {
            case "성급함강화" -> HASTE;
            case "포화강화" -> SATURATION;
            default -> HEALTH;
        };
        open(player, tab);
        return true;
    }

    /** 대화창 열기 */
    public void open(Player player, String tab) {
        dialogViewers.add(player.getUniqueId());
        player.showDialog(screens.dialog(player, track(tab)));
    }

    /** 투입함 열기 */
    private void openDeposit(Player player, UpgradeTrack track) {
        int level = data.level(player, track);
        if (level >= track.max()) {
            fail(player, "이미 최대 단계입니다");
            open(player, track.id());
            return;
        }
        DepositMenu menu = new DepositMenu(player.getUniqueId(), track.id(), track.requirements(level).size());
        Inventory inventory = plugin.getServer().createInventory(menu, menu.size(),
                Component.text("재료 넣기 · " + track.name() + " " + track.label(level) + " → " + track.label(level + 1)));
        menu.setInventory(inventory);
        screens.renderDeposit(player, menu);
        player.openInventory(inventory);
    }

    /** 플러그인 종료 시 열린 강화 창을 닫음 (창의 장식 아이템을 꺼낼 수 없도록). */
    public void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof DepositMenu) player.closeInventory();
            if (dialogViewers.contains(player.getUniqueId())) player.closeDialog();
        }
    }

    // ───────────────────────── 관리자 (콘솔) ─────────────────────────

    public UpgradeTrack healthTrack() {
        return health;
    }

    public UpgradeTrack hasteTrack() {
        return haste;
    }

    public UpgradeTrack saturationTrack() {
        return saturation;
    }

    public int level(Player player, UpgradeTrack track) {
        return data.level(player, track);
    }

    /** 단계를 직접 바꿈 (지금 단계에 넣은 재료는 초기화) */
    public void setLevel(Player player, UpgradeTrack track, int level) {
        data.setLevel(player, track, level);
        if (track == health) applyHealth(player);
        else effects.refresh(player);
    }

    // ───────────────────────── 체력 적용 ─────────────────────────

    public void applyHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double target = data.level(player, health) * 2.0;
        if (attribute.getBaseValue() != target) attribute.setBaseValue(target);
        if (player.getHealth() > attribute.getValue()) player.setHealth(attribute.getValue());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        applyHealth(player);
        // 접속했을 때 이미 준비된 강화가 있으면 알려줌
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) notifyReady(player);
        }, null, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        readyNotified.remove(id);
        dialogViewers.remove(id);
        pendingRefresh.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) return;
            applyHealth(player);
            AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
            if (attribute != null) player.setHealth(attribute.getValue());
        }, null, 1L);
    }

    // ───────────────────────── 준비 완료 ─────────────────────────

    /** 재료와 도전과제 조건이 모두 채워져 버튼만 누르면 되는 상태인지 */
    boolean isReady(Player player, UpgradeTrack track) {
        int level = data.level(player, track);
        if (level >= track.max()) return false;
        Map<String, Integer> progress = data.progress(player, track);
        for (Requirement requirement : track.requirements(level)) {
            if (progress.getOrDefault(requirement.key(), 0) < requirement.amount()) return false;
        }
        AdvancementRequirement advancementRequirement = track.advancementRequirement(level);
        return advancementRequirement == null || advancementRequirement.met(player);
    }

    /** 강화 준비 액션바를 보는지 (플레이어가 강화 창에서 켜고 끔, 기본 켜짐) */
    boolean readyBarEnabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(readyBarKey, PersistentDataType.BOOLEAN, true);
    }

    /**
     * 상시 액션바에 보여 줄 항목 (넣은 재료 + 인벤토리 재료로 지금 강화할 수 있는 것). 강화(금색)와 해금(노란색)을 나눔 (예: ["&6체력 · 성급함 강화 가능", "&e포화 해금 가능"]).
     * 플레이어가 표시를 껐으면 빈 목록
     */
    public List<String> readyUpgradesForBar(Player player) {
        if (!readyBarEnabled(player)) return List.of();
        List<String> upgrades = new ArrayList<>();
        List<String> unlocks = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        for (UpgradeTrack track : tracks.values()) {
            if (!materialsAvailable(player, track)) continue;
            int level = data.level(player, track);
            AdvancementRequirement advancementRequirement = track.advancementRequirement(level);
            if (advancementRequirement != null && !advancementRequirement.met(player)) {
                materials.add(track.name()); // 재료는 다 모았지만 도전과제가 아직
            } else {
                (isUnlock(track, level) ? unlocks : upgrades).add(track.name());
            }
        }
        List<String> result = new ArrayList<>();
        if (!upgrades.isEmpty()) result.add("&6" + String.join(" · ", upgrades) + " 강화 가능"); // 강화 = 금색
        if (!unlocks.isEmpty()) result.add("&e" + String.join(" · ", unlocks) + " 해금 가능");   // 해금 = 노란색
        if (!materials.isEmpty()) result.add("&a" + String.join(" · ", materials) + " 재료 충족"); // 도전과제만 남음 = 연두색
        return result;
    }

    /**
     * 지금 바로 강화할 수 있는지: 도전과제 조건을 채웠고, 재료가 [넣은 양 + 인벤토리에 있는 양]으로 모두 충분함.
     * (재료를 아직 안 넣었어도 인벤토리에 다 있으면 강화 알림에 표시)
     */
    boolean canUpgradeNow(Player player, UpgradeTrack track) {
        int level = data.level(player, track);
        if (level >= track.max()) return false;
        AdvancementRequirement advancementRequirement = track.advancementRequirement(level);
        if (advancementRequirement != null && !advancementRequirement.met(player)) return false;
        return materialsAvailable(player, track);
    }

    /** 재료가 [넣은 양 + 인벤토리에 있는 양]으로 모두 충분한지 (도전과제는 보지 않음) */
    boolean materialsAvailable(Player player, UpgradeTrack track) {
        int level = data.level(player, track);
        if (level >= track.max()) return false;
        Map<String, Integer> progress = data.progress(player, track);
        ItemStack[] storage = player.getInventory().getStorageContents(); // 투입함에서 넣을 수 있는 칸 (0~35)
        for (Requirement requirement : track.requirements(level)) {
            int need = requirement.amount() - progress.getOrDefault(requirement.key(), 0);
            for (ItemStack stack : storage) {
                if (need <= 0) break;
                if (stack != null && !stack.getType().isAir() && requirement.matches(stack.getType())) need -= stack.getAmount();
            }
            if (need > 0) return false;
        }
        return true;
    }

    /** 버튼만 누르면 되는 항목 (예: "체력 강화", "포화 해금") */
    public List<String> readyUpgrades(Player player) {
        List<String> result = new ArrayList<>();
        for (UpgradeTrack track : tracks.values()) {
            if (isReady(player, track)) result.add(track.name() + " " + actionWord(track, data.level(player, track)));
        }
        return result;
    }

    /**
     * 강화 준비가 끝난 트랙이 있으면 액션바로 알림. 해금/강화는 자동으로 되지 않고 반드시 버튼을 눌러야 함.
     * 같은 단계에서는 한 번만 알림 (재접속하면 다시 알림).
     */
    private void notifyReady(Player player) {
        Set<String> notified = readyNotified.computeIfAbsent(player.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
        for (UpgradeTrack track : tracks.values()) {
            if (!isReady(player, track)) continue;
            int level = data.level(player, track);
            if (!notified.add(track.id() + ":" + level)) continue;
            String action = actionWord(track, level);
            ActionBars.send(player, Component.text(track.name() + " " + action + " 준비 완료! ", NamedTextColor.GREEN)
                    .append(Component.text(command(track) + " 창에서 버튼을 눌러 " + action + "하세요", NamedTextColor.YELLOW)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            return; // 한 번에 하나만 (액션바가 겹치지 않게)
        }
    }

    /** 도전과제를 달성하면 조건이 채워졌는지 확인해 "준비 완료"를 알림. 여러 개를 한꺼번에 달성해도 한 번만 처리. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!pendingRefresh.add(player.getUniqueId())) return;
        player.getScheduler().run(plugin, task -> {
            pendingRefresh.remove(player.getUniqueId());
            if (player.isOnline()) notifyReady(player);
        }, () -> pendingRefresh.remove(player.getUniqueId()));
    }

    // ───────────────────────── 효과음 ─────────────────────────

    private void sound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** 투입함(상자 창) 클릭음. 대화창 버튼은 마크가 기본 클릭 소리를 내므로 쓰지 않음 */
    private void click(Player player) {
        sound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    // ───────────────────────── 대화창 버튼 ─────────────────────────

    static Key buttonKey(UpgradeTrack tab, String action) {
        return Key.key(KEY_NAMESPACE, "ui/" + tab.id() + "/" + action);
    }

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals(KEY_NAMESPACE) || !key.value().startsWith("ui/")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        String[] parts = key.value().split("/");
        if (parts.length != 3 || !tracks.containsKey(parts[1])) return;
        UpgradeTrack tab = tracks.get(parts[1]);
        String action = parts[2];
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) handleButton(player, tab, action);
        }, null);
    }

    private void handleButton(Player player, UpgradeTrack tab, String action) {
        switch (action) {
            case "close" -> {
                player.closeDialog();
                return;
            }
            case "deposit" -> {
                openDeposit(player, tab);
                return;
            }
            case "upgrade" -> upgrade(player, tab);
            case "ready_bar" -> {
                boolean enable = !readyBarEnabled(player);
                player.getPersistentDataContainer().set(readyBarKey, PersistentDataType.BOOLEAN, enable);
                ActionBars.send(player, Component.text(enable ? "강화 준비 알림 켜짐" : "강화 준비 알림 꺼짐",
                        enable ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            }
            case "nv" -> {
                if (nightVision != null) nightVision.toggle(player);
            }
            case "toggle_haste" -> toggle(player, haste);
            case "toggle_saturation" -> toggle(player, saturation);
            default -> {
                if (action.startsWith("tab_")) {
                    open(player, action.substring(4));
                    return;
                }
            }
        }
        open(player, tab.id()); // 바뀐 내용으로 다시 그림
    }

    private void toggle(Player player, UpgradeTrack track) {
        if (data.level(player, track) < 1) {
            fail(player, track.nameObject() + " 해금한 뒤에 켜고 끌 수 있습니다");
            return;
        }
        boolean enable = !data.enabled(player, track);
        data.setEnabled(player, track, enable);
        effects.refresh(player);
        ActionBars.send(player, Component.text(track.name() + (enable ? " 켜짐" : " 꺼짐"),
                enable ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    private void upgrade(Player player, UpgradeTrack track) {
        int level = data.level(player, track);
        if (level >= track.max()) {
            fail(player, "이미 최대 단계입니다");
            return;
        }
        Map<String, Integer> progress = data.progress(player, track);
        for (Requirement requirement : track.requirements(level)) {
            if (progress.getOrDefault(requirement.key(), 0) < requirement.amount()) {
                fail(player, "재료가 부족합니다 · [재료 넣기]로 넣어 주세요");
                return;
            }
        }
        AdvancementRequirement advancementRequirement = track.advancementRequirement(level);
        if (advancementRequirement != null && !advancementRequirement.met(player)) {
            fail(player, (advancementRequirement.isSpecific() ? "필요한 도전과제를 아직 다 달성하지 않았습니다 (" : "도전과제가 부족합니다 (")
                    + advancementRequirement.progress(player) + " / " + advancementRequirement.target() + "개)");
            return;
        }
        String action = actionWord(track, level);
        int next = level + 1;
        data.setLevel(player, track, next);
        if (track == health) {
            applyHealth(player);
            AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
            double max = attribute != null ? attribute.getValue() : next * 2.0;
            player.setHealth(Math.min(max, player.getHealth() + 2.0)); // 늘어난 만큼만 회복
        } else {
            data.setEnabled(player, track, true);
            effects.refresh(player);
        }
        String file = switch (track.id()) {
            case HASTE -> "시스템/강화/성급함.log";
            case SATURATION -> "시스템/강화/포화.log";
            default -> "시스템/강화/체력.log";
        };
        log.write(file, FileLog.line(player, player.getLocation(),
                track.name() + " " + level + "->" + next + " " + action));
        sound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        ActionBars.send(player, Component.text(isUnlock(track, level)
                ? track.nameObject() + " 해금했습니다!"
                : track.name() + " " + track.label(next) + "(으)로 강화되었습니다!", NamedTextColor.GREEN));
    }

    private void fail(Player player, String message) {
        fail(player, Component.text(message));
    }

    private void fail(Player player, Component message) {
        sound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        ActionBars.send(player, message.colorIfAbsent(NamedTextColor.RED));
    }

    // ───────────────────────── 투입함 클릭 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DepositMenu menu)) return;
        event.setCancelled(true); // 창 안에서는 어떤 아이템도 직접 옮길 수 없음
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        int slot = event.getSlot();
        ClickType click = event.getClick();
        boolean all = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (click != ClickType.LEFT && click != ClickType.RIGHT && !all) return; // 숫자키/더블클릭/버리기 등은 무시

        if (clicked == event.getView().getTopInventory()) {
            if (all) return;
            player.getScheduler().run(plugin, task -> {
                if (!stillOpen(player, menu)) return;
                click(player);
                if (slot != menu.backSlot()) return;
                open(player, menu.tab()); // 대화창이 열리면서 투입함은 닫힘
            }, null);
            return;
        }
        if (clicked == event.getView().getBottomInventory()) {
            // 재료 넣기: 일반 클릭(좌/우) = 1개, 쉬프트 클릭 = 그 칸 전부(필요한 만큼만)
            boolean one = !all;
            player.getScheduler().run(plugin, task -> deposit(player, menu, slot, one), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DepositMenu) event.setCancelled(true);
    }

    private boolean stillOpen(Player player, DepositMenu menu) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == menu;
    }

    private void deposit(Player player, DepositMenu menu, int slot, boolean one) {
        if (!stillOpen(player, menu)) return;
        PlayerInventory inventory = player.getInventory();
        if (slot < 0 || slot >= 36) return;
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType().isAir()) return;

        UpgradeTrack track = track(menu.tab());
        int level = data.level(player, track);
        if (level >= track.max()) {
            fail(player, "이미 최대 단계입니다");
            return;
        }
        Requirement requirement = null;
        for (Requirement candidate : track.requirements(level)) {
            if (candidate.matches(stack.getType())) {
                requirement = candidate;
                break;
            }
        }
        if (requirement == null) {
            fail(player, Component.translatable(stack.getType().translationKey()).append(Component.text("은(는) 이 단계의 재료가 아닙니다")));
            return;
        }
        Map<String, Integer> progress = data.progress(player, track);
        int have = progress.getOrDefault(requirement.key(), 0);
        int remaining = requirement.amount() - have;
        if (remaining <= 0) {
            fail(player, requirement.name().append(Component.text("은(는) 이미 다 넣었습니다")));
            return;
        }
        int take;
        if (one) {
            take = 1;
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
        } else {
            // 쉬프트+클릭: 클릭한 칸부터 인벤토리의 같은 재료를 전부 (필요한 만큼만)
            // 그룹 재료(구운 고기·생선, 콘크리트)는 그룹에 속하는 아이템 전부
            take = 0;
            List<Integer> slots = new ArrayList<>();
            slots.add(slot);
            for (int i = 0; i < 36; i++) if (i != slot) slots.add(i);
            for (int i : slots) {
                if (take >= remaining) break;
                ItemStack item = inventory.getItem(i);
                if (item == null || item.getType().isAir() || !requirement.matches(item.getType())) continue;
                int n = Math.min(remaining - take, item.getAmount());
                item.setAmount(item.getAmount() - n);
                inventory.setItem(i, item.getAmount() > 0 ? item : null);
                take += n;
            }
        }
        progress.put(requirement.key(), have + take);
        data.setProgress(player, track, progress);

        sound(player, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
        ActionBars.send(player, Component.empty().color(NamedTextColor.GREEN)
                .append(requirement.name())
                .append(Component.text(" " + take + "개를 넣었습니다 (" + (have + take) + " / " + requirement.amount() + ")")));
        screens.renderDeposit(player, menu);
        notifyReady(player); // 마지막 재료였다면 "준비 완료" 알림 (넣은 메시지 대신 표시됨)
    }
}
