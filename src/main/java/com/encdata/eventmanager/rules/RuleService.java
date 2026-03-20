package com.encdata.eventmanager.rules;

import com.encdata.eventmanager.queue.HoldingService;
import com.encdata.eventmanager.role.RoleDefinition;
import com.encdata.eventmanager.session.EventSessionService;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

public class RuleService {
    public static void init() {
        // Block Breaking
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (shouldCancel(player, "breakBlocks", pos)) return false;
            return true;
        });

        // Block Interaction (Opening blocks/containers/placing blocks)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            BlockPos pos = hitResult.getBlockPos();
            BlockEntity be = world.getBlockEntity(pos);
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            ItemStack stack = player.getStackInHand(hand);

            // Treat block placement as its own permission path. Otherwise placeBlocks=true
            // still gets blocked by openBlocks/useContainers checks on right click.
            if (stack.getItem() instanceof BlockItem) {
                if (shouldCancel(player, "placeBlocks", pos)) {
                    return ActionResult.FAIL;
                }
                return ActionResult.PASS;
            }
            
            // Check useContainers/openBlocks
            boolean isContainer = be instanceof Inventory || state.getBlock() instanceof net.minecraft.block.EnderChestBlock;
            if (isContainer) {
                if (shouldCancel(player, "useContainers", pos)) return ActionResult.FAIL;
            } else {
                if (shouldCancel(player, "openBlocks", pos)) return ActionResult.FAIL;
            }
            
            return ActionResult.PASS;
        });

        // Item Usage
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (shouldCancel(player, "useItems", player.getBlockPos())) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        // Entity Interaction
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (shouldCancel(player, "interactEntities", player.getBlockPos())) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        // Attack Entity (PvP and generic)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof PlayerEntity) {
                if (shouldCancel(player, "pvpEnabled", player.getBlockPos())) return ActionResult.FAIL;
            } else {
                if (shouldCancel(player, "interactEntities", player.getBlockPos())) return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    public static boolean shouldCancel(PlayerEntity player, String ruleName, BlockPos pos) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;
        
        // Holding containment check
        if (HoldingService.isContained(player.getUuid())) return true;
        
        if (!EventSessionService.isActive()) return false;

        RoleDefinition role = EventSessionService.getPlayerRole(player.getUuid());
        if (role == null) return false;

        return switch (ruleName) {
            case "breakBlocks" -> !role.getRules().breakBlocks();
            case "placeBlocks" -> !role.getRules().placeBlocks();
            case "useItems" -> !role.getRules().useItems();
            case "openBlocks" -> !role.getRules().openBlocks();
            case "useContainers" -> !role.getRules().useContainers();
            case "interactEntities" -> !role.getRules().interactEntities();
            case "pvpEnabled" -> !role.getRules().pvpEnabled();
            case "pickupItems" -> !role.getRules().pickupItems();
            case "dropItems" -> !role.getRules().dropItems();
            default -> false;
        };
    }
}
