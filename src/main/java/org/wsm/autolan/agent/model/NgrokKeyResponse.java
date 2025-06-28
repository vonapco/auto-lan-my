package org.wsm.autolan.agent.model;

import com.google.gson.annotations.SerializedName;

public class NgrokKeyResponse {
    @SerializedName("ngrokKey")
    private String ngrokKey;

    public NgrokKeyResponse() {
    }

    public NgrokKeyResponse(String ngrokKey) {
        this.ngrokKey = ngrokKey;
    }

    public String getNgrokKey() {
        return ngrokKey;
    }

    public void setNgrokKey(String ngrokKey) {
        this.ngrokKey = ngrokKey;
    }
} 