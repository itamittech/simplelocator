package com.example.simplelocator.service;

import com.example.simplelocator.dto.ZoneDto;
import com.example.simplelocator.entity.DeliveryZone;
import com.example.simplelocator.repository.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeofenceService {

    private final DeliveryZoneRepository deliveryZoneRepository;

    /**
     * Returns the name of the zone containing (lat, lng), or empty if outside all zones.
     * Delegates the ST_Within check to PostGIS.
     */
    public Optional<String> getZoneName(double lat, double lng) {
        return deliveryZoneRepository.findZoneContaining(lat, lng)
                .map(DeliveryZone::getName);
    }

    /**
     * Returns all zones as simple bounding-box DTOs for the frontend map renderer.
     * Uses JTS Envelope to extract min/max lat-lng from the Polygon.
     */
    public List<ZoneDto> getAllZones() {
        return deliveryZoneRepository.findAllZones().stream()
                .map(dz -> {
                    Envelope env = dz.getBoundary().getEnvelopeInternal();
                    return ZoneDto.builder()
                            .id(dz.getId())
                            .name(dz.getName())
                            .color(dz.getColor())
                            .minLng(env.getMinX())
                            .maxLng(env.getMaxX())
                            .minLat(env.getMinY())
                            .maxLat(env.getMaxY())
                            .build();
                })
                .toList();
    }
}
