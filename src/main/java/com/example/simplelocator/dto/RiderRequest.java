package com.example.simplelocator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiderRequest {
    private double latitude;
    private double longitude;
    /** Search radius in miles; defaults to 2.0 if not supplied. */
    private Double radiusMiles;
}
