package com.clusterpulse.controller;

import com.clusterpulse.dto.ClusterRegistrationRequest;
import com.clusterpulse.dto.ClusterStatusResponse;
import com.clusterpulse.model.Cluster;
import com.clusterpulse.repository.HealthSnapshotRepository;
import com.clusterpulse.service.ClusterRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClusterController.class)
class ClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ClusterRegistryService clusterRegistryService;

    @MockBean
    private HealthSnapshotRepository healthSnapshotRepository;

    @Test
    @DisplayName("POST /api/clusters should register a cluster and return 201")
    void shouldRegisterCluster() throws Exception {
        ClusterRegistrationRequest request = ClusterRegistrationRequest.builder()
                .displayName("Prod RS-1")
                .connectionUri("mongodb://prod:27017")
                .environment("PRODUCTION")
                .build();

        Cluster saved = Cluster.builder()
                .id("abc123")
                .displayName("Prod RS-1")
                .connectionUri("mongodb://prod:27017")
                .environment("PRODUCTION")
                .status(Cluster.ClusterStatus.UNKNOWN)
                .build();

        when(clusterRegistryService.registerCluster(any())).thenReturn(saved);

        mockMvc.perform(post("/api/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.displayName").value("Prod RS-1"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    @DisplayName("GET /api/clusters should return list of clusters")
    void shouldListClusters() throws Exception {
        ClusterStatusResponse response = ClusterStatusResponse.builder()
                .id("cluster-1")
                .displayName("Test Cluster")
                .environment("STAGING")
                .status(Cluster.ClusterStatus.HEALTHY)
                .build();

        when(clusterRegistryService.listAllClusters()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/clusters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cluster-1"))
                .andExpect(jsonPath("$[0].status").value("HEALTHY"));
    }

    @Test
    @DisplayName("POST /api/clusters should return 400 for missing required fields")
    void shouldRejectInvalidRequest() throws Exception {
        // Missing displayName and connectionUri
        String invalidJson = "{ \"environment\": \"PRODUCTION\" }";

        mockMvc.perform(post("/api/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/clusters/{id} should return 204")
    void shouldDeleteCluster() throws Exception {
        mockMvc.perform(delete("/api/clusters/cluster-1"))
                .andExpect(status().isNoContent());
    }
}
