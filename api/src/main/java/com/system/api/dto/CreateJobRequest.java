
package com.system.api.dto;

import com.system.common.enums.JobType;

public class CreateJobRequest {

    private JobType type;
    private String payload;
    private String idempotencyKey;

    public CreateJobRequest() {
    }

    public JobType getType() {
        return type;
    }

    public void setType(JobType type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
