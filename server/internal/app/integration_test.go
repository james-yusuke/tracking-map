package app

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo/options"
)

func TestMongoAPIFlow(t *testing.T) {
	mongoURI := os.Getenv("MONGO_TEST_URI")
	if mongoURI == "" {
		t.Skip("MONGO_TEST_URI is not set; run with the Compose test profile")
	}
	database := fmt.Sprintf("family-orbit-test-%d", time.Now().UnixNano())
	cfg := Config{
		Mode: "api", Port: "0", MongoURI: mongoURI, DatabaseName: database,
		JWTSecret:      []byte("integration-secret-with-at-least-32-characters"),
		AccessTokenTTL: 15 * time.Minute, RefreshTokenTTL: 24 * time.Hour,
		WebOrigins: []string{"http://localhost:3000"}, WebOrigin: "http://localhost:3000",
		SMTPHost: "127.0.0.1", SMTPPort: "1", SMTPFrom: "noreply@example.jp",
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := NewStore(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = store.DB.Drop(context.Background())
		_ = store.Close(context.Background())
	}()
	app := &App{cfg: cfg, store: store, hub: NewHub()}
	server := httptest.NewServer(app.Router())
	defer server.Close()

	register := callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": "parent@example.jp", "password": "correct horse battery staple",
		"displayName": "保護者", "familyName": "テスト家族", "clientType": "parent_android",
	}, http.StatusCreated)
	accessToken := stringField(t, register, "accessToken")
	refreshToken := stringField(t, register, "refreshToken")
	if refreshToken == "" {
		t.Fatal("mobile login did not issue a refresh token")
	}
	refreshed := callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"token": refreshToken}, http.StatusOK)
	accessToken = stringField(t, refreshed, "accessToken")
	rotatedRefreshToken := stringField(t, refreshed, "refreshToken")
	if rotatedRefreshToken == "" || rotatedRefreshToken == refreshToken {
		t.Fatal("mobile refresh token was not rotated")
	}
	callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"token": refreshToken}, http.StatusUnauthorized)
	callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": "parent@example.jp", "password": "wrong password", "clientType": "parent_android",
	}, http.StatusUnauthorized)

	webUser := User{ID: primitive.NewObjectID(), Email: "web@example.jp"}
	webToken, _ := app.signAccessToken(webUser, "web")
	readOnly := callAPI(t, server.URL, http.MethodPost, "/api/v1/children", "Bearer "+webToken, map[string]any{"name": "拒否"}, http.StatusForbidden)
	if stringField(t, readOnly, "code") != "web_read_only" {
		t.Fatalf("unexpected read-only error: %#v", readOnly)
	}

	child := callAPI(t, server.URL, http.MethodPost, "/api/v1/children", "Bearer "+accessToken, map[string]any{"name": "はる", "color": "#C9FF4A"}, http.StatusCreated)
	childID := stringField(t, child, "id")
	webDelete := callAPI(t, server.URL, http.MethodDelete, "/api/v1/children/"+childID, "Bearer "+webToken, nil, http.StatusForbidden)
	if stringField(t, webDelete, "code") != "web_read_only" {
		t.Fatalf("unexpected child deletion read-only error: %#v", webDelete)
	}
	pairing := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/pairing-code", "Bearer "+accessToken, map[string]any{"pauseRestricted": false}, http.StatusCreated)
	code := stringField(t, pairing, "code")
	paired := callAPI(t, server.URL, http.MethodPost, "/api/v1/pairing", "", map[string]any{"code": code, "deviceName": "Test Link", "platform": "android"}, http.StatusCreated)
	deviceToken := stringField(t, paired, "deviceToken")
	callAPI(t, server.URL, http.MethodPost, "/api/v1/pairing", "", map[string]any{"code": code, "deviceName": "Second Link", "platform": "ios"}, http.StatusBadRequest)

	membership, _ := primitive.ObjectIDFromHex(childID)
	var childDocument Child
	if err := store.Children.FindOne(ctx, bson.M{"_id": membership}).Decode(&childDocument); err != nil {
		t.Fatal(err)
	}
	_, err = store.PairingTokens.InsertOne(ctx, PairingToken{FamilyID: childDocument.FamilyID, ChildID: childDocument.ID, CodeHash: hashToken("999999"), ExpiresAt: time.Now().Add(-time.Minute)})
	if err != nil {
		t.Fatal(err)
	}
	callAPI(t, server.URL, http.MethodPost, "/api/v1/pairing", "", map[string]any{"code": "999999", "deviceName": "Expired", "platform": "ios"}, http.StatusBadRequest)

	callAPI(t, server.URL, http.MethodPost, "/api/v1/zones", "Bearer "+accessToken, map[string]any{
		"name": "自宅", "latitude": 35.0, "longitude": 139.0, "radiusMeters": 200,
		"color": "#72E8C0", "childIds": []string{childID},
	}, http.StatusCreated)

	now := time.Now().UTC()
	sendLocation := func(key string, at time.Time, latitude float64, expected int) map[string]any {
		return callAPI(t, server.URL, http.MethodPost, "/api/v1/device/locations", "Device "+deviceToken, map[string]any{
			"idempotencyKey": key, "trackingState": "active", "samples": []map[string]any{{
				"recordedAt": at.Format(time.RFC3339Nano), "latitude": latitude, "longitude": 139.0,
				"accuracy": 15, "batteryLevel": 0.72, "isCharging": false,
			}},
		}, expected)
	}
	sendLocation("outside-1", now, 35.01, http.StatusAccepted)
	duplicate := sendLocation("outside-1", now, 35.01, http.StatusAccepted)
	if duplicate["duplicate"] != true {
		t.Fatalf("duplicate batch was not detected: %#v", duplicate)
	}
	if count, _ := store.LocationPoints.CountDocuments(ctx, bson.M{}); count != 1 {
		t.Fatalf("idempotency failed: %d location points", count)
	}
	sendLocation("inside-1", now.Add(time.Minute), 35.0001, http.StatusAccepted)
	sendLocation("inside-2", now.Add(2*time.Minute), 35.0002, http.StatusAccepted)
	if count, _ := store.Alerts.CountDocuments(ctx, bson.M{"type": "zone_entered"}); count != 1 {
		t.Fatalf("zone enter alert was not deduplicated: %d", count)
	}
	sendLocation("outside-2", now.Add(3*time.Minute), 35.01, http.StatusAccepted)
	if count, _ := store.Alerts.CountDocuments(ctx, bson.M{"type": "zone_exited"}); count != 1 {
		t.Fatalf("zone exit alert count: %d", count)
	}
	dashboard := callAPI(t, server.URL, http.MethodGet, "/api/v1/dashboard", "Bearer "+accessToken, nil, http.StatusOK)
	dashboardChildren, ok := dashboard["children"].([]any)
	if !ok || len(dashboardChildren) != 1 {
		t.Fatalf("dashboard children missing after location upload: %#v", dashboard)
	}
	dashboardChild, _ := dashboardChildren[0].(map[string]any)
	latestLocation, _ := dashboardChild["latestLocation"].(map[string]any)
	if latestLocation == nil || latestLocation["latitude"] != 35.01 || latestLocation["longitude"] != 139.0 {
		t.Fatalf("dashboard latest location was not refreshed: %#v", dashboardChild)
	}
	historyDays := callAPI(t, server.URL, http.MethodGet, "/api/v1/children/"+childID+"/history-days", "Bearer "+accessToken, nil, http.StatusOK)
	dayItems, _ := historyDays["days"].([]any)
	if len(dayItems) == 0 {
		t.Fatalf("history day aggregation returned no days: %#v", historyDays)
	}

	webMessage := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+webToken, map[string]any{"clientMessageId": "web-message", "body": "拒否"}, http.StatusForbidden)
	if stringField(t, webMessage, "code") != "web_read_only" {
		t.Fatalf("unexpected web message error: %#v", webMessage)
	}
	message := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+accessToken, map[string]any{"clientMessageId": "message-1", "body": "気をつけて帰ってきてね"}, http.StatusCreated)
	messageID := stringField(t, message, "id")
	duplicateMessage := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+accessToken, map[string]any{"clientMessageId": "message-1", "body": "気をつけて帰ってきてね"}, http.StatusOK)
	if duplicateMessage["duplicate"] != true || stringField(t, duplicateMessage, "id") != messageID {
		t.Fatalf("message idempotency failed: %#v", duplicateMessage)
	}
	callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+accessToken, map[string]any{"clientMessageId": "too-long", "body": strings.Repeat("あ", 201)}, http.StatusBadRequest)
	deviceMessages := callAPI(t, server.URL, http.MethodGet, "/api/v1/device/messages", "Device "+deviceToken, nil, http.StatusOK)
	messageItems, _ := deviceMessages["messages"].([]any)
	if len(messageItems) != 1 {
		t.Fatalf("device did not receive its message: %#v", deviceMessages)
	}
	if err := app.processNextMessage(ctx); err == nil {
		t.Fatal("message without a registered push token was incorrectly marked as sent")
	}
	var failedMessage GuardianMessage
	messageObjectID, _ := primitive.ObjectIDFromHex(messageID)
	if err := store.Messages.FindOne(ctx, bson.M{"_id": messageObjectID}).Decode(&failedMessage); err != nil || failedMessage.DeliveryState != "failed" {
		t.Fatalf("message retry state was not persisted: state=%s error=%v", failedMessage.DeliveryState, err)
	}
	read := callAPI(t, server.URL, http.MethodPost, "/api/v1/device/messages/"+messageID+"/read", "Device "+deviceToken, map[string]any{}, http.StatusOK)
	if stringField(t, read, "deliveryState") != "read" || read["readAt"] == nil {
		t.Fatalf("message read receipt missing: %#v", read)
	}
	repeatedRead := callAPI(t, server.URL, http.MethodPost, "/api/v1/device/messages/"+messageID+"/read", "Device "+deviceToken, map[string]any{}, http.StatusOK)
	if repeatedRead["readAt"] != read["readAt"] {
		t.Fatalf("repeated read changed the original receipt time: first=%#v repeated=%#v", read, repeatedRead)
	}

	restrictedChild := callAPI(t, server.URL, http.MethodPost, "/api/v1/children", "Bearer "+accessToken, map[string]any{"name": "停止制限あり", "color": "#C9FF4A"}, http.StatusCreated)
	restrictedChildID := stringField(t, restrictedChild, "id")
	restrictedPairing := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+restrictedChildID+"/pairing-code", "Bearer "+accessToken, map[string]any{"pauseRestricted": true}, http.StatusCreated)
	if restrictedPairing["pauseRestricted"] != true {
		t.Fatalf("pairing response omitted pause restriction: %#v", restrictedPairing)
	}
	restrictedDevice := callAPI(t, server.URL, http.MethodPost, "/api/v1/pairing", "", map[string]any{"code": stringField(t, restrictedPairing, "code"), "deviceName": "Restricted Link", "platform": "ios"}, http.StatusCreated)
	if restrictedDevice["pauseRestricted"] != true {
		t.Fatalf("paired device did not inherit pause restriction: %#v", restrictedDevice)
	}
	restrictedToken := stringField(t, restrictedDevice, "deviceToken")
	deviceConfig := callAPI(t, server.URL, http.MethodGet, "/api/v1/device/config", "Device "+restrictedToken, nil, http.StatusOK)
	if deviceConfig["pauseRestricted"] != true {
		t.Fatalf("device config omitted pause restriction: %#v", deviceConfig)
	}
	paused := callAPI(t, server.URL, http.MethodPost, "/api/v1/device/tracking-state", "Device "+restrictedToken, map[string]any{"state": "paused", "reason": "paused_by_child"}, http.StatusForbidden)
	if stringField(t, paused, "code") != "tracking_pause_restricted" {
		t.Fatalf("restricted pause returned an unexpected error: %#v", paused)
	}
	callAPI(t, server.URL, http.MethodPost, "/api/v1/device/tracking-state", "Device "+restrictedToken, map[string]any{"state": "permission_denied", "reason": "location_permission_revoked"}, http.StatusOK)
	unpairRestricted := callAPI(t, server.URL, http.MethodDelete, "/api/v1/device", "Device "+restrictedToken, nil, http.StatusForbidden)
	if stringField(t, unpairRestricted, "code") != "device_unpair_restricted" {
		t.Fatalf("restricted unpair returned an unexpected error: %#v", unpairRestricted)
	}
	callAPI(t, server.URL, http.MethodDelete, "/api/v1/children/"+restrictedChildID, "Bearer "+accessToken, nil, http.StatusNoContent)

	callAPI(t, server.URL, http.MethodDelete, "/api/v1/device", "Device "+deviceToken, nil, http.StatusNoContent)
	callAPI(t, server.URL, http.MethodGet, "/api/v1/device/messages", "Device "+deviceToken, nil, http.StatusUnauthorized)
	unpairedMessage := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+accessToken, map[string]any{"clientMessageId": "after-unpair", "body": "届かないメッセージ"}, http.StatusConflict)
	if stringField(t, unpairedMessage, "code") != "child_not_paired" {
		t.Fatalf("unpaired child accepted a message: %#v", unpairedMessage)
	}
	second := callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email": "other@example.jp", "password": "correct horse battery staple", "displayName": "別の保護者", "familyName": "別の家族", "clientType": "parent_ios",
	}, http.StatusCreated)
	callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+childID+"/messages", "Bearer "+stringField(t, second, "accessToken"), map[string]any{"clientMessageId": "cross-family", "body": "拒否"}, http.StatusNotFound)
	callAPI(t, server.URL, http.MethodDelete, "/api/v1/account", "Bearer "+stringField(t, second, "accessToken"), nil, http.StatusNoContent)

	indexes, err := store.LocationPoints.Indexes().List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer indexes.Close(ctx)
	foundTTL := false
	for indexes.Next(ctx) {
		var index bson.M
		if err := indexes.Decode(&index); err != nil {
			t.Fatal(err)
		}
		if index["name"] == "expiresAt_1" && index["expireAfterSeconds"] != nil {
			foundTTL = true
		}
	}
	if !foundTTL {
		t.Fatal("30-day location TTL index is missing")
	}
	messageIndexes, err := store.Messages.Indexes().List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer messageIndexes.Close(ctx)
	foundMessageTTL := false
	for messageIndexes.Next(ctx) {
		var index bson.M
		if err := messageIndexes.Decode(&index); err != nil {
			t.Fatal(err)
		}
		if index["name"] == "expiresAt_1" && index["expireAfterSeconds"] != nil {
			foundMessageTTL = true
		}
	}
	if !foundMessageTTL {
		t.Fatal("30-day message TTL index is missing")
	}
	if remaining := time.Until(failedMessage.ExpiresAt); remaining < 29*24*time.Hour || remaining > 31*24*time.Hour {
		t.Fatalf("message expiry is not 30 days: %s", remaining)
	}

	mistake := callAPI(t, server.URL, http.MethodPost, "/api/v1/children", "Bearer "+accessToken, map[string]any{"name": "誤登録"}, http.StatusCreated)
	mistakeID := stringField(t, mistake, "id")
	mistakePairing := callAPI(t, server.URL, http.MethodPost, "/api/v1/children/"+mistakeID+"/pairing-code", "Bearer "+accessToken, map[string]any{}, http.StatusCreated)
	mistakeDevice := callAPI(t, server.URL, http.MethodPost, "/api/v1/pairing", "", map[string]any{"code": stringField(t, mistakePairing, "code"), "deviceName": "Deleted Link", "platform": "ios"}, http.StatusCreated)
	callAPI(t, server.URL, http.MethodDelete, "/api/v1/children/"+mistakeID, "Bearer "+accessToken, nil, http.StatusNoContent)
	callAPI(t, server.URL, http.MethodGet, "/api/v1/device/messages", "Device "+stringField(t, mistakeDevice, "deviceToken"), nil, http.StatusUnauthorized)
	mistakeObjectID, _ := primitive.ObjectIDFromHex(mistakeID)
	if count, _ := store.Children.CountDocuments(ctx, bson.M{"_id": mistakeObjectID}); count != 0 {
		t.Fatalf("deleted child profile remains: %d", count)
	}
	if count, _ := store.PairingTokens.CountDocuments(ctx, bson.M{"childId": mistakeObjectID}); count != 0 {
		t.Fatalf("deleted child pairing code remains: %d", count)
	}
	notFound := callAPI(t, server.URL, http.MethodDelete, "/api/v1/children/"+mistakeID, "Bearer "+accessToken, nil, http.StatusNotFound)
	if stringField(t, notFound, "code") != "child_not_found" {
		t.Fatalf("unexpected repeated deletion response: %#v", notFound)
	}

	callAPI(t, server.URL, http.MethodDelete, "/api/v1/account", "Bearer "+accessToken, nil, http.StatusNoContent)
	for name, collection := range map[string]interface {
		CountDocuments(context.Context, interface{}, ...*options.CountOptions) (int64, error)
	}{
		"users": store.Users, "families": store.Families, "children": store.Children,
		"devices": store.Devices, "locations": store.LocationPoints, "latest": store.LatestLocations,
		"zones": store.SafetyZones, "zone states": store.ZoneStates, "alerts": store.Alerts, "messages": store.Messages,
		"sessions": store.RefreshSessions, "pairing tokens": store.PairingTokens,
	} {
		if count, countErr := collection.CountDocuments(ctx, bson.M{}); countErr != nil || count != 0 {
			t.Fatalf("account deletion left %s records: count=%d error=%v", name, count, countErr)
		}
	}
	callAPI(t, server.URL, http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email": "parent@example.jp", "password": "correct horse battery staple", "clientType": "parent_android",
	}, http.StatusUnauthorized)
}

func callAPI(t *testing.T, baseURL, method, path, authorization string, body any, expected int) map[string]any {
	t.Helper()
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, baseURL+path, reader)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if authorization != "" {
		request.Header.Set("Authorization", authorization)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(response.Body)
	if response.StatusCode != expected {
		t.Fatalf("%s %s returned %d, want %d: %s", method, path, response.StatusCode, expected, data)
	}
	if len(data) == 0 {
		return map[string]any{}
	}
	var result map[string]any
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatalf("decode %s: %v", data, err)
	}
	return result
}

func stringField(t *testing.T, value map[string]any, key string) string {
	t.Helper()
	result, ok := value[key].(string)
	if !ok {
		t.Fatalf("field %q is not a string in %#v", key, value)
	}
	return result
}
