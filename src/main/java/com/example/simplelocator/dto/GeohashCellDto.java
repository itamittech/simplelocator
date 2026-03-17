package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

/** One geohash cell used in the prefix search (center or one of 8 neighbors). */
@Data
@Builder
public class GeohashCellDto {
    private String prefix;    // 4-char base-32 prefix, e.g. "dr5q"
    private BboxDto bbox;
    private boolean center;   // true only for the center cell
}
