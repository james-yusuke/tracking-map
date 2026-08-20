export type TrackingState = "active" | "paused" | "permission_denied";
export type Connectivity = "online" | "offline";

export type ChildSummary = {
  id: string;
  name: string;
  color: string;
  trackingState: TrackingState;
  connectivity: Connectivity;
  device: null | {
    id: string;
    name: string;
    platform: "android" | "ios";
    lastSeenAt: string;
  };
  latestLocation: null | {
    latitude: number;
    longitude: number;
    accuracy: number;
    recordedAt: string;
    batteryLevel: number;
    isCharging: boolean;
  };
};

export type SafetyZone = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  color: string;
  childIds: string[];
};

export type FamilyAlert = {
  id: string;
  childId: string | null;
  type: "zone_entered" | "zone_exited" | "tracking_paused" | "tracking_resumed" | "permission_denied" | "device_offline";
  title: string;
  message: string;
  occurredAt: string;
};

export type DashboardData = {
  family: { id: string; name: string; role: "owner" | "guardian" };
  children: ChildSummary[];
  zones: SafetyZone[];
  alerts: FamilyAlert[];
  generatedAt: string;
};

export type HistoryPoint = {
  latitude: number;
  longitude: number;
  accuracy: number;
  recordedAt: string;
  batteryLevel: number;
};

