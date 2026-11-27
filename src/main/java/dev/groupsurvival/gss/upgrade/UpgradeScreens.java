package dev.groupsurvival.gss.upgrade;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 강화 화면 그리기.
 * - 대화창: 단계, (체력) 하트 줄, 재료 전체 진행 막대, 도전과제(✔ 초록 / ✘ 빨강), 버튼.
 *   본문 줄 수가 항상 같아서 버튼 위치가 바뀌지 않음
 * - 투입함: 재료 줄(남은 개수) + 게이지 줄(%) + 아랫줄(사용법 · 돌아가기)
 */
final class UpgradeScreens {

    private static final int BAR_CELLS = 10;
    private static final int BUTTON_WIDTH = 100;
    private static final int TEXT_WIDTH = 300;

    private final UpgradeFeature feature;

    /** 도전과제 목록 칸 수 = 설정에서 가장 긴 지정형 목록 (모든 화면의 줄 수를 맞추기 위함) */
    private final int maxAdvancementLines;

    UpgradeScreens(UpgradeFeature feature) {
        this.feature = feature;
        int max = 1;
        for (UpgradeTrack track : feature.tracks()) {
            for (int level = track.start(); level < track.max(); level++) {
                AdvancementRequirement requirement = track.advancementRequirement(level);
                if (requirement != null && requirement.isSpecific()) max = Math.max(max, requirement.advancements().size());
            }
        }
        this.maxAdvancementLines = Math.min(max, 10);
    }

    // ───────────────────────── 공통 ─────────────────────────

    private static TextComponent text(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** 10칸 막대: 찬 칸 초록, 걸친 칸 노랑, 빈 칸 회색 */
    private static Component bar(double ratio) {
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < BAR_CELLS; i++) {
            boolean full = ratio >= (i + 1) / (double) BAR_CELLS - 1e-9;
            boolean part = !full && ratio > i / (double) BAR_CELLS;
            builder.append(Component.text("■", full ? NamedTextColor.GREEN : part ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        }
        return builder.build();
    }

    private static Component colored(Component component, TextColor color) {
        return component.colorIfAbsent(color).decoration(TextDecoration.ITALIC, false);
    }

    // ───────────────────────── 대화창 ─────────────────────────

    Dialog dialog(Player player, UpgradeTrack track) {
        UpgradeData data = feature.data();
        int level = data.level(player, track);
        boolean maxed = level >= track.max();
        List<Requirement> reqs = maxed ? List.of() : track.requirements(level);
        Map<String, Integer> progress = data.progress(player, track);
        AdvancementRequirement advancements = maxed ? null : track.advancementRequirement(level);
        int doneCount = 0;
        for (Requirement r : reqs) {
            if (progress.getOrDefault(r.key(), 0) >= r.amount()) doneCount++;
        }
        int advNeed = advancements == null ? 0 : advancements.target();
        int advHave = advancements == null ? 0 : Math.min(advNeed, advancements.progress(player));
        boolean complete = !maxed && doneCount == reqs.size() && advHave >= advNeed;
        String action = feature.actionWord(track, level);

        // 본문은 모든 탭·상태에서 줄 수가 같음 → 아래 버튼 위치가 절대 바뀌지 않음
        // (한 줄이 넘치지 않게 짧게 씀. 재료별 자세한 진행은 투입함에서 봄)
        List<Component> lines = new ArrayList<>();

        // 1줄: 단계
        if (maxed) {
            lines.add(text("최대 단계 · 현재 " + track.label(level), NamedTextColor.GREEN));
        } else {
            lines.add(Component.text()
                    .append(text("현재 " + track.label(level) + "  →  다음 " + track.label(level + 1), NamedTextColor.YELLOW))
                    .append(complete ? text("   ✔ " + action + " 준비 완료", NamedTextColor.GREEN) : Component.empty())
                    .build());
        }

        // 2줄: 체력은 하트, 성급함·포화는 지금 상태
        if (feature.isHealth(track)) {
            TextComponent.Builder hearts = Component.text();
            for (int n = 1; n <= track.max(); n++) {
                hearts.append(Component.text("❤", n <= level ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
            }
            lines.add(hearts.build());
        } else if (level >= 1) {
            boolean on = data.enabled(player, track);
            lines.add(text(track.name() + " " + track.label(level) + " · " + (on ? "켜짐" : "꺼짐"), on ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        } else {
            lines.add(text(track.nameObject() + " 아직 해금하지 않았습니다", NamedTextColor.GRAY));
        }

        // 3줄: 재료 전체 진행
        if (maxed) {
            lines.add(text("재료  모두 끝났습니다", NamedTextColor.GREEN));
        } else {
            boolean allDone = doneCount == reqs.size();
            lines.add(Component.text()
                    .append(text("재료  ", NamedTextColor.WHITE))
                    .append(bar(reqs.isEmpty() ? 1 : (double) doneCount / reqs.size()))
                    .append(text("  " + doneCount + " / " + reqs.size() + "종", NamedTextColor.WHITE))
                    .append(allDone ? text("  ✔ 완료", NamedTextColor.GREEN) : text("  · 자세히는 [재료 보기]", NamedTextColor.GRAY))
                    .build());
        }

        // 4줄~: 도전과제 (머리 1줄 + 목록 칸 고정)
        List<Component> advLines = new ArrayList<>();
        if (advancements == null) {
            advLines.add(text("도전과제  이번 단계는 조건 없음", NamedTextColor.GRAY));
        } else if (advancements.isSpecific()) {
            advLines.add(text("필요한 도전과제  " + advHave + " / " + advNeed, advHave >= advNeed ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE));
            for (Advancement advancement : advancements.advancements()) {
                boolean done = player.getAdvancementProgress(advancement).isDone();
                NamedTextColor color = done ? NamedTextColor.GREEN : NamedTextColor.RED;
                advLines.add(Component.text()
                        .append(text(done ? " ✔ " : " ✘ ", color))
                        .append(AdvancementRequirement.title(advancement).color(color).decoration(TextDecoration.ITALIC, false))
                        .build());
            }
        } else {
            boolean ok = advHave >= advNeed;
            advLines.add(Component.text()
                    .append(text("도전과제  ", NamedTextColor.LIGHT_PURPLE))
                    .append(bar(advNeed == 0 ? 1 : (double) advHave / advNeed))
                    .append(text("  " + advHave + " / " + advNeed + "  ", NamedTextColor.WHITE))
                    .append(ok ? text("✔ 달성", NamedTextColor.GREEN) : text((advNeed - advHave) + "개 남음", NamedTextColor.RED))
                    .build());
            advLines.add(text(" 레시피 해금은 세지 않습니다", NamedTextColor.DARK_GRAY));
        }
        while (advLines.size() < 1 + maxAdvancementLines) advLines.add(Component.text(" ")); // 빈 줄도 높이를 차지하게 공백
        lines.addAll(advLines.subList(0, 1 + maxAdvancementLines));

        List<DialogBody> body = new ArrayList<>();
        for (Component line : lines) body.add(DialogBody.plainMessage(line, TEXT_WIDTH));

        DialogBase base = DialogBase.builder(text(track.name() + " 강화", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .externalTitle(text("강화", NamedTextColor.GOLD))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .build();
        DialogType type = DialogType.multiAction(buttons(player, track, level, maxed, complete, doneCount, reqs.size(), advHave, advNeed))
                .columns(3)
                .exitAction(button(track, "close", text("닫기", NamedTextColor.WHITE), null))
                .build();
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    // ───────────────────────── 재료 보기 (스크롤) ─────────────────────────

    /**
     * 재료 미리보기: 재료마다 한 줄 [아이콘 · 이름 · 10칸 막대 · 넣은 수 / 필요 수 · 상태 · 인벤토리],
     * 아래에 도전과제. 본문이 길면 스크롤되고, 맨 아래 버튼 2개([재료 넣기] / [← 돌아가기])는 고정.
     */
    Dialog preview(Player player, UpgradeTrack track) {
        UpgradeData data = feature.data();
        int level = data.level(player, track);
        boolean maxed = level >= track.max();
        List<Requirement> reqs = maxed ? List.of() : track.requirements(level);
        Map<String, Integer> progress = data.progress(player, track);
        ItemStack[] storage = player.getInventory().getStorageContents();
        List<DialogBody> body = new ArrayList<>();

        if (maxed) {
            body.add(DialogBody.plainMessage(text("최대 단계입니다 · 현재 " + track.label(level), NamedTextColor.GREEN), TEXT_WIDTH));
        } else {
            int doneCount = 0;
            for (Requirement r : reqs) if (progress.getOrDefault(r.key(), 0) >= r.amount()) doneCount++;
            body.add(DialogBody.plainMessage(text(track.label(level) + "  →  " + track.label(level + 1)
                    + "   (재료 " + doneCount + " / " + reqs.size() + "종 완료)", NamedTextColor.YELLOW), TEXT_WIDTH));
        }

        for (Requirement r : reqs) {
            int have = Math.min(r.amount(), progress.getOrDefault(r.key(), 0));
            boolean done = have >= r.amount();
            int inInventory = 0;
            for (ItemStack stack : storage) {
                if (stack != null && !stack.getType().isAir() && r.matches(stack.getType())) inInventory += stack.getAmount();
            }
            TextComponent.Builder line = Component.text()
                    .append(colored(r.name(), done ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .append(Component.text("  "))
                    .append(bar((double) have / r.amount()))
                    .append(text("  " + have + " / " + r.amount() + "  ", NamedTextColor.WHITE));
            if (done) {
                line.append(text("✔ 완료", NamedTextColor.GREEN));
            } else {
                int left = r.amount() - have;
                line.append(text(left + "개 남음", NamedTextColor.YELLOW));
                if (inInventory > 0) {
                    line.append(text("  · 인벤토리 " + inInventory, inInventory >= left ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                }
            }
            body.add(DialogBody.item(new ItemStack(r.icon()))
                    .description(DialogBody.plainMessage(line.build(), TEXT_WIDTH - 40))
                    .showDecorations(false)
                    .showTooltip(true)
                    .build());
        }

        AdvancementRequirement advancements = maxed ? null : track.advancementRequirement(level);
        if (advancements != null) {
            int advNeed = advancements.target();
            int advHave = Math.min(advNeed, advancements.progress(player));
            if (advancements.isSpecific()) {
                body.add(DialogBody.plainMessage(text("필요한 도전과제  " + advHave + " / " + advNeed,
                        advHave >= advNeed ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE), TEXT_WIDTH));
                for (Advancement advancement : advancements.advancements()) {
                    boolean done = player.getAdvancementProgress(advancement).isDone();
                    NamedTextColor color = done ? NamedTextColor.GREEN : NamedTextColor.RED;
                    body.add(DialogBody.plainMessage(Component.text()
                            .append(text(done ? " ✔ " : " ✘ ", color))
                            .append(AdvancementRequirement.title(advancement).color(color).decoration(TextDecoration.ITALIC, false))
                            .build(), TEXT_WIDTH));
                }
            } else {
                boolean ok = advHave >= advNeed;
                body.add(DialogBody.item(new ItemStack(Material.KNOWLEDGE_BOOK))
                        .description(DialogBody.plainMessage(Component.text()
                                .append(text("도전과제", NamedTextColor.LIGHT_PURPLE))
                                .append(Component.text("  "))
                                .append(bar(advNeed == 0 ? 1 : (double) advHave / advNeed))
                                .append(text("  " + advHave + " / " + advNeed + "  ", NamedTextColor.WHITE))
                                .append(ok ? text("✔ 달성", NamedTextColor.GREEN) : text((advNeed - advHave) + "개 남음", NamedTextColor.RED))
                                .build(), TEXT_WIDTH - 40))
                        .showDecorations(false)
                        .showTooltip(false)
                        .build());
            }
        }

        DialogBase base = DialogBase.builder(text(track.name() + " 재료", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .externalTitle(text("재료 보기", NamedTextColor.GOLD))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .build();
        ActionButton deposit = ActionButton.builder(text("재료 넣기", maxed ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE))
                .width(150)
                .tooltip(text("투입함을 열어 인벤토리의 재료를 넣습니다", NamedTextColor.GRAY))
                .action(DialogAction.customClick(UpgradeFeature.buttonKey(track, "deposit"), null))
                .build();
        ActionButton back = ActionButton.builder(text("← 돌아가기", NamedTextColor.WHITE))
                .width(150)
                .action(DialogAction.customClick(UpgradeFeature.buttonKey(track, "back"), null))
                .build();
        DialogType type = DialogType.confirmation(deposit, back); // 버튼 2개는 화면 맨 아래에 고정
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    private static ActionButton button(UpgradeTrack tab, String action, Component label, Component tooltip) {
        ActionButton.Builder builder = ActionButton.builder(label)
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(UpgradeFeature.buttonKey(tab, action), null));
        if (tooltip != null) builder.tooltip(tooltip);
        return builder.build();
    }

    private List<ActionButton> buttons(Player player, UpgradeTrack track, int level, boolean maxed, boolean complete,
                                       int doneCount, int reqCount, int advHave, int advNeed) {
        UpgradeData data = feature.data();
        List<ActionButton> buttons = new ArrayList<>();

        // 1줄: 탭
        for (UpgradeTrack other : feature.tracks()) {
            boolean current = other == track;
            int otherLevel = data.level(player, other);
            buttons.add(button(track, "tab_" + other.id(),
                    current ? text("▶ " + other.name() + " ◀", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                            : text(other.name(), NamedTextColor.WHITE),
                    text("현재: " + other.label(otherLevel) + (feature.isReady(player, other) ? " · 준비 완료!" : ""),
                            feature.isReady(player, other) ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
        }

        // 2줄: 재료 보기 / 재료 넣기 / 강화하기
        String action = feature.actionWord(track, level);
        buttons.add(button(track, "preview",
                text("재료 보기", maxed ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA),
                text("재료마다 넣은 양 · 남은 양 · 인벤토리에 있는 양을 봅니다 (스크롤)", NamedTextColor.GRAY)));
        buttons.add(button(track, "deposit",
                text("재료 넣기", maxed ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE),
                text(maxed ? "최대 단계입니다" : "투입함을 열어 인벤토리의 재료를 넣습니다", NamedTextColor.GRAY)));
        Component upgradeLabel;
        Component upgradeTip;
        if (maxed) {
            upgradeLabel = text("최대 단계", NamedTextColor.DARK_GRAY);
            upgradeTip = text("더 강화할 수 없습니다", NamedTextColor.GRAY);
        } else if (complete) {
            upgradeLabel = text(action + "하기", NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
            upgradeTip = text(track.label(level) + " → " + track.label(level + 1), NamedTextColor.GREEN);
        } else {
            upgradeLabel = text(action + "하기", NamedTextColor.GRAY);
            String tip = "조건이 부족합니다 · 재료 " + doneCount + " / " + reqCount
                    + (advNeed > 0 ? " · 도전과제 " + advHave + " / " + advNeed : "");
            upgradeTip = text(tip, NamedTextColor.RED);
        }
        buttons.add(button(track, "upgrade", upgradeLabel, upgradeTip));

        // 3줄: 야간투시 / 성급함 / 포화 켜고 끄기
        if (feature.nightVision() != null) {
            boolean on = feature.nightVision().isEnabled(player);
            buttons.add(button(track, "nv",
                    text("야간투시: " + (on ? "켜짐" : "꺼짐"), on ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                    text("/nv 로도 켜고 끌 수 있습니다", NamedTextColor.GRAY)));
        }
        buttons.add(toggleButton(player, track, "toggle_haste", feature.track(UpgradeFeature.HASTE), NamedTextColor.GOLD));
        buttons.add(toggleButton(player, track, "toggle_saturation", feature.track(UpgradeFeature.SATURATION), NamedTextColor.GOLD));

        // 4줄: 강화 알림 (액션바 상시 표시)
        boolean readyBar = feature.readyBarEnabled(player);
        buttons.add(button(track, "ready_bar",
                text("강화 알림: " + (readyBar ? "켜짐" : "꺼짐"), readyBar ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                text("강화·해금할 수 있거나 재료가 다 모이면 액션바에 계속 표시합니다", NamedTextColor.GRAY)));
        return buttons;
    }

    private ActionButton toggleButton(Player player, UpgradeTrack tab, String action, UpgradeTrack effect, TextColor color) {
        UpgradeData data = feature.data();
        int level = data.level(player, effect);
        if (level < 1) {
            return button(tab, action, text(effect.name() + ": 잠김", NamedTextColor.DARK_GRAY),
                    text(effect.nameObject() + " 해금한 뒤에 켜고 끌 수 있습니다", NamedTextColor.GRAY));
        }
        boolean on = data.enabled(player, effect);
        return button(tab, action, text(effect.name() + ": " + (on ? "켜짐" : "꺼짐"), on ? color : NamedTextColor.GRAY),
                text("입자 없이 지급, 죽어도 유지", NamedTextColor.GRAY));
    }

    // ───────────────────────── 투입함 ─────────────────────────

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

    void renderDeposit(Player player, DepositMenu menu) {
        Inventory inv = menu.getInventory();
        UpgradeTrack track = feature.track(menu.tab());
        UpgradeData data = feature.data();
        int level = data.level(player, track);
        boolean maxed = level >= track.max();
        List<Requirement> reqs = maxed ? List.of() : track.requirements(level);
        Map<String, Integer> progress = data.progress(player, track);

        int bottom = menu.size() - 9;
        for (int i = 0; i < menu.size(); i++) {
            inv.setItem(i, pane(i >= bottom ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
        }

        for (Map.Entry<Integer, Integer> entry : menu.materialSlots().entrySet()) {
            if (entry.getValue() >= reqs.size()) continue;
            Requirement r = reqs.get(entry.getValue());
            int have = Math.min(r.amount(), progress.getOrDefault(r.key(), 0));
            boolean done = have >= r.amount();
            double ratio = (double) have / r.amount();
            int percent = (int) Math.floor(ratio * 100);
            List<Component> lore = List.of(Component.empty(),
                    text("넣은 수량: " + have + " / " + r.amount(), NamedTextColor.WHITE),
                    done ? text("✔ 완료", NamedTextColor.GREEN) : text("남은 수량: " + (r.amount() - have) + "개", NamedTextColor.YELLOW),
                    Component.empty(),
                    text("인벤토리에서 이 재료를 클릭해 넣으세요", NamedTextColor.GRAY),
                    text("클릭: 1개 · 쉬프트+클릭: 인벤토리의 이 재료 전부", NamedTextColor.DARK_GRAY));
            inv.setItem(entry.getKey(), item(r.icon(), done ? 1 : r.amount() - have,
                    colored(r.name(), done ? NamedTextColor.GREEN : NamedTextColor.GOLD), lore, done));
            Material gauge = done ? Material.LIME_STAINED_GLASS_PANE
                    : have > 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            inv.setItem(DepositMenu.gaugeSlot(entry.getKey()), item(gauge, 1,
                    colored(r.name(), NamedTextColor.GOLD).append(text(" " + percent + "%", NamedTextColor.WHITE)), lore, false));
        }

        inv.setItem(menu.infoSlot(), item(Material.BOOK, 1, text("사용법", NamedTextColor.YELLOW), List.of(Component.empty(),
                text("인벤토리의 재료를 클릭하면 들어갑니다", NamedTextColor.WHITE),
                text("클릭: 1개", NamedTextColor.GRAY),
                text("쉬프트+클릭: 인벤토리의 같은 재료 전부 (필요한 만큼만)", NamedTextColor.GRAY),
                text("재료는 나눠서 넣을 수 있습니다", NamedTextColor.GRAY),
                text("넣은 재료는 돌려받을 수 없습니다", NamedTextColor.RED),
                Component.empty(),
                text("강화는 대화창의 [" + feature.actionWord(track, level) + "하기] 버튼으로 합니다", NamedTextColor.YELLOW)), false));
        inv.setItem(menu.backSlot(), item(Material.ARROW, 1, text("돌아가기", NamedTextColor.WHITE),
                List.of(text("강화 화면으로 돌아갑니다", NamedTextColor.GRAY)), false));
    }
}
