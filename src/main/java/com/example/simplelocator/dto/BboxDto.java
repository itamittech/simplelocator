package com.example.simplelocator.dto;

import lombok.Builder;
import lombok.Data;

/** Axis-aligned bounding box in WGS-84 degrees. */
@Data
@Builder
public class BboxDto {
    private double minLat;
    private double maxLat;
    private double minLng;
    private double maxLng;
}
