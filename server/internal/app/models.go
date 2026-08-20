package app

import (
	"time"

	"github.com/golang-jwt/jwt/v5"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

type User struct {
	ID              primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Email           string             `bson:"email" json:"email"`
	PasswordHash    string             `bson:"passwordHash" json:"-"`
	DisplayName     string             `bson:"displayName" json:"displayName"`
	EmailVerifiedAt *time.Time         `bson:"emailVerifiedAt,omitempty" json:"emailVerifiedAt,omitempty"`
	DeletedAt       *time.Time         `bson:"deletedAt,omitempty" json:"-"`
	CreatedAt       time.Time          `bson:"createdAt" json:"createdAt"`
	UpdatedAt       time.Time          `bson:"updatedAt" json:"updatedAt"`
}

type Family struct {
	ID        primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Name      string             `bson:"name" json:"name"`
	OwnerID   primitive.ObjectID `bson:"ownerId" json:"ownerId"`
	CreatedAt time.Time          `bson:"createdAt" json:"createdAt"`
	UpdatedAt time.Time          `bson:"updatedAt" json:"updatedAt"`
}

type Membership struct {
	ID       primitive.ObjectID `bson:"_id,omitempty"`
	FamilyID primitive.ObjectID `bson:"familyId" json:"familyId"`
	UserID   primitive.ObjectID `bson:"userId" json:"userId"`
	Role     string             `bson:"role" json:"role"`
}

type Child struct {
	ID         primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	FamilyID   primitive.ObjectID `bson:"familyId" json:"familyId"`
	Name       string             `bson:"name" json:"name"`
	Color      string             `bson:"color" json:"color"`
	AvatarSeed string             `bson:"avatarSeed" json:"avatarSeed"`
	CreatedAt  time.Time          `bson:"createdAt" json:"createdAt"`
	UpdatedAt  time.Time          `bson:"updatedAt" json:"updatedAt"`
}

type Device struct {
	ID              primitive.ObjectID  `bson:"_id,omitempty" json:"id"`
	FamilyID        primitive.ObjectID  `bson:"familyId" json:"familyId"`
	ChildID         *primitive.ObjectID `bson:"childId,omitempty" json:"childId,omitempty"`
	UserID          *primitive.ObjectID `bson:"userId,omitempty" json:"userId,omitempty"`
	Kind            string              `bson:"kind" json:"kind"`
	Platform        string              `bson:"platform" json:"platform"`
	Name            string              `bson:"name" json:"name"`
	TokenHash       string              `bson:"tokenHash,omitempty" json:"-"`
	PushToken       string              `bson:"pushToken,omitempty" json:"-"`
	TrackingState   string              `bson:"trackingState" json:"trackingState"`
	PauseRestricted bool                `bson:"pauseRestricted" json:"pauseRestricted"`
	LastSeenAt      *time.Time          `bson:"lastSeenAt,omitempty" json:"lastSeenAt,omitempty"`
	RevokedAt       *time.Time          `bson:"revokedAt,omitempty" json:"-"`
	CreatedAt       time.Time           `bson:"createdAt" json:"createdAt"`
	UpdatedAt       time.Time           `bson:"updatedAt" json:"updatedAt"`
}

type GeoPoint struct {
	Type        string    `bson:"type" json:"type"`
	Coordinates []float64 `bson:"coordinates" json:"coordinates"`
}

type LocationPoint struct {
	ID           primitive.ObjectID `bson:"_id,omitempty"`
	FamilyID     primitive.ObjectID `bson:"familyId"`
	ChildID      primitive.ObjectID `bson:"childId"`
	DeviceID     primitive.ObjectID `bson:"deviceId"`
	RecordedAt   time.Time          `bson:"recordedAt"`
	Point        GeoPoint           `bson:"point"`
	Accuracy     float64            `bson:"accuracy"`
	Speed        *float64           `bson:"speed,omitempty"`
	BatteryLevel float64            `bson:"batteryLevel"`
	IsCharging   bool               `bson:"isCharging"`
	ExpiresAt    time.Time          `bson:"expiresAt"`
	CreatedAt    time.Time          `bson:"createdAt"`
}

type LatestLocation struct {
	ID           primitive.ObjectID `bson:"_id,omitempty"`
	FamilyID     primitive.ObjectID `bson:"familyId"`
	ChildID      primitive.ObjectID `bson:"childId"`
	DeviceID     primitive.ObjectID `bson:"deviceId"`
	RecordedAt   time.Time          `bson:"recordedAt"`
	Point        GeoPoint           `bson:"point"`
	Accuracy     float64            `bson:"accuracy"`
	BatteryLevel float64            `bson:"batteryLevel"`
	IsCharging   bool               `bson:"isCharging"`
	UpdatedAt    time.Time          `bson:"updatedAt"`
}

type SafetyZone struct {
	ID           primitive.ObjectID   `bson:"_id,omitempty" json:"id"`
	FamilyID     primitive.ObjectID   `bson:"familyId" json:"familyId"`
	Name         string               `bson:"name" json:"name"`
	Center       GeoPoint             `bson:"center" json:"center"`
	RadiusMeters float64              `bson:"radiusMeters" json:"radiusMeters"`
	Color        string               `bson:"color" json:"color"`
	ChildIDs     []primitive.ObjectID `bson:"childIds" json:"childIds"`
	Enabled      bool                 `bson:"enabled" json:"enabled"`
	CreatedAt    time.Time            `bson:"createdAt" json:"createdAt"`
	UpdatedAt    time.Time            `bson:"updatedAt" json:"updatedAt"`
}

type ZoneState struct {
	ID        primitive.ObjectID `bson:"_id,omitempty"`
	FamilyID  primitive.ObjectID `bson:"familyId"`
	ChildID   primitive.ObjectID `bson:"childId"`
	ZoneID    primitive.ObjectID `bson:"zoneId"`
	State     string             `bson:"state"`
	ChangedAt time.Time          `bson:"changedAt"`
}

type Alert struct {
	ID            primitive.ObjectID  `bson:"_id,omitempty" json:"id"`
	FamilyID      primitive.ObjectID  `bson:"familyId" json:"familyId"`
	ChildID       *primitive.ObjectID `bson:"childId,omitempty" json:"childId,omitempty"`
	ZoneID        *primitive.ObjectID `bson:"zoneId,omitempty" json:"zoneId,omitempty"`
	Type          string              `bson:"type" json:"type"`
	Title         string              `bson:"title" json:"title"`
	Message       string              `bson:"message" json:"message"`
	OccurredAt    time.Time           `bson:"occurredAt" json:"occurredAt"`
	DeliveryState string              `bson:"deliveryState" json:"-"`
	AttemptCount  int                 `bson:"attemptCount" json:"-"`
	NextAttemptAt time.Time           `bson:"nextAttemptAt" json:"-"`
	DeliveredAt   *time.Time          `bson:"deliveredAt,omitempty" json:"-"`
	ExpiresAt     time.Time           `bson:"expiresAt" json:"-"`
}

type GuardianMessage struct {
	ID              primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	FamilyID        primitive.ObjectID `bson:"familyId" json:"familyId"`
	ChildID         primitive.ObjectID `bson:"childId" json:"childId"`
	SenderUserID    primitive.ObjectID `bson:"senderUserId" json:"-"`
	ClientMessageID string             `bson:"clientMessageId" json:"clientMessageId"`
	Body            string             `bson:"body" json:"body"`
	DeliveryState   string             `bson:"deliveryState" json:"deliveryState"`
	AttemptCount    int                `bson:"attemptCount" json:"-"`
	NextAttemptAt   time.Time          `bson:"nextAttemptAt" json:"-"`
	PushedAt        *time.Time         `bson:"pushedAt,omitempty" json:"pushedAt,omitempty"`
	ReadAt          *time.Time         `bson:"readAt,omitempty" json:"readAt,omitempty"`
	CreatedAt       time.Time          `bson:"createdAt" json:"createdAt"`
	ExpiresAt       time.Time          `bson:"expiresAt" json:"-"`
}

type RefreshSession struct {
	ID         primitive.ObjectID `bson:"_id,omitempty"`
	UserID     primitive.ObjectID `bson:"userId"`
	TokenHash  string             `bson:"tokenHash"`
	ClientType string             `bson:"clientType"`
	ExpiresAt  time.Time          `bson:"expiresAt"`
	RevokedAt  *time.Time         `bson:"revokedAt,omitempty"`
	UserAgent  string             `bson:"userAgent,omitempty"`
	CreatedAt  time.Time          `bson:"createdAt"`
}

type PairingToken struct {
	ID              primitive.ObjectID `bson:"_id,omitempty"`
	FamilyID        primitive.ObjectID `bson:"familyId"`
	ChildID         primitive.ObjectID `bson:"childId"`
	CodeHash        string             `bson:"codeHash"`
	CreatedBy       primitive.ObjectID `bson:"createdBy"`
	PauseRestricted bool               `bson:"pauseRestricted"`
	ExpiresAt       time.Time          `bson:"expiresAt"`
	UsedAt          *time.Time         `bson:"usedAt,omitempty"`
}

type OneTimeToken struct {
	ID        primitive.ObjectID `bson:"_id,omitempty"`
	UserID    primitive.ObjectID `bson:"userId"`
	TokenHash string             `bson:"tokenHash"`
	Purpose   string             `bson:"purpose"`
	ExpiresAt time.Time          `bson:"expiresAt"`
	UsedAt    *time.Time         `bson:"usedAt,omitempty"`
}

type AccessClaims struct {
	Email      string `json:"email"`
	ClientType string `json:"clientType"`
	jwt.RegisteredClaims
}
