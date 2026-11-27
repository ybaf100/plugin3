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
 * - 대화창: 요약, (체력) 하트 줄, 재료마다 한 줄 [아이콘 · 이름 · 10칸 막대 · 개수 · 상태], 도전과제(✔ 초록 / ✘ 빨강), 버튼
 * - 투입함: 재료 줄(남은 개수) + 게이지 줄(%) + 아랫줄(사용법 · 돌아가기)
 */
final class UpgradeScreens {

    private static final int BAR_CELLS = 10;
    private static final int BUTTON_WIDTH = 100;
    private static final int TEXT_WIDTH = 300;

    private final UpgradeFeature feature;

    UpgradeScreens(UpgradeFeature feature) {
        this.feature = feature;
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

        List<DialogBody> body = new ArrayList<>();

        // 요약
        if (maxed) {
            body.add(DialogBody.plainMessage(text("최대 단계입니다 · 현재 " + track.label(level), NamedTextColor.GREEN), TEXT_WIDTH));
        } else {
            body.add(DialogBody.plainMessage(text("현재 " + track.label(level) + "  →  다음 " + track.label(level + 1), NamedTextColor.YELLOW), TEXT_WIDTH));
            String counts = "재료 " + doneCount + " / " + reqs.size() + (advancements != null ? " · 도전과제 " + advHave + " / " + advNeed : "");
            body.add(DialogBody.plainMessage(text(complete ? counts + " · " + action + " 준비 완료!" : counts,
                    complete ? NamedTextColor.GREEN : NamedTextColor.GRAY), TEXT_WIDTH));
        }

        // 체력: 하트 줄 / 성급함·포화: 지금 상태
        if (feature.isHealth(track)) {
            TextComponent.Builder hearts = Component.text();
            for (int n = 1; n <= track.max(); n++) {
                hearts.append(Component.text("❤", n <= level ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
            }
            body.add(DialogBody.plainMessage(hearts.build(), TEXT_WIDTH));
        } else if (level >= 1) {
            boolean on = data.enabled(player, track);
            body.add(DialogBody.plainMessage(text(track.name() + " " + track.label(level) + " · " + (on ? "켜짐" : "꺼짐"),
                    on ? NamedTextColor.GOLD : NamedTextColor.GRAY), TEXT_WIDTH));
        }

        // 재료: 한 줄에 하나
        for (Requirement r : reqs) {
            int have = Math.min(r.amount(), progress.getOrDefault(r.key(), 0));
            boolean done = have >= r.amount();
            Component line = Component.text()
                    .append(colored(r.name(), done ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                    .append(Component.text("  "))
                    .append(bar((double) have / r.amount()))
                    .append(text("  " + have + " / " + r.amount() + "  ", NamedTextColor.WHITE))
                    .append(done ? text("✔ 완료", NamedTextColor.GREEN) : text((r.amount() - have) + "개 남음", NamedTextColor.YELLOW))
                    .build();
            body.add(DialogBody.item(new ItemStack(r.icon()))
                    .description(DialogBody.plainMessage(line, TEXT_WIDTH - 40))
                    .showDecorations(false)
                    .showTooltip(true)
                    .build());
        }

        // 도전과제
        if (advancements != null) {
            if (advancements.isSpecific()) {
                body.add(DialogBody.plainMessage(text("필요한 도전과제 (" + advHave + " / " + advNeed + ")", NamedTextColor.LIGHT_PURPLE), TEXT_WIDTH));
                for (Advancement advancement : advancements.advancements()) {
                    boolean done = player.getAdvancementProgress(advancement).isDone();
                    NamedTextColor color = done ? NamedTextColor.GREEN : NamedTextColor.RED;
                    body.add(DialogBody.plainMessage(Component.text()
                            .append(text(done ? "✔ " : "✘ ", color))
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
                body.add(DialogBody.plainMessage(text("레시피 해금은 세지 않습니다", NamedTextColor.DARK_GRAY), TEXT_WIDTH));
            }
        }

        if (!maxed) {
            body.add(DialogBody.plainMessage(text("[재료 넣기]에서 인벤토리의 재료를 클릭해 넣습니다 · 나눠서 넣을 수 있고, 넣은 재료는 돌려받을 수 없습니다",
                    NamedTextColor.GRAY), TEXT_WIDTH));
        }

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

        // 2줄: 재료 넣기 / 강화하기 / 클릭음
        String action = feature.actionWord(track, level);
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
        boolean sound = feature.soundEnabled(player);
        buttons.add(button(track, "sound",
                text("클릭음: " + (sound ? "켜짐" : "꺼짐"), sound ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                text("강화 창의 효과음을 켜고 끕니다", NamedTextColor.GRAY)));

        // 3줄: 야간투시 / 성급함 / 포화 켜고 끄기
        if (feature.nightVision() != null) {
            boolean on = feature.nightVision().isEnabled(player);
            buttons.add(button(track, "nv",
                    text("야간투시: " + (on ? "켜짐" : "꺼짐"), on ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                    text("/nv 로도 켜고 끌 수 있습니다", NamedTextColor.GRAY)));
        }
        buttons.add(toggleButton(player, track, "toggle_haste", feature.track(UpgradeFeature.HASTE), NamedTextColor.GOLD));
        buttons.add(toggleButton(player, track, "toggle_saturation", feature.track(UpgradeFeature.SATURATION), NamedTextColor.GOLD));
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
                    text("좌클릭: 그 칸 전부 · 우클릭: 1개", NamedTextColor.DARK_GRAY));
            inv.setItem(entry.getKey(), item(r.icon(), done ? 1 : r.amount() - have,
                    colored(r.name(), done ? NamedTextColor.GREEN : NamedTextColor.GOLD), lore, done));
            Material gauge = done ? Material.LIME_STAINED_GLASS_PANE
                    : have > 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            inv.setItem(DepositMenu.gaugeSlot(entry.getKey()), item(gauge, 1,
                    colored(r.name(), NamedTextColor.GOLD).append(text(" " + percent + "%", NamedTextColor.WHITE)), lore, false));
        }

        inv.setItem(menu.infoSlot(), item(Material.BOOK, 1, text("사용법", NamedTextColor.YELLOW), List.of(Component.empty(),
                text("인벤토리의 재료를 클릭하면 들어갑니다", NamedTextColor.WHITE),
                text("좌클릭: 그 칸 전부 (필요한 만큼만)", NamedTextColor.GRAY),
                text("우클릭: 1개", NamedTextColor.GRAY),
                text("재료는 나눠서 넣을 수 있습니다", NamedTextColor.GRAY),
                text("넣은 재료는 돌려받을 수 없습니다", NamedTextColor.RED),
                Component.empty(),
                text("강화는 대화창의 [" + feature.actionWord(track, level) + "하기] 버튼으로 합니다", NamedTextColor.YELLOW)), false));
        inv.setItem(menu.backSlot(), item(Material.ARROW, 1, text("돌아가기", NamedTextColor.WHITE),
                List.of(text("강화 화면으로 돌아갑니다", NamedTextColor.GRAY)), false));
    }
}
