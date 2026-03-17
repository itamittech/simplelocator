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

// Auto-search on page load
window.addEventListener('DOMContentLoaded', () => runSearch());
