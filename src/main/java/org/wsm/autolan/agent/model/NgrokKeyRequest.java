package org.wsm.autolan.agent.model;

import com.google.gson.annotations.SerializedName;

public class NgrokKeyRequest {
    @SerializedName("clientId")
    private String clientId;

    public NgrokKeyRequest(String clientId) {
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
} 