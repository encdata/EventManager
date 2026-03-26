package com.encdata.eventmanager.role;

public record RoleRules(
    boolean breakBlocks,
    boolean placeBlocks,
    boolean useItems,
    boolean openBlocks,
    boolean useContainers,
    boolean interactEntities,
    boolean pvpEnabled,
    boolean pickupItems,
    boolean dropItems,
    boolean deathImmunity
) {
    public static RoleRules defaultRules() {
        return new RoleRules(false, false, false, false, false, false, false, false, false, false);
    }

    public static RoleRules fullyEnabled() {
        return new RoleRules(true, true, true, true, true, true, true, true, true, false);
    }
}
