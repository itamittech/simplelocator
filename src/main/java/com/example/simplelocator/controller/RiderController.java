package com.example.simplelocator.controller;

import com.example.simplelocator.dto.NearbyRestaurant;
import com.example.simplelocator.dto.RiderUpdateRequest;
import com.example.simplelocator.dto.RiderUpdateResponse;
import com.example.simplelocator.dto.ZoneDto;
import com.example.simplelocator.service.EventStreamService;
import com.example.simplelocator.service.GeofenceService;
import com.example.simplelocator.service.RedisGeoService;
import com.example.simplelocator.service.RiderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Rider-mode API — Redis GEOSEARCH + PostGIS geofencing + SSE event stream.
 *
 * POST /api/rider/update      — process rider position, trigger events
 * GET  /api/rider/stream      — SSE subscription (text/event-stream)
 * GET  /api/rider/zones       — delivery zone bounding boxes for map
 * GET  /api/rider/restaurants — all restaurants for map initialisation
 */
@RestController
@RequestMapping("/api/rider")
@RequiredArgsConstructor
public class RiderController {

    private final RiderService       riderService;
    private final RedisGeoService    redisGeoService;
    private final GeofenceService    geofenceService;
    private final EventStreamService eventStreamService;

    /**
     * Process a rider position update.
     * Runs GEOSEARCH, checks geofence, fires SSE events, returns full state.
     */
    @PostMapping("/update")
    public ResponseEntity<RiderUpdateResponse> update(@RequestBody RiderUpdateRequest req) {
        String riderId = (req.getRiderId() != null && !req.getRiderId().isBlank())
                ? req.getRiderId() : "rider-1";
        return ResponseEntity.ok(
                riderService.processUpdate(riderId, req.getLatitude(), req.getLongitude()));
    }

    /**
     * Open an SSE stream.  The browser keeps this connection open and receives
     * LOCATION_UPDATE, GEOFENCE_ENTER/EXIT, RIDER_ASSIGNED/UNASSIGNED events.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventStreamService.subscribe();
    }

    /**
     * Delivery zones as bbox DTOs — drawn as coloured rectangles on the Leaflet map.
     */
    @GetMapping("/zones")
    public ResponseEntity<List<ZoneDto>> zones() {
        return ResponseEntity.ok(geofenceService.getAllZones());
    }

    /**
     * All restaurants from the Redis in-memory cache — used once on map init.
     */
    @GetMapping("/restaurants")
    public ResponseEntity<List<NearbyRestaurant>> allRestaurants() {
        return ResponseEntity.ok(redisGeoService.getAllRestaurants());
    }
}
