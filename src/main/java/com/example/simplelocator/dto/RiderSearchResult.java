package com.example.simplelocator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderSearchResult {
    private double searchLat;
    private double searchLng;
    private double radiusMiles;
    private long   executionTimeMs;
    private int    count;
    private List<NearbyRestaurant> restaurants;
}
