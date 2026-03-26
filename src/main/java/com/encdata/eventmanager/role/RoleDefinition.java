package com.encdata.eventmanager.role;

import com.encdata.eventmanager.EventManagerMod;
import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class RoleDefinition {
    private final String name;
    private RoleRules rules;
    private boolean randomizeName;
    private boolean randomizeSkin;
    private boolean bypassEventFlow;
    private Double spawnX;
    private Double spawnY;
    private Double spawnZ;
    private Identifier spawnDimension;
    private Float spawnYaw;
    private Float spawnPitch;
    private List<KitEntry> kit;

    public RoleDefinition(String name) {
        this.name = name;
        this.rules = RoleRules.fullyEnabled();
        this.randomizeName = true;
        this.randomizeSkin = true;
        this.bypassEventFlow = false;
        this.kit = createDefaultKit();
    }

    public String getName() { return name; }
    public RoleRules getRules() { return rules; }
    public void setRules(RoleRules rules) { this.rules = rules; }
    public boolean isRandomizeName() { return randomizeName; }
    public void setRandomizeName(boolean randomizeName) { this.randomizeName = randomizeName; }
    public boolean isRandomizeSkin() { return randomizeSkin; }
    public void setRandomizeSkin(boolean randomizeSkin) { this.randomizeSkin = randomizeSkin; }
    public boolean isBypassEventFlow() { return bypassEventFlow; }
    public void setBypassEventFlow(boolean bypassEventFlow) { this.bypassEventFlow = bypassEventFlow; }

    public void setSpawn(Identifier dimension, double x, double y, double z, float yaw, float pitch) {
        this.spawnDimension = dimension;
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }

    public void clearSpawn() {
        this.spawnDimension = null;
        this.spawnX = null;
        this.spawnY = null;
        this.spawnZ = null;
        this.spawnYaw = null;
        this.spawnPitch = null;
    }

    public boolean hasSpawn() {
        return spawnDimension != null && spawnX != null && spawnY != null && spawnZ != null;
    }

    public Identifier getSpawnDimension() { return spawnDimension; }
    public double getSpawnX() { return spawnX; }
    public double getSpawnY() { return spawnY; }
    public double getSpawnZ() { return spawnZ; }
    public float getSpawnYaw() { return spawnYaw != null ? spawnYaw : 0.0f; }
    public float getSpawnPitch() { return spawnPitch != null ? spawnPitch : 0.0f; }

    public void ensureDefaults() {
        if (rules == null) {
            rules = RoleRules.defaultRules();
        }
        if (kit == null) {
            kit = createDefaultKit();
        }
    }

    public List<KitEntry> getKitEntries() {
        ensureDefaults();
        return new ArrayList<>(kit);
    }

    public void setKitEntries(List<KitEntry> kit) {
        this.kit = kit == null ? createDefaultKit() : new ArrayList<>(kit);
    }

    public static List<KitEntry> createDefaultKit() {
        List<KitEntry> entries = new ArrayList<>();
        entries.add(KitEntry.of(0, new ItemStack(Items.WOODEN_SWORD)));
        entries.add(KitEntry.of(40, new ItemStack(Items.SHIELD)));
        entries.add(KitEntry.of(36, new ItemStack(Items.LEATHER_BOOTS)));
        entries.add(KitEntry.of(37, new ItemStack(Items.LEATHER_LEGGINGS)));
        entries.add(KitEntry.of(38, new ItemStack(Items.LEATHER_CHESTPLATE)));
        entries.add(KitEntry.of(39, new ItemStack(Items.LEATHER_HELMET)));
        return entries;
    }

    public static ItemStack toItemStack(KitEntry entry) {
        if (entry == null) {
            return ItemStack.EMPTY;
        }

        if (entry.stackNbt != null && !entry.stackNbt.isBlank()) {
            try {
                NbtElement nbt = StringNbtReader.readCompound(entry.stackNbt);
                var parsed = ItemStack.CODEC.parse(getRegistryLookup().getOps(NbtOps.INSTANCE), nbt);
                if (parsed.result().isPresent()) {
                    return parsed.result().get();
                }
            } catch (CommandSyntaxException ignored) {
                EventManagerMod.logWarn("Failed to parse kit stack NBT for slot {}", entry.slot);
            }
        }

        if (entry.stackData != null) {
            var parsed = ItemStack.CODEC.parse(getRegistryLookup().getOps(JsonOps.INSTANCE), entry.stackData);
            if (parsed.result().isPresent()) {
                return parsed.result().get();
            }
        }

        Identifier id = Identifier.tryParse(entry.itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }

        var item = Registries.ITEM.get(id);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }

        return new ItemStack(item, Math.max(1, entry.count));
    }

    public static final class KitEntry {
        private int slot;
        private String itemId;
        private int count;
        private String stackNbt;
        private JsonElement stackData;

        private KitEntry() {
        }

        private KitEntry(int slot, String itemId, int count, String stackNbt, JsonElement stackData) {
            this.slot = slot;
            this.itemId = itemId;
            this.count = count;
            this.stackNbt = stackNbt;
            this.stackData = stackData;
        }

        public static KitEntry of(int slot, ItemStack stack) {
            NbtElement stackNbt = ItemStack.CODEC.encodeStart(getRegistryLookup().getOps(NbtOps.INSTANCE), stack.copy())
                    .result()
                    .orElse(null);
            return new KitEntry(
                    slot,
                    Registries.ITEM.getId(stack.getItem()).toString(),
                    stack.getCount(),
                    stackNbt != null ? stackNbt.toString() : null,
                    null
            );
        }

        public int slot() {
            return slot;
        }

        public String itemId() {
            return itemId;
        }

        public int count() {
            return count;
        }

        public JsonElement stackData() {
            return stackData;
        }
    }

    private static RegistryWrapper.WrapperLookup getRegistryLookup() {
        if (EventManagerMod.getServerInstance() != null) {
            return EventManagerMod.getServerInstance().getRegistryManager();
        }
        return DynamicRegistryManager.EMPTY;
    }
}
