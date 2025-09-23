package com.clusterpulse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterRegistrationRequest {

    @NotBlank(message = "Display name is required")
    private String displayName;

    @NotBlank(message = "Connection URI is required")
    private String connectionUri;

    @NotBlank(message = "Environment is required")
    @Pattern(regexp = "PRODUCTION|STAGING|DEVELOPMENT", message = "Environment must be PRODUCTION, STAGING, or DEVELOPMENT")
    private String environment;

    @Builder.Default
    private boolean remediationEnabled = false;

    private Map<String, String> tags;
}
