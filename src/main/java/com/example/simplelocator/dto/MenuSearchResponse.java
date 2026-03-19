package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MenuSearchResponse {
    private double centerLat;
    private double centerLng;
    private double radiusMeters;
    private String query;
    private String dietaryFilter;
    private int resultCount;
    private long executionTimeMs;
    /** Human-readable description of the three index types used in the combined query. */
    private String indexesUsed;
    private List<MenuSearchResult> restaurants;
}
