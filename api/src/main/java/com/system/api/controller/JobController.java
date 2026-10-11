
package com.system.api.controller;

import com.system.api.dto.CreateJobRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    @PostMapping
    public String createJob(@RequestBody CreateJobRequest request) {
        return "Received job of type: " + request.getType();
    }
}
