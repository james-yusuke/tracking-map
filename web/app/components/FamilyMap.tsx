"use client";

import maplibregl, { type GeoJSONSource, type Map as MapLibreMap, type StyleSpecification } from "maplibre-gl";
import { useEffect, useRef } from "react";
import type { ChildSummary, HistoryPoint, SafetyZone } from "../lib/types";

type Props = {
  familyChildren: ChildSummary[];
  selectedChildId: string;
  zones: SafetyZone[];
  history: HistoryPoint[];
  showHistory: boolean;
};

const fallbackStyle: StyleSpecification = {
  version: 8,
  sources: {
    openstreetmap: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      attribution: "© OpenStreetMap contributors",
    },
  },
  layers: [{ id: "osm", type: "raster", source: "openstreetmap", paint: { "raster-saturation": -0.78, "raster-opacity": 0.78, "raster-brightness-min": 0.12, "raster-brightness-max": 0.98 } }],
};

const mapTilerStyle = (key: string): StyleSpecification => ({
  version: 8,
  sources: {
    maptiler: {
      type: "raster",
      url: `https://api.maptiler.com/maps/streets-v4/256/tiles.json?key=${encodeURIComponent(key)}`,
      tileSize: 256,
      attribution: "© MapTiler © OpenStreetMap contributors",
    },
  },
  layers: [{ id: "maptiler", type: "raster", source: "maptiler", paint: { "raster-saturation": -0.34, "raster-opacity": 0.92 } }],
});

export default function FamilyMap({ familyChildren, selectedChildId, zones, history, showHistory }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markersRef = useRef<Map<string, maplibregl.Marker>>(new Map());
  const initialStateRef = useRef({ familyChildren, selectedChildId, zones, history, showHistory });

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const initial = initialStateRef.current;
    const available = initial.familyChildren.filter((child) => child.latestLocation);
    const primary = available.find((child) => child.id === initial.selectedChildId) || available[0];
    const key = process.env.NEXT_PUBLIC_MAPTILER_KEY;
    const style = key ? mapTilerStyle(key) : fallbackStyle;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style,
      center: primary?.latestLocation ? [primary.latestLocation.longitude, primary.latestLocation.latitude] : [139.63, 35.705],
      zoom: 13.8,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "bottom-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");
    map.on("load", () => {
      map.addSource("accuracy-circles", { type: "geojson", data: accuracyFeatureCollection(initial.familyChildren) });
      map.addLayer({ id: "accuracy-circle-fill", type: "fill", source: "accuracy-circles", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.13 } });
      map.addLayer({ id: "accuracy-circle-line", type: "line", source: "accuracy-circles", paint: { "line-color": ["get", "color"], "line-width": 1.5, "line-opacity": 0.55 } });
      map.addSource("safe-zones", { type: "geojson", data: zonesFeatureCollection(initial.zones) });
      map.addLayer({ id: "safe-zone-fill", type: "fill", source: "safe-zones", paint: { "fill-color": ["get", "color"], "fill-opacity": 0.16 } });
      map.addLayer({ id: "safe-zone-line", type: "line", source: "safe-zones", paint: { "line-color": ["get", "color"], "line-width": 2, "line-dasharray": [2, 2] } });
      map.addSource("history-route", { type: "geojson", data: historyFeatureCollection(initial.history) });
      map.addLayer({ id: "history-route-halo", type: "line", source: "history-route", layout: { "line-cap": "round", "line-join": "round", visibility: initial.showHistory ? "visible" : "none" }, paint: { "line-color": "#ffffff", "line-width": 7, "line-opacity": 0.86 } });
      map.addLayer({ id: "history-route", type: "line", source: "history-route", layout: { "line-cap": "round", "line-join": "round", visibility: initial.showHistory ? "visible" : "none" }, paint: { "line-color": "#0B1739", "line-width": 3.5 } });
    });
    mapRef.current = map;
    const resizeObserver = new ResizeObserver(() => map.resize());
    resizeObserver.observe(containerRef.current);
    const markers = markersRef.current;
    return () => {
      resizeObserver.disconnect();
      markers.forEach((marker) => marker.remove());
      markers.clear();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    familyChildren.forEach((child) => {
      const location = child.latestLocation;
      if (!location) return;
      let marker = markersRef.current.get(child.id);
      if (!marker) {
        const element = document.createElement("button");
        element.type = "button";
        element.className = "family-marker";
        element.setAttribute("aria-label", `${child.name}の現在地`);
        const accuracy = document.createElement("span");
        accuracy.className = "marker-accuracy";
        const badge = document.createElement("span");
        badge.className = "marker-badge";
        badge.style.background = child.color;
        badge.textContent = child.name.slice(0, 1);
        const label = document.createElement("span");
        label.className = "marker-label";
        label.textContent = child.name;
        element.append(accuracy, badge, label);
        marker = new maplibregl.Marker({ element, anchor: "center" }).setLngLat([location.longitude, location.latitude]).addTo(map);
        markersRef.current.set(child.id, marker);
      }
      marker.setLngLat([location.longitude, location.latitude]);
      marker.getElement().classList.toggle("is-selected", child.id === selectedChildId);
      marker.getElement().classList.toggle("is-stale", child.connectivity === "offline");
    });
  }, [familyChildren, selectedChildId]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => (map.getSource("accuracy-circles") as GeoJSONSource | undefined)?.setData(accuracyFeatureCollection(familyChildren));
    if (map.isStyleLoaded()) update(); else map.once("load", update);
    return () => { map.off("load", update); };
  }, [familyChildren]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => (map.getSource("safe-zones") as GeoJSONSource | undefined)?.setData(zonesFeatureCollection(zones));
    if (map.isStyleLoaded()) update(); else map.once("load", update);
    return () => { map.off("load", update); };
  }, [zones]);

  useEffect(() => {
    const map = mapRef.current;
    const selected = familyChildren.find((child) => child.id === selectedChildId)?.latestLocation;
    if (!map || !selected) return;
    map.easeTo({ center: [selected.longitude, selected.latitude], zoom: showHistory ? 14.6 : 14.2, duration: 900 });
  }, [familyChildren, selectedChildId, showHistory]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const update = () => {
      (map.getSource("history-route") as GeoJSONSource | undefined)?.setData(historyFeatureCollection(history));
      const visibility = showHistory ? "visible" : "none";
      if (map.getLayer("history-route")) map.setLayoutProperty("history-route", "visibility", visibility);
      if (map.getLayer("history-route-halo")) map.setLayoutProperty("history-route-halo", "visibility", visibility);
    };
    if (map.isStyleLoaded()) update(); else map.once("load", update);
    return () => { map.off("load", update); };
  }, [history, showHistory]);

  return <div className="map-canvas" ref={containerRef} aria-label="家族の位置を表示する地図" />;
}

function historyFeatureCollection(points: HistoryPoint[]): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: points.length > 1 ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: points.map((point) => [point.longitude, point.latitude]) } }] : [],
  };
}

function zonesFeatureCollection(zones: SafetyZone[]): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: zones.map((zone) => ({
      type: "Feature",
      properties: { name: zone.name, color: zone.color },
      geometry: { type: "Polygon", coordinates: [circleCoordinates(zone.longitude, zone.latitude, zone.radiusMeters)] },
    })),
  };
}

function accuracyFeatureCollection(children: ChildSummary[]): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: children.flatMap((child) => {
      const location = child.latestLocation;
      if (!location) return [];
      return [{
        type: "Feature" as const,
        properties: { childId: child.id, color: child.color, accuracy: location.accuracy },
        geometry: { type: "Polygon" as const, coordinates: [circleCoordinates(location.longitude, location.latitude, location.accuracy)] },
      }];
    }),
  };
}

function circleCoordinates(longitude: number, latitude: number, radiusMeters: number) {
  const points = 64;
  const earthRadius = 6_371_000;
  const coordinates: [number, number][] = [];
  for (let index = 0; index <= points; index += 1) {
    const angle = (index / points) * Math.PI * 2;
    const latOffset = (radiusMeters / earthRadius) * Math.sin(angle);
    const lonOffset = (radiusMeters / (earthRadius * Math.cos((latitude * Math.PI) / 180))) * Math.cos(angle);
    coordinates.push([longitude + (lonOffset * 180) / Math.PI, latitude + (latOffset * 180) / Math.PI]);
  }
  return coordinates;
}
