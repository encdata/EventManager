package com.encdata.eventmanager.gui;

import com.encdata.eventmanager.EventManagerMod;
import com.encdata.eventmanager.role.RoleDefinition;
import com.encdata.eventmanager.role.RoleRules;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

public class RoleConfigGui {
    public static void open(ServerPlayerEntity player, RoleDefinition role) {
        SimpleInventory inventory = new SimpleInventory(27);
        updateInventory(inventory, role);

        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.literal("Configure Role: " + role.getName());
            }

            @Nullable
            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
                return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, inventory, 3) {
                    @Override
                    public void onContentChanged(net.minecraft.inventory.Inventory inventory) {
                        // Do not call super to prevent sync issues
                    }

                    @Override
                    public ItemStack quickMove(PlayerEntity player, int index) {
                        return ItemStack.EMPTY;
                    }

                    @Override
                    public boolean canUse(PlayerEntity player) {
                        return true;
                    }

                    @Override
                    public void onSlotClick(int slotIndex, int button, net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player) {
                        if (slotIndex >= 0 && slotIndex < 27) {
                            if (actionType == net.minecraft.screen.slot.SlotActionType.PICKUP || actionType == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE) {
                                if (handleSlotClick((ServerPlayerEntity) player, slotIndex, role)) {
                                    return;
                                }
                                updateInventory(inventory, role);
                                EventManagerMod.getInstance().saveData();
                            }
                        }
                    }
                };
            }
        });
    }

    private static void updateInventory(SimpleInventory inventory, RoleDefinition role) {
        RoleRules rules = role.getRules();
        inventory.setStack(0, createIcon(Items.DIAMOND_PICKAXE, "Break Blocks", rules.breakBlocks()));
        inventory.setStack(1, createIcon(Items.GRASS_BLOCK, "Place Blocks", rules.placeBlocks()));
        inventory.setStack(2, createIcon(Items.APPLE, "Use Items", rules.useItems()));
        inventory.setStack(3, createIcon(Items.CHEST, "Open Blocks", rules.openBlocks()));
        inventory.setStack(4, createIcon(Items.BARREL, "Use Containers", rules.useContainers()));
        inventory.setStack(5, createIcon(Items.VILLAGER_SPAWN_EGG, "Interact Entities", rules.interactEntities()));
        inventory.setStack(6, createIcon(Items.IRON_SWORD, "PvP Enabled", rules.pvpEnabled()));
        inventory.setStack(7, createIcon(Items.HOPPER, "Pickup Items", rules.pickupItems()));
        inventory.setStack(8, createIcon(Items.DROPPER, "Drop Items", rules.dropItems()));
        inventory.setStack(9, createIcon(Items.NAME_TAG, "Randomize Name", role.isRandomizeName()));
        inventory.setStack(10, createIcon(Items.PLAYER_HEAD, "Randomize Skin", role.isRandomizeSkin()));
        inventory.setStack(11, createIcon(Items.ENDER_PEARL, "Bypass Event Flow", role.isBypassEventFlow()));
        inventory.setStack(12, actionIcon(Items.COMPASS, role.hasSpawn() ? "Spawn Is Set" : "Spawn Not Set"));
        inventory.setStack(13, actionIcon(Items.CHEST, "Change Kit"));
    }

    private static ItemStack createIcon(net.minecraft.item.Item item, String name, boolean enabled) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name + ": " + (enabled ? "Enabled" : "Disabled")));
        return stack;
    }

    private static ItemStack actionIcon(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    private static boolean handleSlotClick(ServerPlayerEntity player, int slot, RoleDefinition role) {
        RoleRules r = role.getRules();
        switch (slot) {
            case 0 -> role.setRules(new RoleRules(!r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 1 -> role.setRules(new RoleRules(r.breakBlocks(), !r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 2 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), !r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 3 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), !r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 4 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), !r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 5 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), !r.interactEntities(), r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 6 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), !r.pvpEnabled(), r.pickupItems(), r.dropItems()));
            case 7 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), !r.pickupItems(), r.dropItems()));
            case 8 -> role.setRules(new RoleRules(r.breakBlocks(), r.placeBlocks(), r.useItems(), r.openBlocks(), r.useContainers(), r.interactEntities(), r.pvpEnabled(), r.pickupItems(), !r.dropItems()));
            case 9 -> role.setRandomizeName(!role.isRandomizeName());
            case 10 -> role.setRandomizeSkin(!role.isRandomizeSkin());
            case 11 -> role.setBypassEventFlow(!role.isBypassEventFlow());
            case 13 -> {
                RoleKitGui.open(player, role);
                return true;
            }
            default -> {
            }
        }
        return false;
    }
}
