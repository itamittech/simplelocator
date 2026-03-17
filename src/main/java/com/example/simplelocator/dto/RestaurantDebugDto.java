package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Restaurant with extra flags needed to colour-code markers
 * on each of the three visualization maps.
 */
@Data
@Builder
public class RestaurantDebugDto {
    private Long   id;
    private String name;
    private String cuisine;
    private double latitude;
    private double longitude;
    private double distanceMiles;
    private String geohash;

    // ── GiST (R-tree) map ───────────────────────────────────────
    /** Which simulated MBR group this restaurant belongs to. */
    private String mbrGroup;
    private String mbrGroupColor;

    // ── SP-GiST (Quadtree) map ──────────────────────────────────
    /** True if inside the ST_Expand bounding box. */
    private boolean withinBbox;

    // ── All maps ────────────────────────────────────────────────
    /** True if within 5-mile search radius (in final results). */
    private boolean withinRadius;

    // ── Geohash map ─────────────────────────────────────────────
    /** The 4-char geohash prefix of the cell this restaurant falls in (null if not in 9 cells). */
    private String geohashCell;
    /** True if the matched cell is the center cell (vs a neighbor). */
    private boolean geohashCellIsCenter;
}
