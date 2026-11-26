package dev.groupsurvival.gss.upgrade;

import dev.groupsurvival.gss.effect.PermanentEffects;
import dev.groupsurvival.gss.feature.NightVisionFeature;
import dev.groupsurvival.gss.util.FileLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * 통합 강화 창 (/강화, /체력강화, /성급함강화).
 * - 왼쪽 세로줄: 탭 (체력 / 성급함 / 불토뭉치 준비 중)
 * - 맨 윗줄: 단계 로드맵, 가운데: 재료 + 게이지, 아랫줄: 사용법 / 강화 / 야간투시·성급함 스위치 / 닫기
 * - 재료는 플레이어가 인벤토리의 아이템을 직접 클릭해야만 들어감 (좌클릭 = 그 칸 전부, 우클릭 = 1개, 자동 투입 없음)
 * - 재료는 나눠서 넣을 수 있고, 넣은 재료는 돌려주지 않음
 */
public final class UpgradeFeature implements Listener, CommandExecutor {

    public static final String HEALTH = "health";
    public static final String HASTE = "haste";
    private static final String[] HASTE_NAMES = {"해금하지 않음", "I", "II", "III", "IV", "V"};

    private final JavaPlugin plugin;
    private final FileLog log;
    private final UpgradeData data;
    private final PermanentEffects effects;
    private final NightVisionFeature nightVision;
    private final UpgradeTrack health;
    private final UpgradeTrack haste;
    private final Set<UUID> pendingRefresh = ConcurrentHashMap.newKeySet();

    public UpgradeFeature(JavaPlugin plugin, FileLog log, PermanentEffects effects, NightVisionFeature nightVision) {
        this.plugin = plugin;
        this.log = log;
        this.data = new UpgradeData(plugin);
        this.effects = effects;
        this.nightVision = nightVision;
        var config = plugin.getConfig();
        this.health = UpgradeTrack.load(HEALTH, "체력", config.getInt("health-upgrade.start-hearts", 8),
                config.getConfigurationSection("health-upgrade.steps"),
                config.getConfigurationSection("health-upgrade.advancements"), level -> level + "하트", plugin.getLogger());
        this.haste = UpgradeTrack.load(HASTE, "성급함", 0,
                config.getConfigurationSection("haste-upgrade.steps"),
                config.getConfigurationSection("haste-upgrade.advancements"),
                level -> level >= 0 && level < HASTE_NAMES.length ? HASTE_NAMES[level] : String.valueOf(level), plugin.getLogger());
        effects.add(new PermanentEffects.Source() {
            @Override
            public PotionEffectType type() {
                return PotionEffectType.HASTE;
            }

            @Override
            public Integer amplifier(Player player) {
                int level = data.level(player, haste);
                return level >= 1 && data.enabled(player, haste) ? level - 1 : null;
            }
        });
    }

    private UpgradeTrack track(String id) {
        return HASTE.equals(id) ? haste : health;
    }

    // ───────────────────────── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        open(player, command.getName().equals("성급함강화") ? HASTE : HEALTH);
        return true;
    }

    public void open(Player player, String tab) {
        UpgradeTrack track = track(tab);
        UpgradeMenu menu = new UpgradeMenu(player.getUniqueId(), track.id());
        Inventory inventory = plugin.getServer().createInventory(menu, 54, Component.text("강화 · " + track.name()));
        menu.setInventory(inventory);
        render(player, menu);
        player.openInventory(inventory);
    }

    /** 플러그인 종료 시 열린 강화 창을 닫음 (창의 장식 아이템을 꺼낼 수 없도록). */
    public void closeAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof UpgradeMenu) player.closeInventory();
        }
    }

    // ───────────────────────── 관리자 (콘솔) ─────────────────────────

    public void setHealthLevel(Player player, int hearts) {
        data.setLevel(player, health, hearts);
        applyHealth(player);
    }

    public void setHasteLevel(Player player, int level) {
        data.setLevel(player, haste, level);
        effects.refresh(player);
    }

    public UpgradeTrack healthTrack() {
        return health;
    }

    public UpgradeTrack hasteTrack() {
        return haste;
    }

    public int level(Player player, UpgradeTrack track) {
        return data.level(player, track);
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
        applyHealth(event.getPlayer());
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

    // ───────────────────────── 클릭 처리 ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof UpgradeMenu menu)) return;
        event.setCancelled(true); // 창 안에서는 어떤 아이템도 직접 옮길 수 없음
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        int slot = event.getSlot();

        if (clicked == event.getView().getTopInventory()) {
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
            player.getScheduler().run(plugin, task -> clickMenu(player, menu, slot), null);
            return;
        }
        if (clicked == event.getView().getBottomInventory()) {
            // 재료 넣기: 좌클릭 = 그 칸 전부(필요한 만큼만), 우클릭 = 1개. 쉬프트/숫자키/더블클릭 등은 무시
            ClickType click = event.getClick();
            if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
            boolean one = click == ClickType.RIGHT;
            player.getScheduler().run(plugin, task -> deposit(player, menu, slot, one), null);
        }
    }

    /** 창을 연 채로 도전과제를 달성하면 다시 그림 (한꺼번에 여러 개 달성해도 다음 틱에 한 번만). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof UpgradeMenu)) return;
        if (!pendingRefresh.add(player.getUniqueId())) return;
        player.getScheduler().run(plugin, task -> {
            pendingRefresh.remove(player.getUniqueId());
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof UpgradeMenu menu) {
                render(player, menu);
            }
        }, () -> pendingRefresh.remove(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UpgradeMenu) event.setCancelled(true);
    }

    private boolean stillOpen(Player player, UpgradeMenu menu) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == menu;
    }

    private void clickMenu(Player player, UpgradeMenu menu, int slot) {
        if (!stillOpen(player, menu)) return;
        switch (slot) {
            case UpgradeMenu.TAB_HEALTH -> {
                if (!menu.tab().equals(HEALTH)) open(player, HEALTH);
            }
            case UpgradeMenu.TAB_HASTE -> {
                if (!menu.tab().equals(HASTE)) open(player, HASTE);
            }
            case UpgradeMenu.UPGRADE -> upgrade(player, menu);
            case UpgradeMenu.TOGGLE_NIGHT_VISION -> {
                if (nightVision == null) return;
                nightVision.toggle(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                render(player, menu);
            }
            case UpgradeMenu.TOGGLE_HASTE -> toggleHaste(player, menu);
            case UpgradeMenu.CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    private void deposit(Player player, UpgradeMenu menu, int slot, boolean one) {
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
        int take = one ? 1 : Math.min(remaining, stack.getAmount());
        stack.setAmount(stack.getAmount() - take);
        inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
        progress.put(requirement.key(), have + take);
        data.setProgress(player, track, progress);

        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
        player.sendActionBar(Component.empty().color(NamedTextColor.GREEN)
                .append(requirement.name())
                .append(Component.text(" " + take + "개를 넣었습니다 (" + (have + take) + " / " + requirement.amount() + ")")));
        render(player, menu);
    }

    private void upgrade(Player player, UpgradeMenu menu) {
        UpgradeTrack track = track(menu.tab());
        int level = data.level(player, track);
        if (level >= track.max()) {
            fail(player, "이미 최대 단계입니다");
            return;
        }
        Map<String, Integer> progress = data.progress(player, track);
        for (Requirement requirement : track.requirements(level)) {
            if (progress.getOrDefault(requirement.key(), 0) < requirement.amount()) {
                fail(player, "재료가 부족합니다");
                return;
            }
        }
        AdvancementRequirement advancementRequirement = track.advancementRequirement(level);
        if (advancementRequirement != null && !advancementRequirement.met(player)) {
            fail(player, (advancementRequirement.isSpecific() ? "필요한 도전과제를 아직 다 달성하지 않았습니다 (" : "도전과제가 부족합니다 (")
                    + advancementRequirement.progress(player) + " / " + advancementRequirement.target() + "개)");
            return;
        }
        int next = level + 1;
        data.setLevel(player, track, next);
        if (track == health) {
            applyHealth(player);
            AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
            double max = attribute != null ? attribute.getValue() : next * 2.0;
            player.setHealth(Math.min(max, player.getHealth() + 2.0)); // 늘어난 만큼만 회복
            log.write("시스템/강화/체력.log", FileLog.line(player, player.getLocation(), "체력 " + level + "->" + next + " 강화"));
        } else {
            data.setEnabled(player, track, true);
            effects.refresh(player);
            log.write("시스템/강화/성급함.log", FileLog.line(player, player.getLocation(), "성급함 " + level + "->" + next + " 강화"));
        }
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        player.sendActionBar(Component.text(track.name() + " " + track.label(next) + "(으)로 강화되었습니다!", NamedTextColor.GREEN));
        render(player, menu);
    }

    private void toggleHaste(Player player, UpgradeMenu menu) {
        if (data.level(player, haste) < 1) {
            fail(player, "성급함을 해금한 뒤에 켜고 끌 수 있습니다");
            return;
        }
        boolean enable = !data.enabled(player, haste);
        data.setEnabled(player, haste, enable);
        effects.refresh(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        player.sendActionBar(Component.text(enable ? "성급함 켜짐" : "성급함 꺼짐", enable ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        render(player, menu);
    }

    private void fail(Player player, String message) {
        fail(player, Component.text(message));
    }

    private void fail(Player player, Component message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        player.sendActionBar(message.colorIfAbsent(NamedTextColor.RED));
    }

    // ───────────────────────── 창 그리기 ─────────────────────────

    private static Component text(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component plain(Component component, TextColor color) {
        return component.colorIfAbsent(color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, int amount, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        int count = Math.max(1, Math.min(99, amount));
        if (count > material.getMaxStackSize()) meta.setMaxStackSize(99);
        stack.setItemMeta(meta);
        stack.setAmount(count);
        return stack;
    }

    private static ItemStack pane(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setHideTooltip(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private void render(Player player, UpgradeMenu menu) {
        Inventory inv = menu.getInventory();
        UpgradeTrack track = track(menu.tab());
        boolean healthTab = track == health;
        int level = data.level(player, track);
        boolean maxed = level >= track.max();
        List<Requirement> reqs = maxed ? List.of() : track.requirements(level);
        Map<String, Integer> progress = data.progress(player, track);

        // 배경
        Material accent = healthTab ? Material.RED_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE;
        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            Material bg = col == 0 ? Material.GRAY_STAINED_GLASS_PANE
                    : row == 0 ? accent
                    : row == 5 ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
                    : Material.BLACK_STAINED_GLASS_PANE;
            inv.setItem(i, pane(bg));
        }

        // 탭
        int healthLevel = data.level(player, health);
        inv.setItem(UpgradeMenu.TAB_HEALTH, item(Material.RED_DYE, 1, text("체력 강화", NamedTextColor.RED),
                List.of(Component.empty(), text("현재: " + healthLevel + "하트 / " + health.max() + "하트", NamedTextColor.WHITE),
                        text(healthTab ? "보고 있는 탭" : "클릭해서 열기", NamedTextColor.YELLOW)), healthTab));
        int hasteLevel = data.level(player, haste);
        Material pick = hasteLevel >= 2 ? Material.NETHERITE_PICKAXE : hasteLevel == 1 ? Material.DIAMOND_PICKAXE : Material.IRON_PICKAXE;
        inv.setItem(UpgradeMenu.TAB_HASTE, item(pick, 1, text("성급함 강화", NamedTextColor.GOLD),
                List.of(Component.empty(), text("현재: " + haste.label(hasteLevel), NamedTextColor.WHITE),
                        text(!healthTab ? "보고 있는 탭" : "클릭해서 열기", NamedTextColor.YELLOW)), !healthTab));
        inv.setItem(UpgradeMenu.TAB_FIRE, item(Material.GRAY_DYE, 1, text("불토뭉치 제조", NamedTextColor.GRAY),
                List.of(Component.empty(), text("준비 중입니다", NamedTextColor.DARK_GRAY)), false));

        // 단계 로드맵 (맨 윗줄 1~8)
        List<Integer> targets = new ArrayList<>();
        for (int target = track.start() + 1; target <= track.max(); target++) targets.add(target);
        int focus = targets.indexOf(Math.min(level + 1, track.max()));
        int from = Math.max(0, Math.min(targets.size() - 8, focus - 2));
        for (int n = 0; n < 8 && from + n < targets.size(); n++) {
            int target = targets.get(from + n);
            boolean done = target <= level, now = target == level + 1;
            Material dye = done ? Material.LIME_DYE : now ? Material.YELLOW_DYE : Material.GRAY_DYE;
            TextColor color = done ? NamedTextColor.GREEN : now ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
            List<Component> lore = new ArrayList<>();
            lore.add(text(done ? "완료한 단계" : now ? "진행 중인 단계" : "아직 잠긴 단계", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(text("필요 재료", NamedTextColor.WHITE));
            for (Requirement r : track.requirements(target - 1)) {
                lore.add(text("· ", NamedTextColor.GRAY).append(plain(r.name(), NamedTextColor.GRAY))
                        .append(text(" " + r.amount() + "개", NamedTextColor.GRAY)));
            }
            AdvancementRequirement roadmapAdvancements = track.advancementRequirement(target - 1);
            if (roadmapAdvancements != null) {
                if (roadmapAdvancements.isSpecific()) {
                    lore.add(text("· 도전과제 달성:", NamedTextColor.LIGHT_PURPLE));
                    for (Advancement advancement : roadmapAdvancements.advancements()) {
                        lore.add(text("   ", NamedTextColor.LIGHT_PURPLE)
                                .append(plain(AdvancementRequirement.title(advancement), NamedTextColor.LIGHT_PURPLE)));
                    }
                } else {
                    lore.add(text("· 도전과제 " + roadmapAdvancements.target() + "개 달성", NamedTextColor.LIGHT_PURPLE));
                }
            }
            inv.setItem(1 + n, item(dye, healthTab ? target : Math.max(1, target),
                    text(track.name() + " " + track.label(target), color), lore, now));
        }

        // 재료 + 게이지 (2~5줄)
        int[] cols = UpgradeMenu.materialColumns(reqs.size());
        int doneCount = 0;
        for (int n = 0; n < reqs.size() && n < cols.length; n++) {
            Requirement r = reqs.get(n);
            int col = cols[n];
            int have = Math.min(r.amount(), progress.getOrDefault(r.key(), 0));
            boolean done = have >= r.amount();
            if (done) doneCount++;
            double ratio = (double) have / r.amount();
            int percent = (int) Math.floor(ratio * 100);
            List<Component> lore = List.of(Component.empty(),
                    text("넣은 수량: " + have + " / " + r.amount(), NamedTextColor.WHITE),
                    done ? text("완료", NamedTextColor.GREEN) : text("남은 수량: " + (r.amount() - have) + "개", NamedTextColor.YELLOW),
                    Component.empty(),
                    text("인벤토리에서 이 재료를 클릭해 넣으세요", NamedTextColor.GRAY),
                    text("좌클릭: 그 칸 전부 · 우클릭: 1개", NamedTextColor.DARK_GRAY));
            inv.setItem(9 + col, item(r.icon(), done ? 1 : r.amount() - have, plain(r.name(), NamedTextColor.GOLD), lore, done));
            for (int k = 0; k < 3; k++) {
                boolean full = ratio >= (k + 1) / 3.0 - 1e-9;
                boolean part = !full && ratio > k / 3.0;
                Material gauge = full ? Material.LIME_STAINED_GLASS_PANE
                        : part ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                inv.setItem(9 * (4 - k) + col, item(gauge, 1,
                        plain(r.name(), NamedTextColor.GOLD).append(text(" " + percent + "%", NamedTextColor.WHITE)), lore, false));
            }
        }
        if (maxed) {
            inv.setItem(23, item(Material.NETHER_STAR, 1, text(track.name() + " 최대 단계 달성", NamedTextColor.GREEN),
                    List.of(Component.empty(), text("현재: " + track.label(level), NamedTextColor.WHITE)), true));
        }

        // 아랫줄
        inv.setItem(UpgradeMenu.INFO, item(Material.BOOK, 1, text("사용법", NamedTextColor.YELLOW), List.of(Component.empty(),
                text("인벤토리의 재료를 클릭하면 들어갑니다", NamedTextColor.WHITE),
                text("좌클릭: 그 칸 전부 (필요한 만큼만)", NamedTextColor.GRAY),
                text("우클릭: 1개", NamedTextColor.GRAY),
                text("재료는 나눠서 넣을 수 있습니다", NamedTextColor.GRAY),
                text("넣은 재료는 돌려받을 수 없습니다", NamedTextColor.RED)), false));

        boolean materialsDone = !maxed && doneCount == reqs.size();
        AdvancementRequirement advancementRequirement = maxed ? null : track.advancementRequirement(level);
        int needAdvancements = advancementRequirement == null ? 0 : advancementRequirement.target();
        int haveAdvancements = advancementRequirement == null ? 0 : advancementRequirement.progress(player);
        boolean advancementsDone = haveAdvancements >= needAdvancements;
        boolean complete = materialsDone && advancementsDone;
        String step = maxed ? "" : track.label(level) + " → " + track.label(level + 1);
        if (advancementRequirement != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            if (advancementRequirement.isSpecific()) {
                lore.add(text("이번 단계는 아래 도전과제를 모두 달성해야 강화할 수 있습니다", NamedTextColor.WHITE));
                for (Advancement advancement : advancementRequirement.advancements()) {
                    boolean done = player.getAdvancementProgress(advancement).isDone();
                    lore.add(text(done ? " ✔ " : " ✘ ", done ? NamedTextColor.GREEN : NamedTextColor.RED)
                            .append(plain(AdvancementRequirement.title(advancement), done ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
                }
            } else {
                lore.add(text("이번 단계는 도전과제를 " + needAdvancements + "개 이상 달성해야 강화할 수 있습니다", NamedTextColor.WHITE));
                lore.add(advancementsDone ? text("조건 충족", NamedTextColor.GREEN)
                        : text("앞으로 " + (needAdvancements - haveAdvancements) + "개 더 필요", NamedTextColor.YELLOW));
                lore.add(text("레시피 해금은 세지 않습니다", NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            lore.add(text("재료는 미리 넣어 둘 수 있습니다", NamedTextColor.GRAY));
            inv.setItem(UpgradeMenu.ADVANCEMENTS, item(Material.KNOWLEDGE_BOOK, 1,
                    text("도전과제 " + haveAdvancements + " / " + needAdvancements, advancementsDone ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE),
                    lore, advancementsDone));
        }
        if (maxed) {
            inv.setItem(UpgradeMenu.UPGRADE, item(Material.NETHER_STAR, 1, text("최대 단계입니다", NamedTextColor.GRAY), List.of(), false));
        } else if (materialsDone && !advancementsDone) {
            inv.setItem(UpgradeMenu.UPGRADE, item(Material.PURPLE_CONCRETE, 1, text("도전과제가 부족합니다", NamedTextColor.LIGHT_PURPLE),
                    List.of(Component.empty(), text(step, NamedTextColor.WHITE),
                            text("재료는 모두 넣었습니다", NamedTextColor.GREEN),
                            text("도전과제 " + haveAdvancements + " / " + needAdvancements + "개", NamedTextColor.YELLOW)), false));
        } else if (complete) {
            inv.setItem(UpgradeMenu.UPGRADE, item(Material.LIME_CONCRETE, 1, text(track.name() + " 강화하기", NamedTextColor.GREEN),
                    List.of(Component.empty(), text(step, NamedTextColor.WHITE), text("클릭해서 강화", NamedTextColor.YELLOW)), true));
        } else {
            List<Component> lore = new ArrayList<>(List.of(Component.empty(), text(step, NamedTextColor.WHITE),
                    text("재료 " + doneCount + " / " + reqs.size() + "개 완료", NamedTextColor.WHITE)));
            if (advancementRequirement != null) {
                lore.add(text("도전과제 " + haveAdvancements + " / " + needAdvancements + "개", advancementsDone ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            inv.setItem(UpgradeMenu.UPGRADE, item(Material.GRAY_CONCRETE, Math.max(1, doneCount), text("재료가 부족합니다", NamedTextColor.RED),
                    lore, false));
        }

        if (nightVision != null) {
            boolean on = nightVision.isEnabled(player);
            inv.setItem(UpgradeMenu.TOGGLE_NIGHT_VISION, item(on ? Material.LIGHT_BLUE_DYE : Material.GRAY_DYE, 1,
                    text(on ? "야간투시 켜짐" : "야간투시 꺼짐", on ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                    List.of(Component.empty(), text("클릭해서 " + (on ? "끄기" : "켜기"), NamedTextColor.YELLOW),
                            text("/nv 명령어로도 켜고 끌 수 있습니다", NamedTextColor.GRAY),
                            text("입자 없이 지급, 죽어도 유지", NamedTextColor.GRAY)), on));
        }
        if (hasteLevel < 1) {
            inv.setItem(UpgradeMenu.TOGGLE_HASTE, item(Material.GRAY_DYE, 1, text("성급함 잠김", NamedTextColor.GRAY),
                    List.of(Component.empty(), text("성급함을 해금한 뒤에 켜고 끌 수 있습니다", NamedTextColor.DARK_GRAY)), false));
        } else {
            boolean on = data.enabled(player, haste);
            String name = "성급함 " + haste.label(hasteLevel) + (on ? " 켜짐" : " 꺼짐");
            inv.setItem(UpgradeMenu.TOGGLE_HASTE, item(on ? Material.ORANGE_DYE : Material.GRAY_DYE, 1,
                    text(name, on ? NamedTextColor.GOLD : NamedTextColor.GRAY),
                    List.of(Component.empty(), text("클릭해서 " + (on ? "끄기" : "켜기"), NamedTextColor.YELLOW),
                            text("입자 없이 지급, 죽어도 유지", NamedTextColor.GRAY)), on));
        }
        inv.setItem(UpgradeMenu.CLOSE, item(Material.BARRIER, 1, text("닫기", NamedTextColor.RED), List.of(), false));
    }
}
