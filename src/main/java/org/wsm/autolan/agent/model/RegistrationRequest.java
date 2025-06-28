package org.wsm.autolan.agent.model;

public class RegistrationRequest {
    private String name;
    @com.google.gson.annotations.SerializedName("industrial_client_id")
    private String industrialClientId;

    public RegistrationRequest(String name, String industrialClientId) {
        this.name = name;
        this.industrialClientId = industrialClientId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIndustrialClientId() {
        return industrialClientId;
    }

    public void setIndustrialClientId(String industrialClientId) {
        this.industrialClientId = industrialClientId;
    }
} 