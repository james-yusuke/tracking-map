package app

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

func (a *App) firstMembership(ctx context.Context, userID primitive.ObjectID) (Membership, error) {
	var membership Membership
	err := a.store.Memberships.FindOne(ctx, bson.M{"userId": userID}).Decode(&membership)
	return membership, err
}

func (a *App) handleDashboard(w http.ResponseWriter, r *http.Request) {
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	var family Family
	if err := a.store.Families.FindOne(r.Context(), bson.M{"_id": membership.FamilyID}).Decode(&family); err != nil {
		a.serverError(w, err)
		return
	}
	children, err := findAll[Child](r.Context(), a.store.Children, bson.M{"familyId": membership.FamilyID}, options.Find().SetSort(bson.D{{Key: "createdAt", Value: 1}}))
	if err != nil {
		a.serverError(w, err)
		return
	}
	locations, _ := findAll[LatestLocation](r.Context(), a.store.LatestLocations, bson.M{"familyId": membership.FamilyID})
	devices, _ := findAll[Device](r.Context(), a.store.Devices, bson.M{"familyId": membership.FamilyID, "kind": "child", "revokedAt": bson.M{"$exists": false}})
	zones, _ := findAll[SafetyZone](r.Context(), a.store.SafetyZones, bson.M{"familyId": membership.FamilyID, "enabled": true})
	alerts, _ := findAll[Alert](r.Context(), a.store.Alerts, bson.M{"familyId": membership.FamilyID}, options.Find().SetSort(bson.D{{Key: "occurredAt", Value: -1}}).SetLimit(30))

	locationByChild := map[primitive.ObjectID]LatestLocation{}
	for _, location := range locations {
		locationByChild[location.ChildID] = location
	}
	deviceByChild := map[primitive.ObjectID]Device{}
	for _, device := range devices {
		if device.ChildID != nil {
			deviceByChild[*device.ChildID] = device
		}
	}
	type childResponse struct {
		ID             string `json:"id"`
		Name           string `json:"name"`
		Color          string `json:"color"`
		TrackingState  string `json:"trackingState"`
		Connectivity   string `json:"connectivity"`
		Device         any    `json:"device"`
		LatestLocation any    `json:"latestLocation"`
	}
	childItems := make([]childResponse, 0, len(children))
	for _, child := range children {
		device, hasDevice := deviceByChild[child.ID]
		location, hasLocation := locationByChild[child.ID]
		connectivity := "offline"
		if hasDevice && device.LastSeenAt != nil && time.Since(*device.LastSeenAt) < 15*time.Minute {
			connectivity = "online"
		}
		var deviceValue any
		if hasDevice {
			deviceValue = map[string]any{"id": device.ID.Hex(), "name": device.Name, "platform": device.Platform, "lastSeenAt": device.LastSeenAt}
		}
		var locationValue any
		if hasLocation && len(location.Point.Coordinates) == 2 {
			locationValue = map[string]any{"latitude": location.Point.Coordinates[1], "longitude": location.Point.Coordinates[0], "accuracy": location.Accuracy, "recordedAt": location.RecordedAt, "batteryLevel": location.BatteryLevel, "isCharging": location.IsCharging}
		}
		state := "paused"
		if hasDevice {
			state = device.TrackingState
		}
		childItems = append(childItems, childResponse{ID: child.ID.Hex(), Name: child.Name, Color: child.Color, TrackingState: state, Connectivity: connectivity, Device: deviceValue, LatestLocation: locationValue})
	}
	zoneItems := make([]map[string]any, 0, len(zones))
	for _, zone := range zones {
		if len(zone.Center.Coordinates) != 2 {
			continue
		}
		childIDs := make([]string, 0, len(zone.ChildIDs))
		for _, id := range zone.ChildIDs {
			childIDs = append(childIDs, id.Hex())
		}
		zoneItems = append(zoneItems, map[string]any{"id": zone.ID.Hex(), "name": zone.Name, "latitude": zone.Center.Coordinates[1], "longitude": zone.Center.Coordinates[0], "radiusMeters": zone.RadiusMeters, "color": zone.Color, "childIds": childIDs})
	}
	alertItems := make([]map[string]any, 0, len(alerts))
	for _, alert := range alerts {
		item := map[string]any{"id": alert.ID.Hex(), "type": alert.Type, "title": alert.Title, "message": alert.Message, "occurredAt": alert.OccurredAt}
		if alert.ChildID != nil {
			item["childId"] = alert.ChildID.Hex()
		}
		alertItems = append(alertItems, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"family":   map[string]any{"id": family.ID.Hex(), "name": family.Name, "role": membership.Role},
		"children": childItems, "zones": zoneItems, "alerts": alertItems, "generatedAt": time.Now().UTC(),
	})
}

func (a *App) handleCreateChild(w http.ResponseWriter, r *http.Request) {
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	var input struct {
		Name  string `json:"name"`
		Color string `json:"color"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if input.Name == "" || len([]rune(input.Name)) > 60 {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "名前を入力してください")
		return
	}
	if input.Color == "" {
		input.Color = "#C9F45B"
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	now := time.Now().UTC()
	child := Child{FamilyID: membership.FamilyID, Name: input.Name, Color: input.Color, AvatarSeed: "orbit", CreatedAt: now, UpdatedAt: now}
	result, err := a.store.Children.InsertOne(r.Context(), child)
	if err != nil {
		a.serverError(w, err)
		return
	}
	child.ID = result.InsertedID.(primitive.ObjectID)
	a.audit(r.Context(), membership.FamilyID, &userID, "child.created", child.ID.Hex())
	writeJSON(w, http.StatusCreated, child)
}

func (a *App) handleDeleteChild(w http.ResponseWriter, r *http.Request) {
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil || membership.Role != "owner" {
		writeAPIError(w, http.StatusForbidden, "family_owner_required", "家族の所有者だけが子どもプロフィールを削除できます")
		return
	}
	childID, err := primitive.ObjectIDFromHex(chi.URLParam(r, "childID"))
	if err != nil || a.store.Children.FindOne(r.Context(), bson.M{"_id": childID, "familyId": membership.FamilyID}).Err() != nil {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}

	// Revoke paired devices first so a deleted profile cannot upload another
	// location while the remaining data is being removed.
	devices, err := findAll[Device](r.Context(), a.store.Devices, bson.M{"familyId": membership.FamilyID, "childId": childID})
	if err != nil {
		a.serverError(w, err)
		return
	}
	deviceIDs := make([]primitive.ObjectID, 0, len(devices))
	for _, device := range devices {
		deviceIDs = append(deviceIDs, device.ID)
	}

	deletions := []struct {
		collection *mongo.Collection
		filter     bson.M
	}{
		{a.store.Idempotency, bson.M{"deviceId": bson.M{"$in": deviceIDs}}},
		{a.store.LocationPoints, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.LatestLocations, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.ZoneStates, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.Alerts, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.Messages, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.PairingTokens, bson.M{"familyId": membership.FamilyID, "childId": childID}},
		{a.store.Devices, bson.M{"familyId": membership.FamilyID, "childId": childID}},
	}
	for _, deletion := range deletions {
		if _, err := deletion.collection.DeleteMany(r.Context(), deletion.filter); err != nil {
			a.serverError(w, err)
			return
		}
	}
	if _, err := a.store.SafetyZones.UpdateMany(
		r.Context(),
		bson.M{"familyId": membership.FamilyID, "childIds": childID},
		bson.M{"$pull": bson.M{"childIds": childID}, "$set": bson.M{"updatedAt": time.Now().UTC()}},
	); err != nil {
		a.serverError(w, err)
		return
	}
	result, err := a.store.Children.DeleteOne(r.Context(), bson.M{"_id": childID, "familyId": membership.FamilyID})
	if err != nil {
		a.serverError(w, err)
		return
	}
	if result.DeletedCount != 1 {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	a.audit(r.Context(), membership.FamilyID, &userID, "child.deleted", childID.Hex())
	go a.notifyFamilyRemoval(context.Background(), devices)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleCreatePairingCode(w http.ResponseWriter, r *http.Request) {
	var input struct {
		PauseRestricted bool `json:"pauseRestricted"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	childID, err := primitive.ObjectIDFromHex(chi.URLParam(r, "childID"))
	if err != nil || a.store.Children.FindOne(r.Context(), bson.M{"_id": childID, "familyId": membership.FamilyID}).Err() != nil {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	_, _ = a.store.PairingTokens.DeleteMany(r.Context(), bson.M{"childId": childID, "usedAt": bson.M{"$exists": false}})
	code, err := sixDigitCode()
	if err != nil {
		a.serverError(w, err)
		return
	}
	expiresAt := time.Now().UTC().Add(10 * time.Minute)
	_, err = a.store.PairingTokens.InsertOne(r.Context(), PairingToken{FamilyID: membership.FamilyID, ChildID: childID, CreatedBy: userID, CodeHash: hashToken(code), PauseRestricted: input.PauseRestricted, ExpiresAt: expiresAt})
	if err != nil {
		a.serverError(w, err)
		return
	}
	a.audit(r.Context(), membership.FamilyID, &userID, "pairing.created", childID.Hex())
	writeJSON(w, http.StatusCreated, map[string]any{"code": code, "expiresAt": expiresAt, "qrPayload": "familyorbit://pair?code=" + code, "pauseRestricted": input.PauseRestricted})
}

func (a *App) handlePairDevice(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Code       string `json:"code"`
		DeviceName string `json:"deviceName"`
		Platform   string `json:"platform"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	if len(input.Code) != 6 || strings.TrimSpace(input.DeviceName) == "" || (input.Platform != "android" && input.Platform != "ios") {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "ペアリング情報を確認してください")
		return
	}
	var pairing PairingToken
	err := a.store.PairingTokens.FindOne(r.Context(), bson.M{"codeHash": hashToken(input.Code), "usedAt": bson.M{"$exists": false}, "expiresAt": bson.M{"$gt": time.Now().UTC()}}).Decode(&pairing)
	if err != nil {
		writeAPIError(w, http.StatusBadRequest, "invalid_pairing_code", "ペアリングコードが無効か有効期限切れです")
		return
	}
	now := time.Now().UTC()
	result, err := a.store.PairingTokens.UpdateOne(r.Context(), bson.M{"_id": pairing.ID, "usedAt": bson.M{"$exists": false}}, bson.M{"$set": bson.M{"usedAt": now}})
	if err != nil || result.ModifiedCount != 1 {
		writeAPIError(w, http.StatusConflict, "pairing_code_used", "このコードはすでに使用されています")
		return
	}
	rawToken, _ := randomToken(48)
	childID := pairing.ChildID
	device := Device{FamilyID: pairing.FamilyID, ChildID: &childID, Kind: "child", Platform: input.Platform, Name: strings.TrimSpace(input.DeviceName), TokenHash: hashToken(rawToken), TrackingState: "paused", PauseRestricted: pairing.PauseRestricted, LastSeenAt: &now, CreatedAt: now, UpdatedAt: now}
	deviceResult, err := a.store.Devices.InsertOne(r.Context(), device)
	if err != nil {
		a.serverError(w, err)
		return
	}
	device.ID = deviceResult.InsertedID.(primitive.ObjectID)
	a.audit(r.Context(), pairing.FamilyID, nil, "device.paired", device.ID.Hex())
	writeJSON(w, http.StatusCreated, map[string]any{"deviceId": device.ID.Hex(), "deviceToken": rawToken, "childId": childID.Hex(), "pauseRestricted": device.PauseRestricted})
}

type zoneInput struct {
	Name         string   `json:"name"`
	Latitude     float64  `json:"latitude"`
	Longitude    float64  `json:"longitude"`
	RadiusMeters float64  `json:"radiusMeters"`
	Color        string   `json:"color"`
	ChildIDs     []string `json:"childIds"`
	Enabled      *bool    `json:"enabled,omitempty"`
}

func (a *App) handleCreateZone(w http.ResponseWriter, r *http.Request) {
	a.saveZone(w, r, primitive.NilObjectID)
}
func (a *App) handleUpdateZone(w http.ResponseWriter, r *http.Request) {
	id, err := primitive.ObjectIDFromHex(chi.URLParam(r, "zoneID"))
	if err != nil {
		writeAPIError(w, http.StatusNotFound, "zone_not_found", "安全エリアが見つかりません")
		return
	}
	a.saveZone(w, r, id)
}

func (a *App) saveZone(w http.ResponseWriter, r *http.Request, zoneID primitive.ObjectID) {
	_, userID, _ := claimsFrom(r.Context())
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	var input zoneInput
	if !decodeJSON(w, r, &input) {
		return
	}
	if strings.TrimSpace(input.Name) == "" || input.Latitude < -90 || input.Latitude > 90 || input.Longitude < -180 || input.Longitude > 180 || input.RadiusMeters < 100 || input.RadiusMeters > 5000 || len(input.ChildIDs) == 0 {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "安全エリアの入力内容を確認してください")
		return
	}
	childIDs := make([]primitive.ObjectID, 0, len(input.ChildIDs))
	for _, raw := range input.ChildIDs {
		id, err := primitive.ObjectIDFromHex(raw)
		if err != nil || a.store.Children.FindOne(r.Context(), bson.M{"_id": id, "familyId": membership.FamilyID}).Err() != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_child", "別の家族の子どもは指定できません")
			return
		}
		childIDs = append(childIDs, id)
	}
	if input.Color == "" {
		input.Color = "#7DE0D1"
	}
	now := time.Now().UTC()
	enabled := true
	if input.Enabled != nil {
		enabled = *input.Enabled
	}
	update := bson.M{"name": strings.TrimSpace(input.Name), "center": GeoPoint{Type: "Point", Coordinates: []float64{input.Longitude, input.Latitude}}, "radiusMeters": input.RadiusMeters, "color": input.Color, "childIds": childIDs, "enabled": enabled, "updatedAt": now}
	if zoneID.IsZero() {
		update["familyId"] = membership.FamilyID
		update["createdAt"] = now
		result, err := a.store.SafetyZones.InsertOne(r.Context(), update)
		if err != nil {
			a.serverError(w, err)
			return
		}
		update["_id"] = result.InsertedID
		writeJSON(w, http.StatusCreated, update)
		return
	}
	result := a.store.SafetyZones.FindOneAndUpdate(r.Context(), bson.M{"_id": zoneID, "familyId": membership.FamilyID}, bson.M{"$set": update}, options.FindOneAndUpdate().SetReturnDocument(options.After))
	var zone SafetyZone
	if result.Decode(&zone) != nil {
		writeAPIError(w, http.StatusNotFound, "zone_not_found", "安全エリアが見つかりません")
		return
	}
	writeJSON(w, http.StatusOK, zone)
}

func (a *App) handleDeleteZone(w http.ResponseWriter, r *http.Request) {
	_, userID, _ := claimsFrom(r.Context())
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	id, err := primitive.ObjectIDFromHex(chi.URLParam(r, "zoneID"))
	if err != nil {
		writeAPIError(w, http.StatusNotFound, "zone_not_found", "安全エリアが見つかりません")
		return
	}
	result, _ := a.store.SafetyZones.UpdateOne(r.Context(), bson.M{"_id": id, "familyId": membership.FamilyID}, bson.M{"$set": bson.M{"enabled": false, "updatedAt": time.Now().UTC()}})
	if result == nil || result.MatchedCount == 0 {
		writeAPIError(w, http.StatusNotFound, "zone_not_found", "安全エリアが見つかりません")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleHistory(w http.ResponseWriter, r *http.Request) {
	_, userID, _ := claimsFrom(r.Context())
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	childID, err := primitive.ObjectIDFromHex(chi.URLParam(r, "childID"))
	if err != nil || a.store.Children.FindOne(r.Context(), bson.M{"_id": childID, "familyId": membership.FamilyID}).Err() != nil {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	to := time.Now().UTC()
	from := to.Add(-24 * time.Hour)
	if parsed, err := time.Parse(time.RFC3339, r.URL.Query().Get("from")); err == nil {
		from = parsed
	}
	if parsed, err := time.Parse(time.RFC3339, r.URL.Query().Get("to")); err == nil {
		to = parsed
	}
	earliest := time.Now().UTC().Add(-30 * 24 * time.Hour)
	if from.Before(earliest) {
		from = earliest
	}
	points, err := findAll[LocationPoint](r.Context(), a.store.LocationPoints, bson.M{"childId": childID, "recordedAt": bson.M{"$gte": from, "$lte": to}}, options.Find().SetSort(bson.D{{Key: "recordedAt", Value: 1}}).SetLimit(5000))
	if err != nil {
		a.serverError(w, err)
		return
	}
	items := make([]map[string]any, 0, len(points))
	for _, point := range points {
		if len(point.Point.Coordinates) == 2 {
			items = append(items, map[string]any{"latitude": point.Point.Coordinates[1], "longitude": point.Point.Coordinates[0], "accuracy": point.Accuracy, "recordedAt": point.RecordedAt, "batteryLevel": point.BatteryLevel})
		}
	}
	writeJSON(w, http.StatusOK, items)
}

func (a *App) handleRegisterPush(w http.ResponseWriter, r *http.Request) {
	_, userID, _ := claimsFrom(r.Context())
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	var input struct {
		DeviceName string `json:"deviceName"`
		Platform   string `json:"platform"`
		PushToken  string `json:"pushToken"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	if strings.TrimSpace(input.DeviceName) == "" || strings.TrimSpace(input.PushToken) == "" || (input.Platform != "android" && input.Platform != "ios") {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "端末情報を確認してください")
		return
	}
	now := time.Now().UTC()
	update := bson.M{"familyId": membership.FamilyID, "userId": userID, "kind": "guardian", "platform": input.Platform, "name": strings.TrimSpace(input.DeviceName), "pushToken": input.PushToken, "lastSeenAt": now, "updatedAt": now}
	result := a.store.Devices.FindOneAndUpdate(r.Context(), bson.M{"userId": userID, "platform": input.Platform, "kind": "guardian", "revokedAt": bson.M{"$exists": false}}, bson.M{"$set": update, "$setOnInsert": bson.M{"createdAt": now}}, options.FindOneAndUpdate().SetUpsert(true).SetReturnDocument(options.After))
	var device Device
	if err := result.Decode(&device); err != nil {
		a.serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, device)
}

func (a *App) handleDeleteAccount(w http.ResponseWriter, r *http.Request) {
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil || membership.Role != "owner" {
		writeAPIError(w, http.StatusForbidden, "family_owner_required", "家族の所有者だけがアカウントを削除できます")
		return
	}

	// Disable authentication and tracking first. The remaining records are then
	// synchronously removed, which satisfies the 24-hour deletion promise without
	// retaining a recoverable soft-deleted copy of location data.
	now := time.Now().UTC()
	if _, err = a.store.Users.UpdateByID(r.Context(), userID, bson.M{"$set": bson.M{"deletedAt": now, "updatedAt": now}}); err != nil {
		a.serverError(w, err)
		return
	}
	devices, err := findAll[Device](r.Context(), a.store.Devices, bson.M{"familyId": membership.FamilyID})
	if err != nil {
		a.serverError(w, err)
		return
	}
	deviceIDs := make([]primitive.ObjectID, 0, len(devices))
	for _, device := range devices {
		deviceIDs = append(deviceIDs, device.ID)
	}

	deletions := []struct {
		collection *mongo.Collection
		filter     bson.M
	}{
		{a.store.RefreshSessions, bson.M{"userId": userID}},
		{a.store.OneTimeTokens, bson.M{"userId": userID}},
		{a.store.Idempotency, bson.M{"deviceId": bson.M{"$in": deviceIDs}}},
		{a.store.LocationPoints, bson.M{"familyId": membership.FamilyID}},
		{a.store.LatestLocations, bson.M{"familyId": membership.FamilyID}},
		{a.store.ZoneStates, bson.M{"familyId": membership.FamilyID}},
		{a.store.Alerts, bson.M{"familyId": membership.FamilyID}},
		{a.store.Messages, bson.M{"familyId": membership.FamilyID}},
		{a.store.PairingTokens, bson.M{"familyId": membership.FamilyID}},
		{a.store.SafetyZones, bson.M{"familyId": membership.FamilyID}},
		{a.store.Devices, bson.M{"familyId": membership.FamilyID}},
		{a.store.Children, bson.M{"familyId": membership.FamilyID}},
		{a.store.AuditLogs, bson.M{"familyId": membership.FamilyID}},
		{a.store.Memberships, bson.M{"familyId": membership.FamilyID}},
		{a.store.Families, bson.M{"_id": membership.FamilyID}},
		{a.store.Users, bson.M{"_id": userID}},
	}
	for _, deletion := range deletions {
		if _, err := deletion.collection.DeleteMany(r.Context(), deletion.filter); err != nil {
			a.serverError(w, err)
			return
		}
	}
	go a.notifyFamilyRemoval(context.Background(), devices)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) notifyFamilyRemoval(ctx context.Context, devices []Device) {
	for _, device := range devices {
		if device.Kind != "child" || device.PushToken == "" || device.RevokedAt != nil {
			continue
		}
		pushCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_ = a.sendPushContent(pushCtx, device, device.ID.Hex(), "family_removed", "Family Orbit", "家族からこの端末が削除されました。")
		cancel()
	}
}

func (a *App) audit(ctx context.Context, familyID primitive.ObjectID, actorID *primitive.ObjectID, action, targetID string) {
	entry := bson.M{"familyId": familyID, "action": action, "targetId": targetID, "occurredAt": time.Now().UTC(), "expiresAt": time.Now().UTC().Add(90 * 24 * time.Hour)}
	if actorID != nil {
		entry["actorId"] = *actorID
	}
	_, _ = a.store.AuditLogs.InsertOne(ctx, entry)
}

func findAll[T any](ctx context.Context, collection *mongo.Collection, filter any, opts ...*options.FindOptions) ([]T, error) {
	cursor, err := collection.Find(ctx, filter, opts...)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)
	var items []T
	err = cursor.All(ctx, &items)
	return items, err
}
