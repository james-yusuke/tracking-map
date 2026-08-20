package app

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

func (a *App) guardianChild(r *http.Request) (Membership, Child, bool) {
	_, userID, ok := claimsFrom(r.Context())
	if !ok {
		return Membership{}, Child{}, false
	}
	membership, err := a.firstMembership(r.Context(), userID)
	if err != nil {
		return Membership{}, Child{}, false
	}
	childID, err := primitive.ObjectIDFromHex(chi.URLParam(r, "childID"))
	if err != nil {
		return Membership{}, Child{}, false
	}
	var child Child
	if err := a.store.Children.FindOne(r.Context(), bson.M{"_id": childID, "familyId": membership.FamilyID}).Decode(&child); err != nil {
		return Membership{}, Child{}, false
	}
	return membership, child, true
}

func messageResponse(message GuardianMessage) map[string]any {
	value := map[string]any{
		"id": message.ID.Hex(), "childId": message.ChildID.Hex(),
		"clientMessageId": message.ClientMessageID, "body": message.Body,
		"deliveryState": message.DeliveryState, "createdAt": message.CreatedAt,
	}
	if message.PushedAt != nil {
		value["pushedAt"] = message.PushedAt
	}
	if message.ReadAt != nil {
		value["readAt"] = message.ReadAt
	}
	return value
}

func (a *App) handleCreateMessage(w http.ResponseWriter, r *http.Request) {
	membership, child, ok := a.guardianChild(r)
	if !ok {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	_, senderID, _ := claimsFrom(r.Context())
	var input struct {
		ClientMessageID string `json:"clientMessageId"`
		Body            string `json:"body"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	input.ClientMessageID = strings.TrimSpace(input.ClientMessageID)
	input.Body = strings.TrimSpace(input.Body)
	if input.ClientMessageID == "" || len(input.ClientMessageID) > 100 || len([]rune(input.Body)) < 1 || len([]rune(input.Body)) > 200 {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "メッセージは1〜200文字で入力してください")
		return
	}
	paired, err := a.store.Devices.CountDocuments(r.Context(), bson.M{
		"familyId": membership.FamilyID, "childId": child.ID, "kind": "child", "revokedAt": bson.M{"$exists": false},
	})
	if err != nil {
		a.serverError(w, err)
		return
	}
	if paired == 0 {
		writeAPIError(w, http.StatusConflict, "child_not_paired", "子ども用端末を接続してからメッセージを送ってください")
		return
	}
	now := time.Now().UTC()
	message := GuardianMessage{
		FamilyID: membership.FamilyID, ChildID: child.ID, SenderUserID: senderID,
		ClientMessageID: input.ClientMessageID, Body: input.Body, DeliveryState: "queued",
		NextAttemptAt: now, CreatedAt: now, ExpiresAt: now.Add(30 * 24 * time.Hour),
	}
	result, err := a.store.Messages.InsertOne(r.Context(), message)
	if mongo.IsDuplicateKeyError(err) {
		var existing GuardianMessage
		if findErr := a.store.Messages.FindOne(r.Context(), bson.M{"familyId": membership.FamilyID, "clientMessageId": input.ClientMessageID}).Decode(&existing); findErr != nil {
			a.serverError(w, findErr)
			return
		}
		response := messageResponse(existing)
		response["duplicate"] = true
		writeJSON(w, http.StatusOK, response)
		return
	}
	if err != nil {
		a.serverError(w, err)
		return
	}
	message.ID = result.InsertedID.(primitive.ObjectID)
	a.audit(r.Context(), membership.FamilyID, &senderID, "message.created", message.ID.Hex())
	a.hub.Emit(membership.FamilyID, "message.created", messageResponse(message))
	writeJSON(w, http.StatusCreated, messageResponse(message))
}

func (a *App) handleGuardianMessages(w http.ResponseWriter, r *http.Request) {
	_, child, ok := a.guardianChild(r)
	if !ok {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	writeMessageList(w, r, a.store.Messages, bson.M{"childId": child.ID})
}

func (a *App) handleDeviceMessages(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	_, _ = a.store.Devices.UpdateByID(r.Context(), device.ID, bson.M{"$set": bson.M{"lastSeenAt": time.Now().UTC(), "updatedAt": time.Now().UTC()}})
	writeMessageList(w, r, a.store.Messages, bson.M{"familyId": device.FamilyID, "childId": *device.ChildID})
}

func writeMessageList(w http.ResponseWriter, r *http.Request, collection *mongo.Collection, filter bson.M) {
	limit := int64(50)
	if parsed, err := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 64); err == nil && parsed > 0 && parsed <= 100 {
		limit = parsed
	}
	messages, err := findAll[GuardianMessage](r.Context(), collection, filter, options.Find().SetSort(bson.D{{Key: "createdAt", Value: -1}}).SetLimit(limit))
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal_error", "メッセージを取得できませんでした")
		return
	}
	items := make([]map[string]any, 0, len(messages))
	for _, message := range messages {
		items = append(items, messageResponse(message))
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": items})
}

func (a *App) handleReadMessage(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	messageID, err := primitive.ObjectIDFromHex(chi.URLParam(r, "messageID"))
	if device == nil || device.ChildID == nil || err != nil {
		writeAPIError(w, http.StatusNotFound, "message_not_found", "メッセージが見つかりません")
		return
	}
	now := time.Now().UTC()
	result := a.store.Messages.FindOneAndUpdate(
		r.Context(),
		bson.M{"_id": messageID, "familyId": device.FamilyID, "childId": *device.ChildID, "readAt": bson.M{"$exists": false}},
		bson.M{"$set": bson.M{"deliveryState": "read", "readAt": now}},
		options.FindOneAndUpdate().SetReturnDocument(options.After),
	)
	var message GuardianMessage
	if err := result.Decode(&message); err != nil {
		if findErr := a.store.Messages.FindOne(r.Context(), bson.M{"_id": messageID, "familyId": device.FamilyID, "childId": *device.ChildID}).Decode(&message); findErr != nil {
			writeAPIError(w, http.StatusNotFound, "message_not_found", "メッセージが見つかりません")
			return
		}
		writeJSON(w, http.StatusOK, messageResponse(message))
		return
	}
	a.hub.Emit(device.FamilyID, "message.read", messageResponse(message))
	writeJSON(w, http.StatusOK, messageResponse(message))
}

func (a *App) handleDevicePush(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.Kind != "child" {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	var input struct {
		PushToken string `json:"pushToken"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	input.PushToken = strings.TrimSpace(input.PushToken)
	if input.PushToken == "" || len(input.PushToken) > 4096 {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "通知トークンを確認してください")
		return
	}
	now := time.Now().UTC()
	_, err := a.store.Devices.UpdateByID(r.Context(), device.ID, bson.M{"$set": bson.M{"pushToken": input.PushToken, "lastSeenAt": now, "updatedAt": now}})
	if err != nil {
		a.serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"registered": true})
}

func (a *App) handleUnpairDevice(w http.ResponseWriter, r *http.Request) {
	device, _ := r.Context().Value(deviceContextKey).(*Device)
	if device == nil || device.Kind != "child" || device.ChildID == nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	if device.PauseRestricted {
		writeAPIError(w, http.StatusForbidden, "device_unpair_restricted", "この端末では保護者の設定により家族との接続を解除できません")
		return
	}
	now := time.Now().UTC()
	result, err := a.store.Devices.UpdateOne(
		r.Context(),
		bson.M{"_id": device.ID, "revokedAt": bson.M{"$exists": false}},
		bson.M{"$set": bson.M{"trackingState": "paused", "pushToken": "", "revokedAt": now, "updatedAt": now}},
	)
	if err != nil {
		a.serverError(w, err)
		return
	}
	if result.ModifiedCount != 1 {
		writeAPIError(w, http.StatusUnauthorized, "invalid_device", "端末を再ペアリングしてください")
		return
	}
	var child Child
	_ = a.store.Children.FindOne(r.Context(), bson.M{"_id": *device.ChildID}).Decode(&child)
	a.createAlert(r, device.FamilyID, device.ChildID, nil, "device_unpaired", "子ども用端末の接続を解除しました", child.Name+"の端末接続が解除されました。", now)
	a.hub.Emit(device.FamilyID, "device.status", map[string]any{"childId": device.ChildID.Hex(), "state": "unpaired", "occurredAt": now})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleHistoryDays(w http.ResponseWriter, r *http.Request) {
	_, child, ok := a.guardianChild(r)
	if !ok {
		writeAPIError(w, http.StatusNotFound, "child_not_found", "子どもプロフィールが見つかりません")
		return
	}
	to := time.Now().UTC()
	from := to.Add(-30 * 24 * time.Hour)
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
	pipeline := mongo.Pipeline{
		bson.D{{Key: "$match", Value: bson.D{{Key: "childId", Value: child.ID}, {Key: "recordedAt", Value: bson.D{{Key: "$gte", Value: from}, {Key: "$lte", Value: to}}}}}},
		bson.D{{Key: "$group", Value: bson.D{
			{Key: "_id", Value: bson.D{{Key: "$dateToString", Value: bson.D{{Key: "format", Value: "%Y-%m-%d"}, {Key: "date", Value: "$recordedAt"}, {Key: "timezone", Value: "Asia/Tokyo"}}}}},
			{Key: "pointCount", Value: bson.D{{Key: "$sum", Value: 1}}},
			{Key: "firstRecordedAt", Value: bson.D{{Key: "$min", Value: "$recordedAt"}}},
			{Key: "lastRecordedAt", Value: bson.D{{Key: "$max", Value: "$recordedAt"}}},
		}}},
		bson.D{{Key: "$sort", Value: bson.D{{Key: "_id", Value: -1}}}},
	}
	cursor, err := a.store.LocationPoints.Aggregate(r.Context(), pipeline)
	if err != nil {
		a.serverError(w, err)
		return
	}
	defer cursor.Close(r.Context())
	var rows []struct {
		Date            string    `bson:"_id"`
		PointCount      int       `bson:"pointCount"`
		FirstRecordedAt time.Time `bson:"firstRecordedAt"`
		LastRecordedAt  time.Time `bson:"lastRecordedAt"`
	}
	if err := cursor.All(r.Context(), &rows); err != nil {
		a.serverError(w, err)
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		items = append(items, map[string]any{"date": row.Date, "pointCount": row.PointCount, "firstRecordedAt": row.FirstRecordedAt, "lastRecordedAt": row.LastRecordedAt})
	}
	writeJSON(w, http.StatusOK, map[string]any{"days": items})
}
