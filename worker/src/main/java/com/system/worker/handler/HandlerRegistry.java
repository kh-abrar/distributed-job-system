package com.system.worker.handler;

import com.system.common.enums.JobType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One handler per job type, checked when the worker starts. A missing handler
 * would make every job of that type fail on every attempt and end DEAD, so the
 * worker refuses to start instead.
 */
@Component
public class HandlerRegistry {

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public HandlerRegistry(List<JobHandler> handlers) {
        for (JobHandler handler : handlers) {
            if (this.handlers.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("Two handlers for job type " + handler.type());
            }
        }
        Set<JobType> missing = EnumSet.allOf(JobType.class);
        missing.removeAll(this.handlers.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No handler for job types " + missing);
        }
    }

    public JobHandler forType(JobType type) {
        return handlers.get(type);
    }
}
