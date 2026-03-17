package com.example.simplelocator.service;

import ch.hsr.geohash.BoundingBox;
import ch.hsr.geohash.GeoHash;
import com.example.simplelocator.dto.BboxDto;
import com.example.simplelocator.dto.GeohashCellDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Geohash helper: prefix search cells and their bounding boxes.
 *
 * Precision guide (approx cell size at mid-latitudes):
 *   4 → ~39 km × 20 km  ← search precision (center + 8 neighbors)
 *   9 → ~4 m  × 5 m     ← stored precision
 */
@Service
public class GeohashService {

    private static final int SEARCH_PRECISION = 4;

    /** Returns 9 LIKE-pattern strings (center + 8 neighbors), each ending with %. */
    public List<String> getPrefixesForSearch(double lat, double lng) {
        GeoHash center = GeoHash.withCharacterPrecision(lat, lng, SEARCH_PRECISION);
        List<String> prefixes = new ArrayList<>();
        prefixes.add(center.toBase32() + "%");
        for (GeoHash n : center.getAdjacent()) prefixes.add(n.toBase32() + "%");
        return prefixes;
    }

    /** Returns the 9 cells with their bounding boxes — used by the debug/viz endpoint. */
    public List<GeohashCellDto> getCellBboxes(double lat, double lng) {
        GeoHash center = GeoHash.withCharacterPrecision(lat, lng, SEARCH_PRECISION);
        List<GeohashCellDto> cells = new ArrayList<>();
        cells.add(toDto(center, true));
        for (GeoHash n : center.getAdjacent()) cells.add(toDto(n, false));
        return cells;
    }

    /** Encodes a lat/lng to precision-9 geohash for storage. */
    public String encode(double lat, double lng) {
        return GeoHash.withCharacterPrecision(lat, lng, 9).toBase32();
    }

    private GeohashCellDto toDto(GeoHash gh, boolean isCenter) {
        BoundingBox bb = gh.getBoundingBox();
        return GeohashCellDto.builder()
                .prefix(gh.toBase32())
                .center(isCenter)
                .bbox(BboxDto.builder()
                        .minLat(bb.getSouthLatitude())
                        .maxLat(bb.getNorthLatitude())
                        .minLng(bb.getWestLongitude())
                        .maxLng(bb.getEastLongitude())
                        .build())
                .build();
    }
}
