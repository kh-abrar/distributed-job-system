
package com.system.api.service;

import com.system.api.entity.JobEntity;

public record JobCreationResult(
        JobEntity job,
        boolean created) {
}
