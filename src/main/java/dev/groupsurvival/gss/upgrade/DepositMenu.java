package dev.groupsurvival.gss.upgrade;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * 재료 투입함 (상자 창). 재료 줄 + 진행 게이지 줄, 맨 아랫줄에 돌아가기.
 * 재료 9개 이하: 3줄 (재료 / 게이지 / 아랫줄), 10~18개: 5줄 (재료 / 게이지 / 재료 / 게이지 / 아랫줄)
 */
public final class DepositMenu implements InventoryHolder {

    private final UUID owner;
    private final String tab;
    private final int rows;
    /** 재료 칸 → 재료 순번 */
    private final Map<Integer, Integer> materialSlots = new HashMap<>();
    private Inventory inventory;

    public DepositMenu(UUID owner, String tab, int materialCount) {
        this.owner = owner;
        this.tab = tab;
        int materialRows = materialCount <= 9 ? 1 : 2;
        this.rows = materialRows * 2 + 1;
        if (materialRows == 1) {
            place(0, 0, materialCount);
        } else {
            int first = (materialCount + 1) / 2;
            place(0, 0, first);
            place(2, first, materialCount - first);
        }
    }

    private void place(int row, int startIndex, int count) {
        int[] cols = columns(count);
        for (int n = 0; n < cols.length; n++) materialSlots.put(row * 9 + cols[n], startIndex + n);
    }

    /** 한 줄에 재료 count 개를 가운데 정렬 (4개 이하는 한 칸씩 띄움) */
    static int[] columns(int count) {
        return switch (count) {
            case 0 -> new int[0];
            case 1 -> new int[]{4};
            case 2 -> new int[]{3, 5};
            case 3 -> new int[]{2, 4, 6};
            case 4 -> new int[]{1, 3, 5, 7};
            default -> {
                int n = Math.min(9, count);
                int[] cols = new int[n];
                int start = (9 - n) / 2;
                for (int i = 0; i < n; i++) cols[i] = start + i;
                yield cols;
            }
        };
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

    public int size() {
        return rows * 9;
    }

    public Map<Integer, Integer> materialSlots() {
        return materialSlots;
    }

    /** 재료 칸 바로 아래 칸 = 게이지 */
    public static int gaugeSlot(int materialSlot) {
        return materialSlot + 9;
    }

    public int backSlot() {
        return (rows - 1) * 9 + 4;
    }

    public int infoSlot() {
        return (rows - 1) * 9;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
