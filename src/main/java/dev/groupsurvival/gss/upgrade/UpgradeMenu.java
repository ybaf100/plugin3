package dev.groupsurvival.gss.upgrade;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** 강화 창 (상자 6줄). 어떤 탭을 보고 있는지 기억. */
public final class UpgradeMenu implements InventoryHolder {

    // 슬롯 배치 (디자인 시안과 같음)
    public static final int TAB_HEALTH = 0;
    public static final int TAB_HASTE = 9;
    public static final int INFO = 46;
    public static final int ADVANCEMENTS = 47;
    public static final int UPGRADE = 49;
    public static final int TOGGLE_NIGHT_VISION = 51;
    public static final int TOGGLE_HASTE = 52;
    public static final int CLOSE = 53;

    private final UUID owner;
    private final String tab;
    private Inventory inventory;

    public UpgradeMenu(UUID owner, String tab) {
        this.owner = owner;
        this.tab = tab;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public UUID owner() {
        return owner;
    }

    public String tab() {
        return tab;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** 재료 줄 위치 (1~8열). 재료 개수에 따라 가운데 정렬. */
    public static int[] materialColumns(int count) {
        return switch (count) {
            case 1 -> new int[]{5};
            case 2 -> new int[]{4, 6};
            case 3 -> new int[]{3, 5, 7};
            case 4 -> new int[]{2, 4, 6, 8};
            default -> {
                int[] cols = new int[Math.min(count, 8)];
                for (int i = 0; i < cols.length; i++) cols[i] = i + 1;
                yield cols;
            }
        };
    }
}
