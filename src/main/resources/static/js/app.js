/* ═══════════════════════════════════════════════════════════════
   SimpleLocator — app.js
   ═══════════════════════════════════════════════════════════════ */

// Leaflet map instances (one per tab, lazily initialized)
const maps        = {};
const layerGroups = {};

function setLocation(lat, lng) {
    document.getElementById('latitude').value  = lat;
    document.getElementById('longitude').value = lng;
}

// ── Main search ──────────────────────────────────────────────────────────────

async function runSearch() {
    const lat = parseFloat(document.getElementById('latitude').value);
    const lng = parseFloat(document.getElementById('longitude').value);
    if (isNaN(lat) || isNaN(lng)) { alert('Enter valid coordinates.'); return; }

    const btn = document.getElementById('searchBtn');
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-icon">⏳</span> Searching…';

    // Show results spinner
    document.getElementById('resultsSection').style.display   = 'block';
    document.getElementById('loading').style.display          = 'block';
    document.getElementById('resultsContent').style.display   = 'none';
    document.getElementById('vizSection').style.display       = 'none';
    document.getElementById('resultsSection').scrollIntoView({ behavior: 'smooth' });

    try {
        // 1. Quick compare (results + timing)
        const compareResp = await fetch('/api/restaurants/search/compare', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ latitude: lat, longitude: lng })
        });
        if (!compareResp.ok) throw new Error(`HTTP ${compareResp.status}`);
        renderResults(await compareResp.json());

        // 2. Debug data for maps (slightly heavier — fetch after results visible)
        fetchAndRenderMaps(lat, lng);

    } catch (err) {
        document.getElementById('loading').innerHTML =
            `<p style="color:#ef4444">❌ Search failed: ${err.message}</p>`;
    } finally {
        btn.disabled = false;
        btn.innerHTML = '<span class="btn-icon">🔍</span> Search';
    }
}

// ── Compare results ──────────────────────────────────────────────────────────

function renderResults(data) {
    document.getElementById('loading').style.display       = 'none';
    document.getElementById('resultsContent').style.display = 'block';

    const maxTime = Math.max(data.gist.executionTimeMs, data.spgist.executionTimeMs,
                             data.geohash.executionTimeMs, 1);

    document.getElementById('timingBars').innerHTML =
        timingRow('GiST (R-tree)',    data.gist.executionTimeMs,    maxTime, 'gist-fill')  +
        timingRow('SP-GiST (Quad)',   data.spgist.executionTimeMs,  maxTime, 'spgist-fill') +
        timingRow('Geohash (B-tree)', data.geohash.executionTimeMs, maxTime, 'geohash-fill');

    for (const [key, res] of Object.entries({ gist: data.gist, spgist: data.spgist, geohash: data.geohash })) {
        document.getElementById(`${key}Count`).textContent  = `${res.count} found`;
        document.getElementById(`${key}Timing`).textContent = `⏱ ${res.executionTimeMs} ms`;
        renderList(`${key}List`, res.restaurants, `${key}-dist`);
    }
}

function timingRow(label, ms, maxTime, cls) {
    const pct = Math.max((ms / maxTime) * 100, 6).toFixed(1);
    return `<div class="timing-row">
        <span class="timing-label">${label}</span>
        <div class="timing-track">
          <div class="timing-fill ${cls}" style="width:${pct}%">${ms} ms</div>
        </div></div>`;
}

function renderList(id, items, distCls) {
    const ul = document.getElementById(id);
    if (!items?.length) { ul.innerHTML = '<li class="r-empty">No results nearby.</li>'; return; }
    ul.innerHTML = items.map(r => `
        <li>
          <div>
            <div class="r-name">${esc(r.name)}</div>
            <div class="r-cuisine">${esc(r.cuisine||'')} · <code style="font-size:.64rem">${esc(r.geohash||'')}</code></div>
          </div>
          <span class="r-dist ${distCls}">${r.distanceMiles} mi</span>
        </li>`).join('');
}

// ── Map visualization ────────────────────────────────────────────────────────

async function fetchAndRenderMaps(lat, lng) {
    document.getElementById('vizSection').style.display  = 'block';
    document.getElementById('vizLoading').style.display  = 'block';
    document.getElementById('vizContent').style.display  = 'none';

    try {
        const resp = await fetch('/api/restaurants/search/debug', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ latitude: lat, longitude: lng })
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const d = await resp.json();

        document.getElementById('vizLoading').style.display = 'none';
        document.getElementById('vizContent').style.display = 'block';

        renderGistMap(d);
        renderSpgistMap(d);
        renderGeohashMap(d);
        showVizTab('gist');   // ensure first tab is active + map renders correctly

    } catch (e) {
        document.getElementById('vizLoading').innerHTML =
            `<p style="color:#ef4444">❌ Map data failed: ${e.message}</p>`;
    }
}

// ── Tab switching ────────────────────────────────────────────────────────────

function showVizTab(type) {
    ['gist', 'spgist', 'geohash'].forEach(t => {
        document.getElementById(`viz-${t}`).style.display  = t === type ? 'block' : 'none';
        document.getElementById(`tab-${t}`).classList.toggle('active', t === type);
    });
    // Leaflet needs a size refresh when its container becomes visible
    if (maps[type]) setTimeout(() => maps[type].invalidateSize(), 10);
}

// ── Leaflet helpers ──────────────────────────────────────────────────────────

function getOrCreateMap(id, lat, lng) {
    if (maps[id]) {
        layerGroups[id].clearLayers();
        maps[id].setView([lat, lng], 12);
        return maps[id];
    }
    const m = L.map(`map-${id}`, { zoomControl: true }).setView([lat, lng], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(m);
    maps[id]        = m;
    layerGroups[id] = L.layerGroup().addTo(m);
    return m;
}

function addLayer(id, layer) { layerGroups[id].addLayer(layer); }

function searchCircle(id, d, color = '#2563eb') {
    addLayer(id, L.circle([d.centerLat, d.centerLng], {
        radius: d.radiusMeters, color, weight: 2, dashArray: '8 5',
        fillOpacity: 0.04
    }).bindTooltip('5-mile search radius'));
}

function markerCircle(id, r, fillColor, radius, popup) {
    addLayer(id, L.circleMarker([r.latitude, r.longitude], {
        radius, fillColor, color: '#fff', weight: 1.5, fillOpacity: 0.92
    }).bindPopup(popup));
}

// ── GiST (R-tree) map ────────────────────────────────────────────────────────
// Shows: MBR groups (solid=searched, dashed=pruned) + result markers

function renderGistMap(d) {
    const m = getOrCreateMap('gist', d.centerLat, d.centerLng);

    // Draw MBR groups
    d.mbrGroups.forEach(g => {
        const b = g.bbox;
        const rect = L.rectangle([[b.minLat, b.minLng], [b.maxLat, b.maxLng]], {
            color: g.color, weight: 2.5,
            dashArray: g.intersectsSearch ? null : '7 5',
            fillColor: g.color,
            fillOpacity: g.intersectsSearch ? 0.07 : 0.02,
            opacity:     g.intersectsSearch ? 1.0  : 0.45
        });
        rect.addTo(layerGroups.gist);
        rect.bindPopup(
            `<b>${g.name}</b><br>` +
            `Points: ${g.pointCount}<br>` +
            (g.intersectsSearch
                ? `<span style="color:#16a34a">✓ MBR intersects search circle → branch explored</span>`
                : `<span style="color:#ef4444">✗ MBR outside search circle → branch pruned</span>`)
        );
    });

    // Search circle
    searchCircle('gist', d);

    // Markers: blue = result, gray = pruned
    d.restaurants.forEach(r => {
        const inResult = r.withinRadius;
        markerCircle('gist', r,
            inResult ? '#2563eb' : '#94a3b8',
            inResult ? 8 : 5,
            `<b>${esc(r.name)}</b><br>` +
            `${esc(r.cuisine)}<br>` +
            `MBR: ${r.mbrGroup || '—'}<br>` +
            (inResult
                ? `<span style="color:#2563eb">✓ Within 5 miles (${r.distanceMiles} mi)</span>`
                : `<span style="color:#94a3b8">✗ Outside radius (${r.distanceMiles} mi)</span>`)
        );
    });
}

// ── SP-GiST (Quadtree) map ───────────────────────────────────────────────────
// Shows: ST_Expand bbox (yellow) + search circle + 3-colour markers

function renderSpgistMap(d) {
    getOrCreateMap('spgist', d.centerLat, d.centerLng);

    // Search bounding box (ST_Expand envelope)
    const b = d.searchBbox;
    const bboxRect = L.rectangle([[b.minLat, b.minLng], [b.maxLat, b.maxLng]], {
        color: '#f59e0b', weight: 2.5, fillColor: '#f59e0b', fillOpacity: 0.06
    });
    bboxRect.addTo(layerGroups.spgist);
    bboxRect.bindPopup('<b>ST_Expand bounding box</b><br>SP-GiST reads only quadrant nodes overlapping this rectangle');

    searchCircle('spgist', d);

    d.restaurants.forEach(r => {
        const color  = r.withinRadius ? '#16a34a' : r.withinBbox ? '#f59e0b' : '#94a3b8';
        const radius = r.withinRadius ? 8 : 5;
        const status = r.withinRadius
            ? `<span style="color:#16a34a">✓ In circle → result (${r.distanceMiles} mi)</span>`
            : r.withinBbox
            ? `<span style="color:#f59e0b">⚠ In bbox, filtered by ST_DWithin (${r.distanceMiles} mi)</span>`
            : `<span style="color:#94a3b8">✗ Outside bbox → pruned by SP-GiST</span>`;
        markerCircle('spgist', r, color, radius,
            `<b>${esc(r.name)}</b><br>${esc(r.cuisine)}<br>${status}`);
    });
}

// ── Geohash (B-tree) map ─────────────────────────────────────────────────────
// Shows: 9 geohash cells (center+neighbors) + search circle + 3-colour markers

function renderGeohashMap(d) {
    getOrCreateMap('geohash', d.centerLat, d.centerLng);

    // Geohash cells
    d.geohashCells.forEach(cell => {
        const b = cell.bbox;
        const rect = L.rectangle([[b.minLat, b.minLng], [b.maxLat, b.maxLng]], {
            color:       cell.center ? '#ea580c' : '#f97316',
            weight:      cell.center ? 3 : 1.5,
            fillColor:   cell.center ? '#ea580c' : '#fb923c',
            fillOpacity: cell.center ? 0.18 : 0.07
        });
        rect.addTo(layerGroups.geohash);
        rect.bindPopup(
            `<b>Geohash cell: <code>${cell.prefix}</code></b><br>` +
            (cell.center ? '★ Center cell' : 'Neighbor cell') +
            `<br>Queried via: <code>geohash LIKE '${cell.prefix}%'</code>`
        );
    });

    searchCircle('geohash', d);

    d.restaurants.forEach(r => {
        const color  = r.withinRadius ? '#16a34a' : r.geohashCell ? '#f59e0b' : '#94a3b8';
        const radius = r.withinRadius ? 8 : 5;
        const cell   = r.geohashCell
            ? `Cell: <code>${r.geohashCell}</code> ${r.geohashCellIsCenter ? '(center)' : '(neighbor)'}`
            : 'Not in any search cell';
        const status = r.withinRadius
            ? `<span style="color:#16a34a">✓ In cell + circle → result (${r.distanceMiles} mi)</span>`
            : r.geohashCell
            ? `<span style="color:#f59e0b">⚠ In cell, outside circle → filtered (${r.distanceMiles} mi)</span>`
            : `<span style="color:#94a3b8">✗ No matching cell prefix → never read</span>`;
        markerCircle('geohash', r, color, radius,
            `<b>${esc(r.name)}</b><br>${esc(r.cuisine)}<br><code style="font-size:.7rem">${esc(r.geohash||'')}</code><br>${cell}<br>${status}`);
    });
}

// ── Utilities ────────────────────────────────────────────────────────────────

function esc(s) {
    return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── Rider / Redis GEO ────────────────────────────────────────────────────────

/** Route waypoints through Manhattan (Financial District → 59th St) */
const RIDER_ROUTE = [
    [40.7128, -74.0060],  // Financial District
    [40.7175, -74.0030],  // Tribeca
    [40.7230, -74.0003],  // SoHo / Canal St
    [40.7267, -73.9981],  // NoHo
    [40.7282, -73.9942],  // East Village
    [40.7330, -73.9923],  // Union Square
    [40.7359, -73.9911],  // Gramercy
    [40.7421, -73.9897],  // Flatiron
    [40.7484, -73.9854],  // Herald Square
    [40.7549, -73.9840],  // Penn Station
    [40.7580, -73.9855],  // Times Square
    [40.7614, -73.9776],  // Bryant Park / 5th Ave
    [40.7680, -73.9819],  // Rockefeller Center
    [40.7711, -73.9820],  // 59th St / Central Park South
];

const STEPS_PER_SEG  = 12;   // interpolation steps between waypoints
const RADIUS_MILES   = 2.0;  // fixed 2-mile search radius
const RIDER_ID       = 'rider-1';

let riderMap        = null;
let riderMarker     = null;
let riderCircle     = null;
let riderRouteLine  = null;
let riderInterval   = null;
let riderRunning    = false;
let riderSeg        = 0;
let riderStep       = 0;
let riderSseSource  = null;           // EventSource for SSE
let currentAssignedId = null;         // currently assigned restaurant id
const riderAllMarkers = new Map();    // id → L.circleMarker (all restaurants)
const riderZoneLayers = [];           // zone rectangles on the map
const eventLog        = [];           // event log entries (most recent first)

/** Interpolate position along the route */
function getRiderPos() {
    const p1 = RIDER_ROUTE[riderSeg];
    const p2 = RIDER_ROUTE[(riderSeg + 1) % RIDER_ROUTE.length];
    const t  = riderStep / STEPS_PER_SEG;
    return [p1[0] + (p2[0] - p1[0]) * t, p1[1] + (p2[1] - p1[1]) * t];
}

function advanceRiderPos() {
    riderStep++;
    if (riderStep >= STEPS_PER_SEG) {
        riderStep = 0;
        riderSeg  = (riderSeg + 1) % (RIDER_ROUTE.length - 1);
    }
}

// ── Zone initialization ───────────────────────────────────────────────────────

async function initZones() {
    try {
        const resp = await fetch('/api/rider/zones');
        if (!resp.ok) return;
        const zones = await resp.json();
        zones.forEach(z => {
            const rect = L.rectangle(
                [[z.minLat, z.minLng], [z.maxLat, z.maxLng]],
                { color: z.color, weight: 2, fillColor: z.color, fillOpacity: 0.10, dashArray: '6 4' }
            ).bindTooltip(`<b>${esc(z.name)}</b><br>Delivery Zone`, { sticky: true });
            rect.addTo(riderMap);
            riderZoneLayers.push({ zone: z, rect });
        });
    } catch (e) { console.warn('Could not load zones:', e); }
}

// ── SSE subscription ──────────────────────────────────────────────────────────

function initSse() {
    if (riderSseSource) return;          // already connected
    riderSseSource = new EventSource('/api/rider/stream');

    riderSseSource.addEventListener('LOCATION_UPDATE', e => {
        // Silently received — map is already updated by riderTick()
    });

    riderSseSource.addEventListener('GEOFENCE_ENTER', e => {
        const d = JSON.parse(e.data);
        addEventLog('enter', `Entered zone: <strong>${esc(d.zone)}</strong>`);
        highlightZone(d.zone, true);
    });

    riderSseSource.addEventListener('GEOFENCE_EXIT', e => {
        const d = JSON.parse(e.data);
        addEventLog('exit', `Left zone: <strong>${esc(d.zone)}</strong>`);
        highlightZone(d.zone, false);
    });

    riderSseSource.addEventListener('RIDER_ASSIGNED', e => {
        const d = JSON.parse(e.data);
        addEventLog('assign',
            `Assigned to <strong>${esc(d.restaurantName)}</strong> — ${d.distanceMiles} mi away`);
        highlightAssigned(d.restaurantId);
    });

    riderSseSource.addEventListener('RIDER_UNASSIGNED', e => {
        addEventLog('unassign', 'Assignment cleared — no restaurant within 0.5 mi');
        highlightAssigned(null);
    });

    riderSseSource.onerror = () => {
        // Connection drops when tab goes to background — will reconnect automatically
    };
}

function highlightZone(zoneName, entering) {
    riderZoneLayers.forEach(({ zone, rect }) => {
        if (zone.name === zoneName) {
            rect.setStyle({ fillOpacity: entering ? 0.30 : 0.10 });
        }
    });
}

function highlightAssigned(restaurantId) {
    currentAssignedId = restaurantId;
    riderAllMarkers.forEach((marker, id) => {
        if (id === restaurantId) {
            marker.setStyle({ fillColor: '#f59e0b', radius: 13, fillOpacity: 1.0 });
        }
    });
}

// ── Event log ─────────────────────────────────────────────────────────────────

const EVENT_ICONS = {
    enter:    { icon: '🟢', cls: 'ev-enter'    },
    exit:     { icon: '🔴', cls: 'ev-exit'     },
    assign:   { icon: '⭐', cls: 'ev-assign'   },
    unassign: { icon: '⬜', cls: 'ev-unassign' },
};

function addEventLog(type, html) {
    const ts = new Date().toLocaleTimeString();
    eventLog.unshift({ type, html, ts });
    if (eventLog.length > 40) eventLog.pop();
    renderEventLog();
}

function renderEventLog() {
    const el = document.getElementById('eventLog');
    if (!el) return;
    if (!eventLog.length) {
        el.innerHTML = '<div class="ev-empty">Events will appear here once the rider starts…</div>';
        return;
    }
    el.innerHTML = eventLog.map(ev => {
        const { icon, cls } = EVENT_ICONS[ev.type] || { icon: '📌', cls: '' };
        return `<div class="event-item ${cls}">
          <span class="ev-icon">${icon}</span>
          <span class="ev-body">${ev.html}</span>
          <span class="ev-ts">${ev.ts}</span>
        </div>`;
    }).join('');
}

// ── Map init ──────────────────────────────────────────────────────────────────

async function initRiderMap() {
    if (riderMap) return;

    riderMap = L.map('map-rider').setView(RIDER_ROUTE[0], 13);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(riderMap);

    // Delivery zones (drawn before restaurant dots so dots appear on top)
    await initZones();

    // Dashed route line
    riderRouteLine = L.polyline(RIDER_ROUTE, {
        color: '#DC382D', weight: 2.5, dashArray: '6 5', opacity: 0.5
    }).addTo(riderMap);

    // 2-mile search circle
    riderCircle = L.circle(RIDER_ROUTE[0], {
        radius: RADIUS_MILES * 1609.344,
        color: '#DC382D', weight: 2, dashArray: '8 5', fillOpacity: 0.05
    }).addTo(riderMap);

    // Rider marker (emoji icon)
    const riderIcon = L.divIcon({
        html: '<div class="rider-icon-div">🏍️</div>',
        iconSize: [32, 32], iconAnchor: [16, 16], className: ''
    });
    riderMarker = L.marker(RIDER_ROUTE[0], {icon: riderIcon, zIndexOffset: 1000})
        .bindTooltip('Rider').addTo(riderMap);

    // Pre-load all restaurants as gray dots
    try {
        const resp = await fetch('/api/rider/restaurants');
        if (resp.ok) {
            const all = await resp.json();
            all.forEach(r => {
                const m = L.circleMarker([r.latitude, r.longitude], {
                    radius: 7, fillColor: '#94a3b8', color: '#fff',
                    weight: 1.5, fillOpacity: 0.75
                }).bindPopup(`<b>${esc(r.name)}</b><br>${esc(r.cuisine || '')}<br><span style="color:#94a3b8">Outside 2-mile radius</span>`);
                m.addTo(riderMap);
                riderAllMarkers.set(r.id, m);
            });
        }
    } catch (e) { console.error('Could not pre-load restaurants:', e); }
}

// ── Rider tick ────────────────────────────────────────────────────────────────

async function riderTick() {
    const [lat, lng] = getRiderPos();
    advanceRiderPos();

    // Move marker + circle
    riderMarker.setLatLng([lat, lng]);
    riderCircle.setLatLng([lat, lng]);
    riderMap.panTo([lat, lng], {animate: true, duration: 0.4});

    // Update position display
    document.getElementById('riderPos').textContent =
        `📍 ${lat.toFixed(4)}, ${lng.toFixed(4)}`;

    // Show the exact Redis command being executed (GEOSEARCH — modern syntax)
    const cmdEl = document.getElementById('riderCmd');
    cmdEl.style.display = 'block';
    document.getElementById('riderCmdText').textContent =
        `GEOSEARCH restaurants:geo FROMLONLAT ${lng.toFixed(4)} ${lat.toFixed(4)} BYRADIUS ${RADIUS_MILES} mi ASC WITHCOORD WITHDIST COUNT 20`;

    try {
        const resp = await fetch('/api/rider/update', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ riderId: RIDER_ID, latitude: lat, longitude: lng })
        });
        if (!resp.ok) return;
        const data = await resp.json();

        // Execution time: show Redis + PostGIS combined latency
        const zoneLabel = data.currentZone ? ` · Zone: ${esc(data.currentZone)}` : ' · Outside zones';
        document.getElementById('riderTiming').textContent =
            `⚡ ${data.executionTimeMs} ms  ·  ${data.nearbyRestaurants.length} nearby${zoneLabel}`;

        // Update zone display in controls bar
        const zoneBadge = document.getElementById('riderZone');
        if (zoneBadge) {
            zoneBadge.textContent = data.currentZone ? data.currentZone : '—';
            zoneBadge.className   = data.currentZone ? 'zone-badge active' : 'zone-badge';
        }

        // Update restaurant dot colors
        const nearbyIds = new Set(data.nearbyRestaurants.map(r => r.id));
        riderAllMarkers.forEach((marker, id) => {
            if (id === currentAssignedId) {
                // Assigned → gold star (kept by RIDER_ASSIGNED SSE event)
                marker.setStyle({ fillColor: '#f59e0b', radius: 13, fillOpacity: 1.0 });
            } else if (nearbyIds.has(id)) {
                marker.setStyle({ fillColor: '#16a34a', radius: 10, fillOpacity: 0.95 });
            } else {
                marker.setStyle({ fillColor: '#94a3b8', radius: 7, fillOpacity: 0.75 });
            }
        });

        // Update popups for nearby restaurants with distance
        data.nearbyRestaurants.forEach(r => {
            const m = riderAllMarkers.get(r.id);
            if (!m) return;
            const isAssigned = r.id === data.assignedRestaurantId;
            m.bindPopup(
                `<b>${esc(r.name)}</b><br>${esc(r.cuisine || '')}<br>` +
                (isAssigned
                    ? `<span style="color:#f59e0b">⭐ ASSIGNED — ${r.distanceMiles} mi</span>`
                    : `<span style="color:#16a34a">✓ ${r.distanceMiles} mi away</span>`)
            );
        });

        renderRiderList(data.nearbyRestaurants, data.assignedRestaurantId);
    } catch (e) { console.error('Rider update failed:', e); }
}

// ── Sidebar list ──────────────────────────────────────────────────────────────

function renderRiderList(items, assignedId) {
    const ul    = document.getElementById('riderList');
    const count = document.getElementById('riderCount');
    if (!items?.length) {
        ul.innerHTML = '<li class="r-empty">No restaurants within 2 miles</li>';
        count.textContent = '0 found';
        return;
    }
    count.textContent = `${items.length} found`;
    ul.innerHTML = items.map(r => {
        const isAssigned = r.id === assignedId;
        return `<li class="${isAssigned ? 'assigned-row' : ''}">
          <div>
            <div class="r-name">${isAssigned ? '⭐ ' : ''}${esc(r.name)}</div>
            <div class="r-cuisine">${esc(r.cuisine || '')}</div>
          </div>
          <span class="r-dist ${isAssigned ? 'assigned-dist' : 'redis-dist'}">${r.distanceMiles} mi</span>
        </li>`;
    }).join('');
}

// ── Toggle simulation ─────────────────────────────────────────────────────────

async function toggleRider() {
    await initRiderMap();
    initSse();       // open SSE connection on first start
    const btn = document.getElementById('riderStartBtn');
    if (riderRunning) {
        clearInterval(riderInterval);
        riderRunning = false;
        btn.textContent = '▶ Resume Riding';
        btn.classList.remove('paused');
    } else {
        riderRunning = true;
        btn.textContent = '⏸ Pause';
        btn.classList.add('paused');
        riderTick();   // immediate first tick
        const speed = parseInt(document.getElementById('riderSpeed').value);
        riderInterval = setInterval(riderTick, speed);
    }
}

// Auto-search on page load
window.addEventListener('DOMContentLoaded', () => {
    runSearch();
    renderEventLog();   // show empty state
    document.getElementById('riderSpeed').addEventListener('change', () => {
        if (riderRunning) {
            clearInterval(riderInterval);
            const speed = parseInt(document.getElementById('riderSpeed').value);
            riderInterval = setInterval(riderTick, speed);
        }
    });
});
