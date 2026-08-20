package app

import (
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	jwtv5 "github.com/golang-jwt/jwt/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"golang.org/x/oauth2/google"
)

func (a *App) RunWorker(ctx context.Context) error {
	slog.Info("Family Orbit notification worker started")
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			a.createOfflineAlerts(ctx)
			if err := a.processNextAlert(ctx); err != nil {
				slog.Warn("notification delivery failed", "error", err)
			}
			if err := a.processNextMessage(ctx); err != nil {
				slog.Warn("message delivery failed", "error", err)
			}
		}
	}
}

func (a *App) processNextAlert(ctx context.Context) error {
	now := time.Now().UTC()
	result := a.store.Alerts.FindOneAndUpdate(ctx,
		bson.M{"deliveryState": bson.M{"$in": bson.A{"pending", "failed"}}, "nextAttemptAt": bson.M{"$lte": now}},
		bson.M{"$set": bson.M{"deliveryState": "processing"}, "$inc": bson.M{"attemptCount": 1}},
		options.FindOneAndUpdate().SetSort(bson.D{{Key: "occurredAt", Value: 1}}).SetReturnDocument(options.After),
	)
	var alert Alert
	if err := result.Decode(&alert); errors.Is(err, mongo.ErrNoDocuments) {
		return nil
	} else if err != nil {
		return err
	}
	devices, err := findAll[Device](ctx, a.store.Devices, bson.M{"familyId": alert.FamilyID, "kind": "guardian", "revokedAt": bson.M{"$exists": false}, "pushToken": bson.M{"$exists": true, "$ne": ""}})
	if err == nil && len(devices) == 0 {
		err = errors.New("guardian push token is not registered")
	}
	if err == nil {
		for _, device := range devices {
			if err = a.sendPush(ctx, device, alert); err != nil {
				break
			}
		}
	}
	if err != nil {
		delay := time.Duration(1<<min(alert.AttemptCount, 6)) * 30 * time.Second
		_, _ = a.store.Alerts.UpdateByID(ctx, alert.ID, bson.M{"$set": bson.M{"deliveryState": "failed", "nextAttemptAt": time.Now().UTC().Add(delay)}})
		return err
	}
	deliveredAt := time.Now().UTC()
	_, err = a.store.Alerts.UpdateByID(ctx, alert.ID, bson.M{"$set": bson.M{"deliveryState": "sent", "deliveredAt": deliveredAt}})
	return err
}

func (a *App) sendPush(ctx context.Context, device Device, alert Alert) error {
	return a.sendPushContent(ctx, device, alert.ID.Hex(), alert.Type, "Family Orbit", "家族の状態が更新されました。アプリで確認してください。")
}

func (a *App) sendPushContent(ctx context.Context, device Device, itemID, itemType, title, body string) error {
	if device.Platform == "android" {
		return a.sendFCM(ctx, device.PushToken, itemID, itemType, title, body)
	}
	if device.Platform == "ios" {
		return a.sendAPNS(ctx, device, itemID, itemType, title, body)
	}
	return errors.New("unsupported push platform")
}

func (a *App) sendFCM(ctx context.Context, pushToken, itemID, itemType, title, messageBody string) error {
	if a.cfg.FCMProjectID == "" || a.cfg.FCMClientEmail == "" || a.cfg.FCMPrivateKey == "" {
		return errors.New("FCM is not configured")
	}
	credentials := map[string]string{"type": "service_account", "client_email": a.cfg.FCMClientEmail, "private_key": a.cfg.FCMPrivateKey, "token_uri": "https://oauth2.googleapis.com/token"}
	encoded, _ := json.Marshal(credentials)
	config, err := google.JWTConfigFromJSON(encoded, "https://www.googleapis.com/auth/firebase.messaging")
	if err != nil {
		return err
	}
	client := config.Client(ctx)
	data := map[string]string{"type": itemType}
	if itemType == "parent_message" {
		data["messageId"] = itemID
	} else {
		data["itemId"] = itemID
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token": pushToken,
		"notification": map[string]string{
			"title": title,
			"body":  messageBody,
		},
		"data": data,
	}})
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", a.cfg.FCMProjectID), strings.NewReader(string(body)))
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("FCM returned %s", response.Status)
	}
	return nil
}

func (a *App) sendAPNS(ctx context.Context, device Device, itemID, itemType, title, messageBody string) error {
	bundleID := a.cfg.APNSParentBundleID
	if device.Kind == "child" {
		bundleID = a.cfg.APNSLinkBundleID
	}
	if a.cfg.APNSKeyID == "" || a.cfg.APNSTeamID == "" || bundleID == "" || a.cfg.APNSPrivateKey == "" {
		return errors.New("APNs is not configured")
	}
	privateKey, err := parseAPNSPrivateKey(a.cfg.APNSPrivateKey)
	if err != nil {
		return err
	}
	claims := jwtv5.MapClaims{"iss": a.cfg.APNSTeamID, "iat": time.Now().Unix()}
	token := jwtv5.NewWithClaims(jwtv5.SigningMethodES256, claims)
	token.Header["kid"] = a.cfg.APNSKeyID
	bearer, err := token.SignedString(privateKey)
	if err != nil {
		return err
	}
	host := "https://api.push.apple.com"
	if a.cfg.APNSEnvironment == "sandbox" {
		host = "https://api.sandbox.push.apple.com"
	}
	payload := map[string]any{
		"aps": map[string]any{
			"alert": map[string]string{
				"title": title,
				"body":  messageBody,
			},
			"sound": "default",
		},
		"type": itemType,
	}
	if itemType == "parent_message" {
		payload["messageId"] = itemID
	} else {
		payload["itemId"] = itemID
	}
	body, _ := json.Marshal(payload)
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, host+"/3/device/"+device.PushToken, strings.NewReader(string(body)))
	request.Header.Set("Authorization", "bearer "+bearer)
	request.Header.Set("apns-topic", bundleID)
	request.Header.Set("apns-push-type", "alert")
	request.Header.Set("apns-priority", "10")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("APNs returned %s", response.Status)
	}
	return nil
}

func (a *App) processNextMessage(ctx context.Context) error {
	now := time.Now().UTC()
	result := a.store.Messages.FindOneAndUpdate(ctx,
		bson.M{"deliveryState": bson.M{"$in": bson.A{"queued", "failed"}}, "nextAttemptAt": bson.M{"$lte": now}},
		bson.M{"$set": bson.M{"deliveryState": "processing"}, "$inc": bson.M{"attemptCount": 1}},
		options.FindOneAndUpdate().SetSort(bson.D{{Key: "createdAt", Value: 1}}).SetReturnDocument(options.After),
	)
	var message GuardianMessage
	if err := result.Decode(&message); errors.Is(err, mongo.ErrNoDocuments) {
		return nil
	} else if err != nil {
		return err
	}
	devices, err := findAll[Device](ctx, a.store.Devices, bson.M{
		"familyId": message.FamilyID, "childId": message.ChildID, "kind": "child",
		"revokedAt": bson.M{"$exists": false}, "pushToken": bson.M{"$exists": true, "$ne": ""},
	})
	if err == nil && len(devices) == 0 {
		err = errors.New("child push token is not registered")
	}
	if err == nil {
		for _, device := range devices {
			if err = a.sendPushContent(ctx, device, message.ID.Hex(), "parent_message", "Family Orbitからメッセージ", message.Body); err != nil {
				break
			}
		}
	}
	if err != nil {
		delay := time.Duration(1<<min(message.AttemptCount, 6)) * 30 * time.Second
		_, _ = a.store.Messages.UpdateByID(ctx, message.ID, bson.M{"$set": bson.M{"deliveryState": "failed", "nextAttemptAt": time.Now().UTC().Add(delay)}})
		return err
	}
	pushedAt := time.Now().UTC()
	update, err := a.store.Messages.UpdateOne(ctx, bson.M{"_id": message.ID, "readAt": bson.M{"$exists": false}}, bson.M{"$set": bson.M{"deliveryState": "pushed", "pushedAt": pushedAt}})
	if err == nil && update.ModifiedCount == 1 {
		message.DeliveryState = "pushed"
		message.PushedAt = &pushedAt
		a.hub.Emit(message.FamilyID, "message.pushed", messageResponse(message))
	}
	return err
}

func parseAPNSPrivateKey(value string) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(value))
	if block == nil {
		return nil, errors.New("invalid APNs private key")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ecdsaKey, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("APNs key is not ECDSA")
	}
	return ecdsaKey, nil
}

func (a *App) createOfflineAlerts(ctx context.Context) {
	cutoff := time.Now().UTC().Add(-15 * time.Minute)
	devices, err := findAll[Device](ctx, a.store.Devices, bson.M{"kind": "child", "trackingState": "active", "lastSeenAt": bson.M{"$lt": cutoff}, "revokedAt": bson.M{"$exists": false}})
	if err != nil {
		return
	}
	for _, device := range devices {
		if device.ChildID == nil || device.LastSeenAt == nil {
			continue
		}
		count, _ := a.store.Alerts.CountDocuments(ctx, bson.M{"childId": *device.ChildID, "type": "device_offline", "occurredAt": bson.M{"$gte": *device.LastSeenAt}})
		if count > 0 {
			continue
		}
		var child Child
		_ = a.store.Children.FindOne(ctx, bson.M{"_id": *device.ChildID}).Decode(&child)
		now := time.Now().UTC()
		alert := Alert{FamilyID: device.FamilyID, ChildID: device.ChildID, Type: "device_offline", Title: "端末がオフラインです", Message: child.Name + "の端末から15分以上更新がありません。", OccurredAt: now, DeliveryState: "pending", NextAttemptAt: now, ExpiresAt: now.Add(30 * 24 * time.Hour)}
		_, _ = a.store.Alerts.InsertOne(ctx, alert)
	}
}
