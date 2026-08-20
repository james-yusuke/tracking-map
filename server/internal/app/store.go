package app

import (
	"context"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type Store struct {
	Client          *mongo.Client
	DB              *mongo.Database
	Users           *mongo.Collection
	Families        *mongo.Collection
	Memberships     *mongo.Collection
	Children        *mongo.Collection
	Devices         *mongo.Collection
	LocationPoints  *mongo.Collection
	LatestLocations *mongo.Collection
	SafetyZones     *mongo.Collection
	ZoneStates      *mongo.Collection
	Alerts          *mongo.Collection
	Messages        *mongo.Collection
	PairingTokens   *mongo.Collection
	RefreshSessions *mongo.Collection
	OneTimeTokens   *mongo.Collection
	AuditLogs       *mongo.Collection
	Idempotency     *mongo.Collection
}

func NewStore(ctx context.Context, cfg Config) (*Store, error) {
	client, err := mongo.Connect(ctx, options.Client().ApplyURI(cfg.MongoURI).SetServerSelectionTimeout(8*time.Second))
	if err != nil {
		return nil, err
	}
	if err := client.Ping(ctx, nil); err != nil {
		_ = client.Disconnect(ctx)
		return nil, err
	}
	db := client.Database(cfg.DatabaseName)
	store := &Store{
		Client:          client,
		DB:              db,
		Users:           db.Collection("users"),
		Families:        db.Collection("families"),
		Memberships:     db.Collection("memberships"),
		Children:        db.Collection("children"),
		Devices:         db.Collection("devices"),
		LocationPoints:  db.Collection("location_points"),
		LatestLocations: db.Collection("latest_locations"),
		SafetyZones:     db.Collection("safety_zones"),
		ZoneStates:      db.Collection("zone_states"),
		Alerts:          db.Collection("alerts"),
		Messages:        db.Collection("messages"),
		PairingTokens:   db.Collection("pairing_tokens"),
		RefreshSessions: db.Collection("refresh_sessions"),
		OneTimeTokens:   db.Collection("one_time_tokens"),
		AuditLogs:       db.Collection("audit_logs"),
		Idempotency:     db.Collection("idempotency_keys"),
	}
	if err := store.ensureIndexes(ctx); err != nil {
		_ = client.Disconnect(ctx)
		return nil, err
	}
	return store, nil
}

func (s *Store) Close(ctx context.Context) error { return s.Client.Disconnect(ctx) }

func (s *Store) ensureIndexes(ctx context.Context) error {
	unique := options.Index().SetUnique(true)
	ttl := options.Index().SetExpireAfterSeconds(0)
	indexes := []struct {
		collection *mongo.Collection
		models     []mongo.IndexModel
	}{
		{s.Users, []mongo.IndexModel{{Keys: bson.D{{Key: "email", Value: 1}}, Options: unique}}},
		{s.Memberships, []mongo.IndexModel{
			{Keys: bson.D{{Key: "familyId", Value: 1}, {Key: "userId", Value: 1}}, Options: unique},
			{Keys: bson.D{{Key: "userId", Value: 1}}},
		}},
		{s.Devices, []mongo.IndexModel{
			{Keys: bson.D{{Key: "tokenHash", Value: 1}}, Options: options.Index().SetUnique(true).SetSparse(true)},
			{Keys: bson.D{{Key: "familyId", Value: 1}, {Key: "childId", Value: 1}}},
		}},
		{s.LocationPoints, []mongo.IndexModel{
			{Keys: bson.D{{Key: "childId", Value: 1}, {Key: "recordedAt", Value: -1}}},
			{Keys: bson.D{{Key: "point", Value: "2dsphere"}}},
			{Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl},
		}},
		{s.LatestLocations, []mongo.IndexModel{{Keys: bson.D{{Key: "childId", Value: 1}}, Options: unique}}},
		{s.SafetyZones, []mongo.IndexModel{{Keys: bson.D{{Key: "center", Value: "2dsphere"}}}, {Keys: bson.D{{Key: "familyId", Value: 1}}}}},
		{s.ZoneStates, []mongo.IndexModel{{Keys: bson.D{{Key: "childId", Value: 1}, {Key: "zoneId", Value: 1}}, Options: unique}}},
		{s.Alerts, []mongo.IndexModel{{Keys: bson.D{{Key: "familyId", Value: 1}, {Key: "occurredAt", Value: -1}}}, {Keys: bson.D{{Key: "deliveryState", Value: 1}, {Key: "nextAttemptAt", Value: 1}}}, {Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
		{s.Messages, []mongo.IndexModel{
			{Keys: bson.D{{Key: "familyId", Value: 1}, {Key: "clientMessageId", Value: 1}}, Options: unique},
			{Keys: bson.D{{Key: "childId", Value: 1}, {Key: "createdAt", Value: -1}}},
			{Keys: bson.D{{Key: "deliveryState", Value: 1}, {Key: "nextAttemptAt", Value: 1}}},
			{Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl},
		}},
		{s.PairingTokens, []mongo.IndexModel{{Keys: bson.D{{Key: "codeHash", Value: 1}}, Options: unique}, {Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
		{s.RefreshSessions, []mongo.IndexModel{{Keys: bson.D{{Key: "tokenHash", Value: 1}}, Options: unique}, {Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
		{s.OneTimeTokens, []mongo.IndexModel{{Keys: bson.D{{Key: "tokenHash", Value: 1}}, Options: unique}, {Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
		{s.AuditLogs, []mongo.IndexModel{{Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
		{s.Idempotency, []mongo.IndexModel{{Keys: bson.D{{Key: "deviceId", Value: 1}, {Key: "key", Value: 1}}, Options: unique}, {Keys: bson.D{{Key: "expiresAt", Value: 1}}, Options: ttl}}},
	}
	for _, item := range indexes {
		if _, err := item.collection.Indexes().CreateMany(ctx, item.models); err != nil {
			return err
		}
	}
	return nil
}
