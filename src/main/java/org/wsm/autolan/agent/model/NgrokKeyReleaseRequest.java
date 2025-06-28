package org.wsm.autolan.agent.model;

import com.google.gson.annotations.SerializedName;

public class NgrokKeyReleaseRequest {
    @SerializedName("clientId")
    private String clientId;
    
    @SerializedName("key")
    private String key;

    public NgrokKeyReleaseRequest(String clientId, String key) {
        this.clientId = clientId;
        this.key = key;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
} 