import type { DashboardData, HistoryPoint } from "./types";

const now = Date.now();
const isoMinutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();

export const demoDashboard: DashboardData = {
  family: { id: "family-demo", name: "佐藤ファミリー", role: "owner" },
  children: [
    {
      id: "haruto",
      name: "陽翔",
      color: "#C9F45B",
      trackingState: "active",
      connectivity: "online",
      device: { id: "device-haruto", name: "陽翔のPixel", platform: "android", lastSeenAt: isoMinutesAgo(0) },
      latestLocation: { latitude: 35.70878, longitude: 139.63546, accuracy: 18, recordedAt: isoMinutesAgo(0), batteryLevel: 0.82, isCharging: false },
    },
    {
      id: "yui",
      name: "結衣",
      color: "#7DE0D1",
      trackingState: "active",
      connectivity: "online",
      device: { id: "device-yui", name: "結衣のiPhone", platform: "ios", lastSeenAt: isoMinutesAgo(12) },
      latestLocation: { latitude: 35.70432, longitude: 139.6194, accuracy: 31, recordedAt: isoMinutesAgo(12), batteryLevel: 0.64, isCharging: false },
    },
    {
      id: "sora",
      name: "空",
      color: "#FFB698",
      trackingState: "paused",
      connectivity: "offline",
      device: { id: "device-sora", name: "空のiPhone", platform: "ios", lastSeenAt: isoMinutesAgo(47) },
      latestLocation: { latitude: 35.6998, longitude: 139.6298, accuracy: 44, recordedAt: isoMinutesAgo(47), batteryLevel: 0.31, isCharging: false },
    },
  ],
  zones: [
    { id: "school", name: "みどり小学校", latitude: 35.7086, longitude: 139.6352, radiusMeters: 240, color: "#7DE0D1", childIds: ["haruto", "yui"] },
    { id: "home", name: "自宅", latitude: 35.7041, longitude: 139.6196, radiusMeters: 180, color: "#C9F45B", childIds: ["haruto", "yui", "sora"] },
  ],
  alerts: [
    { id: "a1", childId: "haruto", type: "zone_entered", title: "みどり小学校に到着しました", message: "陽翔がみどり小学校に到着しました。", occurredAt: isoMinutesAgo(4) },
    { id: "a2", childId: "yui", type: "zone_exited", title: "自宅を出発しました", message: "結衣が自宅を出発しました。", occurredAt: isoMinutesAgo(38) },
    { id: "a3", childId: "sora", type: "tracking_paused", title: "位置共有を一時停止しました", message: "空の端末で位置共有が一時停止されました。", occurredAt: isoMinutesAgo(47) },
    { id: "a4", childId: "haruto", type: "zone_exited", title: "自宅を出発しました", message: "陽翔が自宅を出発しました。", occurredAt: isoMinutesAgo(132) },
  ],
  generatedAt: new Date(now).toISOString(),
};

export const demoHistory: Record<string, HistoryPoint[]> = Object.fromEntries(
  demoDashboard.children.map((child, childIndex) => {
    const latest = child.latestLocation;
    if (!latest) return [child.id, []];
    return [
      child.id,
      Array.from({ length: 22 }, (_, index) => ({
        latitude: latest.latitude - (21 - index) * 0.00022 + Math.sin(index * 0.8 + childIndex) * 0.00018,
        longitude: latest.longitude - (21 - index) * 0.00027 + Math.cos(index * 0.7 + childIndex) * 0.00013,
        accuracy: 14 + (index % 4) * 5,
        recordedAt: new Date(now - (21 - index) * 4 * 60_000).toISOString(),
        batteryLevel: Math.max(0.1, latest.batteryLevel - (21 - index) * 0.004),
      })),
    ];
  }),
);

