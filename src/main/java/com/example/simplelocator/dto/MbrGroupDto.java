package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

/**
 * A simulated R-tree MBR group — restaurants clustered into NW/NE/SW/SE
 * quadrants relative to the data centroid.  Shown as colored rectangles
 * on the GiST visualization map.
 */
@Data
@Builder
public class MbrGroupDto {
    private String name;             // e.g. "North-West Group"
    private String color;            // hex, e.g. "#3b82f6"
    private BboxDto bbox;
    private boolean intersectsSearch; // did this MBR survive the R-tree prune?
    private int pointCount;
}
