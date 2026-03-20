package com.encdata.eventmanager.gui;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.role.RoleDefinition;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class RoleKitGui {
    private static final int ROWS = 6;
    private static final int SIZE = 54;
    private static final int EDITABLE_SLOT_COUNT = 41;
    private static final int BACK_SLOT = 49;

    private RoleKitGui() {
    }

    public static void open(ServerPlayerEntity player, RoleDefinition role) {
        SimpleInventory inventory = new SimpleInventory(SIZE);
        loadKit(inventory, role);
        fillLockedSlots(inventory);

        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("Change Kit: " + role.getName());
            }

            @Nullable
            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity screenPlayer) {
                return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, ROWS) {
                    @Override
                    public boolean canUse(PlayerEntity player) {
                        return true;
                    }

                    @Override
                    public boolean canInsertIntoSlot(Slot slot) {
                        return slot.inventory != inventory || slot.getIndex() < EDITABLE_SLOT_COUNT;
                    }

                    @Override
                    public ItemStack quickMove(PlayerEntity player, int index) {
                        Slot slot = this.slots.get(index);
                        if (!slot.hasStack()) {
                            return ItemStack.EMPTY;
                        }

                        ItemStack original = slot.getStack();
                        ItemStack moved = original.copy();
                        if (index < SIZE) {
                            if (!this.insertItem(original, SIZE, this.slots.size(), true)) {
                                return ItemStack.EMPTY;
                            }
                        } else if (!this.insertItem(original, 0, EDITABLE_SLOT_COUNT, false)) {
                            return ItemStack.EMPTY;
                        }

                        if (original.isEmpty()) {
                            slot.setStack(ItemStack.EMPTY);
                        } else {
                            slot.markDirty();
                        }
                        return moved;
                    }

                    @Override
                    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
                        if (slotIndex >= 0 && slotIndex < SIZE && slotIndex >= EDITABLE_SLOT_COUNT) {
                            if (slotIndex == BACK_SLOT && actionType == SlotActionType.PICKUP) {
                                saveKit(inventory, role);
                                EventManagerMod.getInstance().saveData();
                                RoleConfigGui.open((ServerPlayerEntity) player, role);
                            }
                            return;
                        }

                        super.onSlotClick(slotIndex, button, actionType, player);
                    }

                    @Override
                    public void onClosed(PlayerEntity player) {
                        super.onClosed(player);
                        saveKit(inventory, role);
                        EventManagerMod.getInstance().saveData();
                    }
                };
            }
        });
    }

    private static void loadKit(SimpleInventory inventory, RoleDefinition role) {
        inventory.clear();
        for (RoleDefinition.KitEntry entry : role.getKitEntries()) {
            if (entry.slot() < 0 || entry.slot() >= EDITABLE_SLOT_COUNT) {
                continue;
            }

            ItemStack stack = RoleDefinition.toItemStack(entry);
            if (!stack.isEmpty()) {
                inventory.setStack(entry.slot(), stack);
            }
        }
    }

    private static void saveKit(SimpleInventory inventory, RoleDefinition role) {
        List<RoleDefinition.KitEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < EDITABLE_SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                entries.add(RoleDefinition.KitEntry.of(slot, stack.copy()));
            }
        }
        role.setKitEntries(entries);
    }

    private static void fillLockedSlots(SimpleInventory inventory) {
        for (int slot = EDITABLE_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setStack(slot, actionItem(Items.GRAY_STAINED_GLASS_PANE, "Unused"));
        }
        inventory.setStack(BACK_SLOT, actionItem(Items.CHEST, "Back To Role Settings"));
    }

    private static ItemStack actionItem(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
