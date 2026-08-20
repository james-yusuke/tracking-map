package app

import (
	"math"
	"net/http"
	"sort"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type locationSampleInput struct {
	RecordedAt   time.Time `json:"recordedAt"`
	Latitude     float64   `json:"latitude"`
	Longitude    float64   `json:"longitude"`
	Accuracy     float64   `json:"accuracy"`
	Speed        *float64  `json:"speed,omitempty"`
	BatteryLevel float64   `json:"batteryLevel"`
	IsCharging   bool      `json:"isCharging"`
}

type locationBatchInput struct {
	IdempotencyKey string                `json:"idempotencyKey"`
	TrackingState  string                `json:"trackingState"`
	Samples        []locationSampleInput `json:"samples"`
}

func (a *App) handleDeviceZones(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	zones, err := findAll[SafetyZone](r.Context(), a.store.SafetyZones, bson.M{"familyId": device.FamilyID, "enabled": true, "childIds": *device.ChildID})
	if err != nil {
		a.serverError(w, err)
		return
	}
	items := make([]map[string]any, 0, len(zones))
	for _, zone := range zones {
		if len(zone.Center.Coordinates) != 2 {
			continue
		}
		items = append(items, map[string]any{
			"id": zone.ID.Hex(), "name": zone.Name,
			"latitude": zone.Center.Coordinates[1], "longitude": zone.Center.Coordinates[0],
			"radiusMeters": zone.RadiusMeters,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"zones": items})
}

func (a *App) handleDeviceConfig(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pauseRestricted": device.PauseRestricted,
		"trackingState":   device.TrackingState,
	})
}

func (a *App) handleLocationBatch(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	var input locationBatchInput
	if !decodeJSON(w, r, &input) {
		return
	}
	if strings.TrimSpace(input.IdempotencyKey) == "" || len(input.Samples) == 0 || len(input.Samples) > 100 || !validTrackingState(input.TrackingState) {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "位置情報の形式を確認してください")
		return
	}
	if device.PauseRestricted && input.TrackingState == "paused" {
		writeAPIError(w, http.StatusForbidden, "tracking_pause_restricted", "この端末では保護者の設定により位置共有を一時停止できません")
		return
	}
	now := time.Now().UTC()
	_, err := a.store.Idempotency.InsertOne(r.Context(), bson.M{"deviceId": device.ID, "key": input.IdempotencyKey, "expiresAt": now.Add(24 * time.Hour), "createdAt": now})
	if mongo.IsDuplicateKeyError(err) {
		writeJSON(w, http.StatusAccepted, map[string]any{"accepted": 0, "duplicate": true})
		return
	}
	if err != nil {
		a.serverError(w, err)
		return
	}
	validSamples := make([]locationSampleInput, 0, len(input.Samples))
	for _, sample := range input.Samples {
		if sample.RecordedAt.IsZero() || sample.RecordedAt.After(now.Add(5*time.Minute)) || sample.Latitude < -90 || sample.Latitude > 90 || sample.Longitude < -180 || sample.Longitude > 180 || sample.Accuracy < 0 || sample.Accuracy > 10000 || sample.BatteryLevel < 0 || sample.BatteryLevel > 1 {
			continue
		}
		validSamples = append(validSamples, sample)
	}
	if len(validSamples) == 0 {
		writeJSON(w, http.StatusAccepted, map[string]any{"accepted": 0, "duplicate": false})
		return
	}
	sort.Slice(validSamples, func(i, j int) bool { return validSamples[i].RecordedAt.Before(validSamples[j].RecordedAt) })
	expiresAt := now.Add(30 * 24 * time.Hour)
	documents := make([]any, 0, len(validSamples))
	for _, sample := range validSamples {
		documents = append(documents, LocationPoint{FamilyID: device.FamilyID, ChildID: *device.ChildID, DeviceID: device.ID, RecordedAt: sample.RecordedAt.UTC(), Point: GeoPoint{Type: "Point", Coordinates: []float64{sample.Longitude, sample.Latitude}}, Accuracy: sample.Accuracy, Speed: sample.Speed, BatteryLevel: sample.BatteryLevel, IsCharging: sample.IsCharging, ExpiresAt: expiresAt, CreatedAt: now})
	}
	if _, err := a.store.LocationPoints.InsertMany(r.Context(), documents); err != nil {
		a.serverError(w, err)
		return
	}
	last := validSamples[len(validSamples)-1]
	latest := LatestLocation{FamilyID: device.FamilyID, ChildID: *device.ChildID, DeviceID: device.ID, RecordedAt: last.RecordedAt.UTC(), Point: GeoPoint{Type: "Point", Coordinates: []float64{last.Longitude, last.Latitude}}, Accuracy: last.Accuracy, BatteryLevel: last.BatteryLevel, IsCharging: last.IsCharging, UpdatedAt: now}
	var existing LatestLocation
	err = a.store.LatestLocations.FindOne(r.Context(), bson.M{"childId": *device.ChildID}).Decode(&existing)
	if err == mongo.ErrNoDocuments || (err == nil && !existing.RecordedAt.After(last.RecordedAt)) {
		_, _ = a.store.LatestLocations.ReplaceOne(r.Context(), bson.M{"childId": *device.ChildID}, latest, options.Replace().SetUpsert(true))
	}
	_, _ = a.store.Devices.UpdateByID(r.Context(), device.ID, bson.M{"$set": bson.M{"lastSeenAt": now, "trackingState": input.TrackingState, "updatedAt": now}})
	a.evaluateZones(r, *device, last)
	a.hub.Emit(device.FamilyID, "location.updated", map[string]any{"childId": device.ChildID.Hex(), "latitude": last.Latitude, "longitude": last.Longitude, "accuracy": last.Accuracy, "batteryLevel": last.BatteryLevel, "isCharging": last.IsCharging, "recordedAt": last.RecordedAt.UTC()})
	writeJSON(w, http.StatusAccepted, map[string]any{"accepted": len(validSamples), "duplicate": false})
}

func (a *App) handleTrackingState(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	var input struct {
		State  string `json:"state"`
		Reason string `json:"reason,omitempty"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	if !validTrackingState(input.State) {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "追跡状態が正しくありません")
		return
	}
	if device.PauseRestricted && input.State == "paused" {
		writeAPIError(w, http.StatusForbidden, "tracking_pause_restricted", "この端末では保護者の設定により位置共有を一時停止できません")
		return
	}
	now := time.Now().UTC()
	_, _ = a.store.Devices.UpdateByID(r.Context(), device.ID, bson.M{"$set": bson.M{"trackingState": input.State, "lastSeenAt": now, "updatedAt": now}})
	if device.TrackingState != input.State {
		var child Child
		_ = a.store.Children.FindOne(r.Context(), bson.M{"_id": *device.ChildID}).Decode(&child)
		alertType, title := "tracking_paused", "位置共有を一時停止しました"
		if input.State == "active" {
			alertType, title = "tracking_resumed", "位置共有を再開しました"
		} else if input.State == "permission_denied" {
			alertType, title = "permission_denied", "位置情報の権限を確認してください"
		}
		a.createAlert(r, device.FamilyID, device.ChildID, nil, alertType, title, child.Name+"の端末状態が変わりました。", now)
		a.hub.Emit(device.FamilyID, "tracking.changed", map[string]any{"childId": device.ChildID.Hex(), "state": input.State, "reason": input.Reason, "occurredAt": now})
	}
	writeJSON(w, http.StatusOK, map[string]any{"state": input.State, "updatedAt": now})
}

func (a *App) evaluateZones(r *http.Request, device Device, sample locationSampleInput) {
	if device.ChildID == nil {
		return
	}
	zones, err := findAll[SafetyZone](r.Context(), a.store.SafetyZones, bson.M{"familyId": device.FamilyID, "enabled": true, "childIds": *device.ChildID})
	if err != nil {
		return
	}
	var child Child
	_ = a.store.Children.FindOne(r.Context(), bson.M{"_id": *device.ChildID}).Decode(&child)
	for _, zone := range zones {
		if len(zone.Center.Coordinates) != 2 {
			continue
		}
		distance := haversineMeters(sample.Latitude, sample.Longitude, zone.Center.Coordinates[1], zone.Center.Coordinates[0])
		nextState := "outside"
		if distance <= zone.RadiusMeters {
			nextState = "inside"
		}
		var previous ZoneState
		err := a.store.ZoneStates.FindOne(r.Context(), bson.M{"childId": *device.ChildID, "zoneId": zone.ID}).Decode(&previous)
		if err == mongo.ErrNoDocuments {
			_, _ = a.store.ZoneStates.InsertOne(r.Context(), ZoneState{FamilyID: device.FamilyID, ChildID: *device.ChildID, ZoneID: zone.ID, State: nextState, ChangedAt: sample.RecordedAt.UTC()})
			continue
		}
		if err != nil || previous.State == nextState {
			continue
		}
		_, _ = a.store.ZoneStates.UpdateByID(r.Context(), previous.ID, bson.M{"$set": bson.M{"state": nextState, "changedAt": sample.RecordedAt.UTC()}})
		event, alertType, action := "zone.exited", "zone_exited", "出発しました"
		if nextState == "inside" {
			event, alertType, action = "zone.entered", "zone_entered", "到着しました"
		}
		alertID := a.createAlert(r, device.FamilyID, device.ChildID, &zone.ID, alertType, zone.Name+"に"+action, child.Name+"が"+zone.Name+"に"+action+"。", sample.RecordedAt.UTC())
		a.hub.Emit(device.FamilyID, event, map[string]any{"alertId": alertID.Hex(), "childId": device.ChildID.Hex(), "zoneId": zone.ID.Hex(), "occurredAt": sample.RecordedAt.UTC()})
	}
}

func (a *App) createAlert(r *http.Request, familyID primitive.ObjectID, childID, zoneID *primitive.ObjectID, alertType, title, message string, occurredAt time.Time) primitive.ObjectID {
	alert := Alert{FamilyID: familyID, ChildID: childID, ZoneID: zoneID, Type: alertType, Title: title, Message: message, OccurredAt: occurredAt, DeliveryState: "pending", NextAttemptAt: time.Now().UTC(), ExpiresAt: time.Now().UTC().Add(30 * 24 * time.Hour)}
	result, err := a.store.Alerts.InsertOne(r.Context(), alert)
	if err != nil {
		return primitive.NilObjectID
	}
	return result.InsertedID.(primitive.ObjectID)
}

func validTrackingState(value string) bool {
	return value == "active" || value == "paused" || value == "permission_denied"
}

func haversineMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const earthRadius = 6_371_000.0
	toRadians := math.Pi / 180
	dLat := (lat2 - lat1) * toRadians
	dLon := (lon2 - lon1) * toRadians
	a := math.Pow(math.Sin(dLat/2), 2) + math.Cos(lat1*toRadians)*math.Cos(lat2*toRadians)*math.Pow(math.Sin(dLon/2), 2)
	return earthRadius * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
}
