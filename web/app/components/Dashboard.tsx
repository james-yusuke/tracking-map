"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Bell, BatteryCharging, ChevronDown, Clock3, History, House, LocateFixed, LogOut, Map, MapPin, Menu, Navigation, Route, ShieldCheck, Smartphone, Sparkles, Wifi, WifiOff, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { clearAccessToken, loadDashboard, loadHistory, websocketURL } from "../lib/api";
import type { ChildSummary, DashboardData, FamilyAlert, HistoryPoint } from "../lib/types";

const FamilyMap = dynamic(() => import("./FamilyMap"), { ssr: false, loading: () => <div className="map-loading"><span /><p>地図を準備しています</p></div> });
type View = "map" | "history" | "alerts";

export default function Dashboard() {
  const router = useRouter();
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [selectedChildId, setSelectedChildId] = useState("");
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [view, setView] = useState<View>("map");
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [familyMenuOpen, setFamilyMenuOpen] = useState(false);
  const [mobilePanelOpen, setMobilePanelOpen] = useState(false);
  const [isDemo, setIsDemo] = useState(false);
  const [loading, setLoading] = useState(true);
  const [now, setNow] = useState(0);

  const refresh = useCallback(async () => {
    try {
      const result = await loadDashboard();
      setDashboard(result.data);
      setIsDemo(result.isDemo);
      setSelectedChildId((current) => current || result.data.children[0]?.id || "");
    } catch {
      router.replace("/login");
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void refresh(), 0);
    return () => window.clearTimeout(initialLoad);
  }, [refresh]);
  useEffect(() => {
    const initialClock = window.setTimeout(() => setNow(Date.now()), 0);
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => { window.clearTimeout(initialClock); window.clearInterval(timer); };
  }, []);
  useEffect(() => {
    if (!selectedChildId) return;
    void loadHistory(selectedChildId).then(setHistory);
  }, [selectedChildId]);
  useEffect(() => {
    const url = websocketURL();
    if (!url || isDemo) return;
    const socket = new WebSocket(url);
    socket.onmessage = () => void refresh();
    return () => socket.close();
  }, [isDemo, refresh]);

  const selectedChild = dashboard?.children.find((child) => child.id === selectedChildId) || dashboard?.children[0];
  const filteredAlerts = useMemo(() => dashboard?.alerts.filter((alert) => !selectedChildId || alert.childId === selectedChildId) || [], [dashboard, selectedChildId]);
  const overallHealthy = dashboard?.children.every((child) => child.connectivity === "online" && child.trackingState === "active");

  if (loading || !dashboard || !selectedChild) return <DashboardSkeleton />;

  const logout = () => {
    clearAccessToken();
    router.push("/login");
  };

  return (
    <main className="dashboard-shell">
      <aside className={`sidebar ${mobilePanelOpen ? "mobile-open" : ""}`}>
        <div className="brand-row">
          <Link className="brand" href="/" aria-label="Family Orbit ホーム"><span className="brand-mark"><span /></span><strong>Family Orbit</strong></Link>
          <button className="icon-button close-mobile" aria-label="メニューを閉じる" onClick={() => setMobilePanelOpen(false)}><X size={19} /></button>
        </div>
        <button className="family-switcher" onClick={() => setFamilyMenuOpen((value) => !value)} aria-expanded={familyMenuOpen}>
          <span><small>MY FAMILY</small><strong>{dashboard.family.name}</strong></span><ChevronDown size={16} />
        </button>
        {familyMenuOpen && <div className="family-menu"><ShieldCheck size={16} /><span>オーナーとして閲覧中</span></div>}

        <nav className="main-nav" aria-label="メインメニュー">
          <button className={view === "map" ? "active" : ""} onClick={() => { setView("map"); setMobilePanelOpen(false); }}><Map size={18} /><span>現在地</span></button>
          <button className={view === "history" ? "active" : ""} onClick={() => { setView("history"); setMobilePanelOpen(false); }}><History size={18} /><span>移動履歴</span></button>
          <button className={view === "alerts" ? "active" : ""} onClick={() => { setView("alerts"); setMobilePanelOpen(false); }}><Bell size={18} /><span>通知</span><b>{dashboard.alerts.length}</b></button>
        </nav>

        <section className="family-section">
          <div className="section-heading"><span>家族</span><small>{dashboard.children.filter((child) => child.connectivity === "online").length}/{dashboard.children.length} オンライン</small></div>
          <div className="child-list">
            {dashboard.children.map((child) => <ChildButton child={child} selected={child.id === selectedChild.id} key={child.id} now={now} onClick={() => { setSelectedChildId(child.id); setMobilePanelOpen(false); }} />)}
          </div>
        </section>

        <div className="privacy-card"><ShieldCheck size={18} /><span><strong>共有は明示されています</strong><small>お子さまの端末にも共有状態が常に表示されます。</small></span></div>
        <button className="logout-button" onClick={logout}><LogOut size={16} /><span>ログアウト</span></button>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <button className="icon-button mobile-menu" aria-label="メニューを開く" onClick={() => setMobilePanelOpen(true)}><Menu size={21} /></button>
          <div className={`health-pill ${overallHealthy ? "healthy" : "attention"}`}><span className="pulse-dot" />{overallHealthy ? "すべて順調です" : "確認が必要な端末があります"}</div>
          {isDemo && <div className="demo-pill"><Sparkles size={13} /> デモデータ</div>}
          <div className="topbar-actions">
            <span className="last-sync">更新 {relativeTime(dashboard.generatedAt, now)}</span>
            <button className="notification-button" aria-label="通知を開く" onClick={() => setNotificationsOpen(true)}><Bell size={18} /><b>{dashboard.alerts.length}</b></button>
            <span className="profile-avatar">保</span>
          </div>
        </header>

        <div className="map-stage">
          <FamilyMap familyChildren={dashboard.children} selectedChildId={selectedChild.id} zones={dashboard.zones} history={history} showHistory={view === "history"} />
          <div className="map-title"><small>{view === "history" ? "TODAY'S JOURNEY" : "LIVE LOCATION"}</small><h1>{view === "history" ? `${selectedChild.name}の今日の移動` : "家族の現在地"}</h1></div>
          {view === "history" && <HistoryRail points={history} child={selectedChild} />}
          {view === "alerts" && <AlertsPanel alerts={filteredAlerts} now={now} />}
          {view === "map" && <CurrentLocationSheet child={selectedChild} zones={dashboard.zones.map((zone) => zone.name)} now={now} onHistory={() => setView("history")} />}
        </div>

        <nav className="mobile-nav" aria-label="モバイルメニュー">
          <button className={view === "map" ? "active" : ""} onClick={() => setView("map")}><Map size={20} /><span>現在地</span></button>
          <button className={view === "history" ? "active" : ""} onClick={() => setView("history")}><Route size={20} /><span>履歴</span></button>
          <button className={view === "alerts" ? "active" : ""} onClick={() => setView("alerts")}><Bell size={20} /><span>通知</span></button>
        </nav>
      </section>

      {notificationsOpen && <NotificationDrawer alerts={dashboard.alerts} now={now} onClose={() => setNotificationsOpen(false)} />}
    </main>
  );
}

function ChildButton({ child, selected, now, onClick }: { child: ChildSummary; selected: boolean; now: number; onClick: () => void }) {
  return (
    <button className={`child-button ${selected ? "selected" : ""}`} onClick={onClick} aria-pressed={selected}>
      <span className="avatar" style={{ background: child.color }}>{child.name.slice(0, 1)}<i className={child.connectivity} /></span>
      <span className="child-copy"><strong>{child.name}</strong><small>{child.latestLocation ? relativeTime(child.latestLocation.recordedAt, now) : "位置情報なし"}</small></span>
      <span className="mini-battery">{child.latestLocation ? Math.round(child.latestLocation.batteryLevel * 100) : "—"}%</span>
    </button>
  );
}

function CurrentLocationSheet({ child, zones, now, onHistory }: { child: ChildSummary; zones: string[]; now: number; onHistory: () => void }) {
  const location = child.latestLocation;
  return (
    <article className="location-sheet">
      <div className="sheet-grabber" />
      <div className="location-person">
        <span className="avatar large" style={{ background: child.color }}>{child.name.slice(0, 1)}<i className={child.connectivity} /></span>
        <span><small>現在の共有位置</small><strong>{location ? `${zones[0] || "登録エリア"}付近` : "まだ位置情報がありません"}</strong><em><MapPin size={13} /> {child.device?.name || "未ペアリング"}</em></span>
      </div>
      <dl className="location-stats">
        <div><dt><Clock3 size={14} /> 更新</dt><dd>{location ? relativeTime(location.recordedAt, now) : "—"}</dd></div>
        <div><dt><LocateFixed size={14} /> 精度</dt><dd>{location ? `± ${Math.round(location.accuracy)} m` : "—"}</dd></div>
        <div><dt>{location?.isCharging ? <BatteryCharging size={14} /> : <Smartphone size={14} />} バッテリー</dt><dd>{location ? `${Math.round(location.batteryLevel * 100)}%` : "—"}</dd></div>
        <div><dt>{child.connectivity === "online" ? <Wifi size={14} /> : <WifiOff size={14} />} 状態</dt><dd className={child.connectivity}>{child.connectivity === "online" ? "オンライン" : "オフライン"}</dd></div>
      </dl>
      <button className="history-cta" onClick={onHistory}><Route size={17} />今日の移動を見る</button>
    </article>
  );
}

function HistoryRail({ points, child }: { points: HistoryPoint[]; child: ChildSummary }) {
  const first = points[0];
  const last = points.at(-1);
  return (
    <article className="history-rail">
      <div className="history-summary"><span className="avatar" style={{ background: child.color }}>{child.name.slice(0, 1)}</span><span><small>今日の記録</small><strong>{points.length ? `${points.length}地点を記録` : "記録はありません"}</strong></span></div>
      <div className="time-range"><span>{first ? formatTime(first.recordedAt) : "—"}</span><i /><span>{last ? formatTime(last.recordedAt) : "—"}</span></div>
      <div className="history-note"><Navigation size={16} /><span>移動にあわせて30〜60秒間隔で更新</span></div>
    </article>
  );
}

function AlertsPanel({ alerts, now }: { alerts: FamilyAlert[]; now: number }) {
  return (
    <article className="alerts-panel">
      <div className="panel-heading"><span><small>RECENT ACTIVITY</small><h2>最近の通知</h2></span><b>{alerts.length}件</b></div>
      <div className="alert-list">
        {alerts.length ? alerts.map((alert) => <AlertRow alert={alert} now={now} key={alert.id} />) : <div className="empty-state"><ShieldCheck size={28} /><strong>通知はありません</strong><small>新しいお知らせがここに表示されます。</small></div>}
      </div>
    </article>
  );
}

function AlertRow({ alert, now }: { alert: FamilyAlert; now: number }) {
  const entered = alert.type === "zone_entered" || alert.type === "tracking_resumed";
  return <div className="alert-row"><span className={`alert-icon ${entered ? "positive" : "neutral"}`}>{entered ? <House size={17} /> : <Bell size={17} />}</span><span><strong>{alert.title}</strong><small>{alert.message}</small></span><time>{relativeTime(alert.occurredAt, now)}</time></div>;
}

function NotificationDrawer({ alerts, now, onClose }: { alerts: FamilyAlert[]; now: number; onClose: () => void }) {
  return <div className="drawer-layer" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><aside className="notification-drawer" role="dialog" aria-modal="true" aria-label="通知"><div className="drawer-header"><span><small>FAMILY ACTIVITY</small><h2>お知らせ</h2></span><button className="icon-button" onClick={onClose} aria-label="通知を閉じる"><X size={20} /></button></div><div className="alert-list">{alerts.map((alert) => <AlertRow alert={alert} now={now} key={alert.id} />)}</div><p className="drawer-footnote"><ShieldCheck size={15} />通知には位置座標を含めず、ログイン後に安全に取得します。</p></aside></div>;
}

function DashboardSkeleton() {
  return <main className="dashboard-shell skeleton-shell" aria-label="ダッシュボードを読み込み中"><aside className="sidebar"><div className="skeleton brand-skeleton" /><div className="skeleton title-skeleton" /><div className="skeleton card-skeleton" /><div className="skeleton card-skeleton" /></aside><section className="workspace"><div className="skeleton map-skeleton" /></section></main>;
}

function relativeTime(value: string, now: number) {
  const minutes = Math.max(0, Math.floor((now - new Date(value).getTime()) / 60_000));
  if (minutes < 1) return "たった今";
  if (minutes < 60) return `${minutes}分前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}時間前`;
  return `${Math.floor(hours / 24)}日前`;
}

function formatTime(value: string) { return new Intl.DateTimeFormat("ja-JP", { hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }
