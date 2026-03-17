package com.example.simplelocator.service;

import com.example.simplelocator.dto.NearbyRestaurant;
import com.example.simplelocator.dto.RiderUpdateResponse;
import com.example.simplelocator.dto.RiderSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a rider's position update:
 *
 *  1. Redis GEOSEARCH → list of nearby restaurants
 *  2. PostGIS ST_Within → current delivery zone
 *  3. Zone change detection → GEOFENCE_ENTER / GEOFENCE_EXIT SSE events
 *  4. Assignment: nearest restaurant within ASSIGNMENT_RADIUS_MILES
 *     → RIDER_ASSIGNED / RIDER_UNASSIGNED SSE events
 *  5. LOCATION_UPDATE broadcast every tick
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiderService {

    private static final double ASSIGNMENT_RADIUS_MILES = 0.5;

    private final RedisGeoService    redisGeoService;
    private final GeofenceService    geofenceService;
    private final EventStreamService eventStreamService;
    private final ObjectMapper       objectMapper;

    /** Previous zone per riderId (null = outside all zones). */
    private final ConcurrentHashMap<String, String> riderZoneMap       = new ConcurrentHashMap<>();
    /** Previous assignment per riderId (null = unassigned). */
    private final ConcurrentHashMap<String, Long>   riderAssignmentMap = new ConcurrentHashMap<>();

    public RiderUpdateResponse processUpdate(String riderId, double lat, double lng) {
        long start = System.currentTimeMillis();

        // 1 ── Redis GEOSEARCH ─────────────────────────────────────────────────
        RiderSearchResult geoResult = redisGeoService.findNearby(lat, lng, 2.0);
        List<NearbyRestaurant> nearby = geoResult.getRestaurants();

        // 2 ── PostGIS geofence check ──────────────────────────────────────────
        Optional<String> zoneOpt = geofenceService.getZoneName(lat, lng);
        String currentZone = zoneOpt.orElse(null);

        // 3 ── Zone transition events ──────────────────────────────────────────
        String previousZone = riderZoneMap.get(riderId);
        if (currentZone != null && !currentZone.equals(previousZone)) {
            publishEvent(EventStreamService.EventType.GEOFENCE_ENTER,
                    buildZonePayload(riderId, lat, lng, currentZone));
        } else if (currentZone == null && previousZone != null) {
            publishEvent(EventStreamService.EventType.GEOFENCE_EXIT,
                    buildZonePayload(riderId, lat, lng, previousZone));
        }
        riderZoneMap.put(riderId, currentZone);

        // 4 ── Assignment: nearest restaurant within 0.5 mi ───────────────────
        Long   assignedId   = null;
        String assignedName = null;
        Long   prevAssigned = riderAssignmentMap.get(riderId);

        if (!nearby.isEmpty() && nearby.get(0).getDistanceMiles() <= ASSIGNMENT_RADIUS_MILES) {
            NearbyRestaurant closest = nearby.get(0);
            assignedId   = closest.getId();
            assignedName = closest.getName();

            if (!closest.getId().equals(prevAssigned)) {
                publishEvent(EventStreamService.EventType.RIDER_ASSIGNED,
                        buildAssignPayload(riderId, lat, lng, closest));
                riderAssignmentMap.put(riderId, closest.getId());
            }
        } else if (prevAssigned != null) {
            publishEvent(EventStreamService.EventType.RIDER_UNASSIGNED,
                    buildUnassignPayload(riderId, lat, lng, prevAssigned));
            riderAssignmentMap.remove(riderId);
        }

        // 5 ── Always broadcast a LOCATION_UPDATE ─────────────────────────────
        publishEvent(EventStreamService.EventType.LOCATION_UPDATE,
                buildLocationPayload(riderId, lat, lng, currentZone, assignedId, assignedName, nearby.size()));

        long elapsed = System.currentTimeMillis() - start;
        return RiderUpdateResponse.builder()
                .riderId(riderId)
                .latitude(lat).longitude(lng)
                .currentZone(currentZone)
                .assignedRestaurantId(assignedId)
                .assignedRestaurantName(assignedName)
                .nearbyRestaurants(nearby)
                .executionTimeMs(elapsed)
                .build();
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private String buildLocationPayload(String riderId, double lat, double lng,
                                        String zone, Long assignedId, String assignedName,
                                        int nearbyCount) {
        return toJson(new java.util.LinkedHashMap<>() {{
            put("riderId",      riderId);
            put("lat",          lat);
            put("lng",          lng);
            put("zone",         zone);
            put("assignedId",   assignedId);
            put("assignedName", assignedName);
            put("nearbyCount",  nearbyCount);
        }});
    }

    private String buildZonePayload(String riderId, double lat, double lng, String zone) {
        return toJson(new java.util.LinkedHashMap<>() {{
            put("riderId", riderId);
            put("lat",     lat);
            put("lng",     lng);
            put("zone",    zone);
        }});
    }

    private String buildAssignPayload(String riderId, double lat, double lng, NearbyRestaurant r) {
        return toJson(new java.util.LinkedHashMap<>() {{
            put("riderId",        riderId);
            put("lat",            lat);
            put("lng",            lng);
            put("restaurantId",   r.getId());
            put("restaurantName", r.getName());
            put("distanceMiles",  r.getDistanceMiles());
        }});
    }

    private String buildUnassignPayload(String riderId, double lat, double lng, Long prevId) {
        return toJson(new java.util.LinkedHashMap<>() {{
            put("riderId",           riderId);
            put("lat",               lat);
            put("lng",               lng);
            put("prevRestaurantId",  prevId);
        }});
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void publishEvent(EventStreamService.EventType type, String payload) {
        eventStreamService.publish(type, payload);
    }
}
