package com.encdata.eventmanager.identity;

public class IdentityDefinition {
    private String name;
    private String skinTextureValue;
    private String skinSignature;

    public IdentityDefinition() {
    }

    public IdentityDefinition(String name, String skinTextureValue, String skinSignature) {
        this.name = name;
        this.skinTextureValue = skinTextureValue;
        this.skinSignature = skinSignature;
    }

    public String getName() {
        return name;
    }

    public String getSkinTextureValue() {
        return skinTextureValue;
    }

    public String getSkinSignature() {
        return skinSignature;
    }

    public boolean isValid() {
        return isNonBlank(name) && isNonBlank(skinTextureValue) && isNonBlank(skinSignature);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
