package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Payload for the /search/debug endpoint — everything the frontend
 * needs to render the three Leaflet visualizations.
 */
@Data
@Builder
public class DebugInfoDto {

    // Search parameters
    private double centerLat;
    private double centerLng;
    private double radiusMeters;
    private double bufferDegrees;

    // SP-GiST: the ST_Expand envelope
    private BboxDto searchBbox;

    // Geohash: 9 cells (precision-4 center + 8 neighbors)
    private List<GeohashCellDto> geohashCells;

    // GiST: simulated R-tree MBR groups
    private List<MbrGroupDto> mbrGroups;

    // All restaurants with per-map metadata
    private List<RestaurantDebugDto> restaurants;

    // Timing from each index strategy
    private long gistMs;
    private long spgistMs;
    private long geohashMs;
}
