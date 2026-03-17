package com.example.simplelocator.dto;

import lombok.Data;

@Data
public class RiderUpdateRequest {
    private String riderId;
    private double latitude;
    private double longitude;
}
