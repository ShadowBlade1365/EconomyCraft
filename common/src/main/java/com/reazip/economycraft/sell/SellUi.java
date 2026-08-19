package com.reazip.economycraft.sell;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.HubUi;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.SellService;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShulkerBoxItem;
import net.minecraft.world.item.component.ItemContainerContents;

public final class SellUi {
    private SellUi() {}

    private static final int DEPOSIT_ROWS = 5;
    private static final int DEPOSIT_SLOTS = DEPOSIT_ROWS * 9;
    private static final int NAV_BALANCE = 0;
    private static final int NAV_FILL = 3;
    private static final int NAV_HELP = 4;
    private static final int NAV_MENU = 5;
    private static final int NAV_CONFIRM = 8;
    private static final int NAV_ROW_SLOTS = 9;
    private static final int NAV_ROW_END = DEPOSIT_SLOTS + NAV_ROW_SLOTS;

    public static void open(ServerPlayer player, EconomyManager manager) {
        MenuUiSupport.openMenu(player, "Sell",
                (id, inv) -> new SellMenu(id, inv, player, manager));
    }

    private static class SellMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final EconomyManager manager;
        private final PriceRegistry prices;

        private final SimpleContainer depositContainer =
                new SimpleContainer(DEPOSIT_SLOTS);

        private final SimpleContainer navContainer =
                new SimpleContainer(9);

        SellMenu(int id, Inventory inv, ServerPlayer viewer, EconomyManager manager) {
            super(MenuType.GENERIC_9x6, id);

            this.viewer = viewer;
            this.manager = manager;
            this.prices = manager.getPrices();

            for (Slot slot : MenuUiSupport.openGridSlots(
                    depositContainer,
                    DEPOSIT_SLOTS,
                    stack -> isSellableForMenu(stack))) {

                this.addSlot(slot);
            }

            for (Slot slot : MenuUiSupport.lockedRowSlots(
                    navContainer,
                    18 + DEPOSIT_ROWS * 18)) {

                this.addSlot(slot);
            }

            for (Slot slot : MenuUiSupport.playerInventorySlots(
                    inv,
                    18 + 6 * 18 + 14)) {

                this.addSlot(slot);
            }

            renderNavRow();
        }

        private record SellPreview(int count, long total) {}

        /*
         * Normal EconomyCraft items continue using the existing
         * sellableResolved() check.
         *
         * Filled shulker boxes are the exception:
         * EconomyCraft normally blocks containers with contents,
         * but we want to calculate those contents ourselves.
         */
        private boolean isSellableForMenu(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }

            if (stack.getItem() instanceof ShulkerBoxItem) {
                if (prices.getUnitSell(stack) == null) {
                    return false;
                }

                if (prices.isSellBlockedByDamage(stack)) {
                    return false;
                }

                return true;
            }

            return SellService.sellableResolved(prices, stack) != null;
        }

        /*
         * Returns the value of all sellable items inside one shulker.
         *
         * Items without a sell price are ignored.
         */
        private long getShulkerContentsValue(ItemStack shulker) {
            if (!(shulker.getItem() instanceof ShulkerBoxItem)) {
                return 0L;
            }

            ItemContainerContents contents =
                    shulker.get(DataComponents.CONTAINER);

            if (contents == null) {
                return 0L;
            }

            long total = 0L;

            for (ItemStack inside : contents.nonEmptyItems()) {
                if (inside.isEmpty()) {
                    continue;
                }

                Long unitSell = prices.getUnitSell(inside);
                if (unitSell == null) {
                    continue;
                }

                Long value = safeMultiply(unitSell, inside.getCount());

                if (value == null) {
                    continue;
                }

                Long newTotal = safeAdd(total, value);

                if (newTotal == null) {
                    continue;
                }

                total = newTotal;
            }

            return total;
        }

        /*
         * Removes the contents from one shulker.
         *
         * The shulker itself is removed separately by performSale().
         */
        private void clearShulkerContents(ItemStack shulker) {
            if (!(shulker.getItem() instanceof ShulkerBoxItem)) {
                return;
            }

            shulker.set(
                    DataComponents.CONTAINER,
                    ItemContainerContents.EMPTY
            );
        }

        private SellPreview previewTotals() {
            int count = 0;
            long total = 0;

            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                ItemStack stack = depositContainer.getItem(i);

                if (stack.isEmpty()) {
                    continue;
                }

                /*
                 * SHULKER BOX
                 */
                if (stack.getItem() instanceof ShulkerBoxItem) {
                    Long shulkerUnitSell = prices.getUnitSell(stack);

                    if (shulkerUnitSell != null) {
                        Long shulkerValue =
                                safeMultiply(shulkerUnitSell, stack.getCount());

                        if (shulkerValue != null) {
                            Long sum = safeAdd(total, shulkerValue);

                            if (sum != null) {
                                total = sum;
                                count += stack.getCount();
                            }
                        }

                        long contentsPerShulker =
                                getShulkerContentsValue(stack);

                        Long contentsValue =
                                safeMultiply(contentsPerShulker, stack.getCount());

                        if (contentsValue != null) {
                            Long sum = safeAdd(total, contentsValue);

                            if (sum != null) {
                                total = sum;
                            }
                        }

                        /*
                         * Count the actual contents too so the
                         * confirmation screen shows the amount.
                         */
                        ItemContainerContents contents =
                                stack.get(DataComponents.CONTAINER);

                        if (contents != null) {
                            for (ItemStack inside : contents.nonEmptyItems()) {
                                if (!inside.isEmpty()
                                        && prices.getUnitSell(inside) != null) {

                                    long amount =
                                            (long) inside.getCount() * stack.getCount();

                                    if (amount <= Integer.MAX_VALUE) {
                                        count += (int) amount;
                                    }
                                }
                            }
                        }
                    }

                    continue;
                }

                /*
                 * NORMAL ITEM
                 */
                if (SellService.sellableResolved(prices, stack) == null) {
                    continue;
                }

                Long unitSell = prices.getUnitSell(stack);

                if (unitSell == null) {
                    continue;
                }

                Long value =
                        safeMultiply(unitSell, stack.getCount());

                if (value == null) {
                    continue;
                }

                Long sum = safeAdd(total, value);

                if (sum == null) {
                    continue;
                }

                total = sum;
                count += stack.getCount();
            }

            return new SellPreview(count, total);
        }

        private void renderNavRow() {
            navContainer.clearContent();

            navContainer.setItem(
                    NAV_BALANCE,
                    MenuUiSupport.createBalanceItem(viewer)
            );

            navContainer.setItem(
                    NAV_HELP,
                    MenuUiSupport.button(
                            Items.BOOK,
                            "How this works",
                            ChatFormatting.YELLOW,
                            MenuUiSupport.hint("Drop items in the slots above."),
                            MenuUiSupport.hint("Only items with a sell price fit."),
                            MenuUiSupport.hint("Filled shulkers sell their contents too."),
                            MenuUiSupport.hint("Nothing is sold until you confirm."),
                            MenuUiSupport.hint("Closing gives everything back.")
                    )
            );

            navContainer.setItem(
                    NAV_FILL,
                    MenuUiSupport.button(
                            Items.HOPPER,
                            "Add everything sellable",
                            ChatFormatting.AQUA,
                            MenuUiSupport.hint(
                                    "Pulls every sellable item from your inventory"
                            )
                    )
            );

            navContainer.setItem(
                    NAV_MENU,
                    MenuUiSupport.button(
                            Items.NETHER_STAR,
                            "Main menu",
                            ChatFormatting.YELLOW
                    )
            );

            SellPreview preview = previewTotals();

            navContainer.setItem(
                    NAV_CONFIRM,
                    MenuUiSupport.confirmButton(
                            "Confirm",
                            MenuUiSupport.hint("Sells the items above"),
                            MenuUiSupport.labeledValue(
                                    "Items",
                                    String.valueOf(preview.count()),
                                    MenuUiSupport.LABEL_PRIMARY_COLOR
                            ),
                            MenuUiSupport.labeledValue(
                                    "Total",
                                    EconomyCraft.formatMoney(preview.total()),
                                    MenuUiSupport.LABEL_PRIMARY_COLOR
                            )
                    )
            );

            MenuUiSupport.fillFooter(navContainer);
        }

        private void fillFromInventory(Player player) {
            Inventory inv = player.getInventory();

            int moved = 0;

            for (int i = 0; i < SellService.MAIN_INVENTORY_SLOTS; i++) {
                ItemStack stack = inv.getItem(i);

                if (stack.isEmpty()) {
                    continue;
                }

                if (!isSellableForMenu(stack)) {
                    continue;
                }

                ItemStack remainder =
                        depositContainer.addItem(stack.copy());

                int placed =
                        stack.getCount() - remainder.getCount();

                if (placed <= 0) {
                    continue;
                }

                stack.shrink(placed);

                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }

                moved += placed;
            }

            if (moved == 0) {
                viewer.sendSystemMessage(
                        MenuUiSupport.line(
                                "Nothing in your inventory can be sold.",
                                ChatFormatting.RED
                        )
                );
            }

            renderNavRow();
        }

        private void performSale(ServerPlayer player) {
            int orderGivenTotal = 0;
            long orderPayoutTotal = 0;

            int serverSoldTotal = 0;
            long serverPayoutTotal = 0;

            int limitBlockedTotal = 0;

            for (int i = 0; i < DEPOSIT_SLOTS; i++) {
                ItemStack stack = depositContainer.getItem(i);

                if (stack.isEmpty()) {
                    continue;
                }

                if (!isSellableForMenu(stack)) {
                    continue;
                }

                Long unitSell = prices.getUnitSell(stack);

                if (unitSell == null) {
                    continue;
                }

                /*
                 * First handle normal EconomyCraft orders.
                 *
                 * If somebody has an order for the shulker itself,
                 * that shulker is sold to the order exactly like before.
                 */
                SellService.SaleSplit split =
                        SellService.sellHandWithRouting(
                                manager,
                                player,
                                stack,
                                stack.getCount(),
                                unitSell
                        );

                orderGivenTotal += split.orderGiven();
                orderPayoutTotal += split.orderPayout();

                /*
                 * Whatever remains is sold to the server.
                 */
                int remainingShulkers = split.serverRemaining();

                if (remainingShulkers > 0) {

                    long shulkerValue =
                            safeMultiply(unitSell, remainingShulkers) != null
                                    ? safeMultiply(unitSell, remainingShulkers)
                                    : 0L;

                    long contentsPerShulker =
                            getShulkerContentsValue(stack);

                    long contentsValue =
                            safeMultiply(
                                    contentsPerShulker,
                                    remainingShulkers
                            ) != null
                                    ? safeMultiply(
                                            contentsPerShulker,
                                            remainingShulkers
                                    )
                                    : 0L;

                    /*
                     * Total server payout includes:
                     *
                     * shulker itself
                     * +
                     * everything inside
                     */
                    long potential =
                            safeAdd(shulkerValue, contentsValue) != null
                                    ? safeAdd(shulkerValue, contentsValue)
                                    : 0L;

                    boolean blocked = false;

                    if (EconomyConfig.get().dailySellLimit > 0) {
                        blocked =
                                manager.tryRecordDailySell(
                                        player.getUUID(),
                                        potential
                                );
                    }

                    if (blocked) {
                        limitBlockedTotal += remainingShulkers;
                    } else {
                        /*
                         * The shulker and its contents are now sold.
                         */
                        if (stack.getItem() instanceof ShulkerBoxItem) {
                            clearShulkerContents(stack);
                        }

                        stack.shrink(remainingShulkers);

                        if (stack.isEmpty()) {
                            depositContainer.setItem(
                                    i,
                                    ItemStack.EMPTY
                            );
                        }

                        serverSoldTotal += remainingShulkers;
                        serverPayoutTotal =
                                safeAdd(
                                        serverPayoutTotal,
                                        potential
                                ) != null
                                        ? safeAdd(
                                                serverPayoutTotal,
                                                potential
                                        )
                                        : serverPayoutTotal;

                        manager.addMoney(
                                player.getUUID(),
                                potential
                        );
                    }
                }

                /*
                 * If the original stack was a normal item rather
                 * than a shulker, the code above also works:
                 *
                 * potential = unit price × quantity
                 */
            }

            int totalSold =
                    orderGivenTotal + serverSoldTotal;

            if (totalSold > 0) {
                long totalPayout =
                        orderPayoutTotal + serverPayoutTotal;

                player.sendSystemMessage(
                        Component.literal(
                                        "Successfully sold "
                                                + totalSold
                                                + " item"
                                                + (totalSold == 1 ? "" : "s")
                                                + " for "
                                                + EconomyCraft.formatMoney(
                                                totalPayout
                                        )
                                                + (orderGivenTotal > 0
                                                ? " ("
                                                + orderGivenTotal
                                                + " to open orders for a better price)"
                                                : "")
                                                + "."
                                )
                                .withStyle(ChatFormatting.GREEN)
                );
            }

            if (limitBlockedTotal > 0) {
                long remaining =
                        manager.getDailySellRemaining(
                                player.getUUID()
                        );

                player.sendSystemMessage(
                        Component.literal(
                                        limitBlockedTotal
                                                + " item"
                                                + (limitBlockedTotal == 1
                                                ? ""
                                                : "s")
                                                + " was not sold: daily sell limit reached"
                                                + (remaining > 0
                                                ? " ("
                                                + EconomyCraft.formatMoney(remaining)
                                                + " left today)."
                                                : ".")
                                )
                                .withStyle(ChatFormatting.RED)
                );
            }

            renderNavRow();
        }

        @Override
        protected boolean onClick(
                int slot,
                int dragType,
                ClickKind kind,
                Player player
        ) {
            if (slot >= DEPOSIT_SLOTS
                    && slot < NAV_ROW_END) {

                if (kind == ClickKind.PICKUP
                        || kind == ClickKind.QUICK_MOVE) {

                    int navSlot =
                            slot - DEPOSIT_SLOTS;

                    if (navSlot == NAV_CONFIRM) {
                        performSale((ServerPlayer) player);

                    } else if (navSlot == NAV_FILL) {
                        fillFromInventory(player);

                    } else if (navSlot == NAV_MENU) {
                        player.closeContainer();
                        HubUi.open((ServerPlayer) player);
                    }
                }

                return true;
            }

            if (slot >= 0 && slot < DEPOSIT_SLOTS) {
                ItemStack carried = this.getCarried();

                if (!carried.isEmpty()
                        && !isSellableForMenu(carried)) {

                    rejectUnsellable(player, carried);
                    return true;
                }
            }

            return false;
        }

        @Override
        protected void afterClick(
                int slot,
                int dragType,
                ClickKind kind,
                Player player
        ) {
            renderNavRow();
        }

        private void rejectUnsellable(
                Player player,
                ItemStack stack
        ) {
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(
                        Component.literal(
                                        stack.getHoverName().getString()
                                                + " cannot be sold."
                                )
                                .withStyle(ChatFormatting.RED)
                );
            }
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            clearContainer(player, depositContainer);
        }

        @Override
        public ItemStack quickMoveStack(
                Player player,
                int index
        ) {
            Slot slot = this.getSlot(index);

            if (slot == null || !slot.hasItem()) {
                return ItemStack.EMPTY;
            }

            ItemStack original = slot.getItem();
            ItemStack copy = original.copy();

            boolean moved;

            if (index < DEPOSIT_SLOTS) {

                moved =
                        this.moveItemStackTo(
                                original,
                                NAV_ROW_END,
                                this.slots.size(),
                                true
                        );

            } else if (index >= NAV_ROW_END) {

                if (!isSellableForMenu(original)) {
                    rejectUnsellable(player, original);
                    return ItemStack.EMPTY;
                }

                moved =
                        this.moveItemStackTo(
                                original,
                                0,
                                DEPOSIT_SLOTS,
                                false
                        );

            } else {
                return ItemStack.EMPTY;
            }

            if (!moved) {
                return ItemStack.EMPTY;
            }

            if (original.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            renderNavRow();

            return copy;
        }

        private static Long safeMultiply(
                long value,
                int count
        ) {
            try {
                return Math.multiplyExact(
                        value,
                        count
                );
            } catch (ArithmeticException ex) {
                return null;
            }
        }

        private static Long safeAdd(
                long a,
                long b
        ) {
            try {
                return Math.addExact(a, b);
            } catch (ArithmeticException ex) {
                return null;
            }
        }
    }
}
