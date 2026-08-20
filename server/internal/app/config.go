package app

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Mode               string
	Port               string
	MongoURI           string
	DatabaseName       string
	JWTSecret          []byte
	AccessTokenTTL     time.Duration
	RefreshTokenTTL    time.Duration
	WebOrigins         []string
	WebOrigin          string
	SMTPHost           string
	SMTPPort           string
	SMTPUser           string
	SMTPPassword       string
	SMTPFrom           string
	FCMProjectID       string
	FCMClientEmail     string
	FCMPrivateKey      string
	APNSKeyID          string
	APNSTeamID         string
	APNSParentBundleID string
	APNSLinkBundleID   string
	APNSPrivateKey     string
	APNSEnvironment    string
	ShutdownTimeout    time.Duration
}

func LoadConfig(mode string) (Config, error) {
	refreshDays, _ := strconv.Atoi(env("REFRESH_TOKEN_DAYS", "30"))
	accessTTL, err := time.ParseDuration(env("ACCESS_TOKEN_TTL", "15m"))
	if err != nil {
		return Config{}, err
	}
	secret := env("ACCESS_JWT_SECRET", "development-only-secret-change-me")
	if os.Getenv("NODE_ENV") == "production" && len(secret) < 32 {
		return Config{}, errors.New("ACCESS_JWT_SECRET must contain at least 32 characters in production")
	}
	webOrigin := env("WEB_ORIGIN", "http://localhost:3000")
	return Config{
		Mode:               mode,
		Port:               env("API_PORT", "4000"),
		MongoURI:           env("MONGODB_URI", "mongodb://127.0.0.1:27017/family-orbit"),
		DatabaseName:       env("MONGODB_DATABASE", "family-orbit"),
		JWTSecret:          []byte(secret),
		AccessTokenTTL:     accessTTL,
		RefreshTokenTTL:    time.Duration(refreshDays) * 24 * time.Hour,
		WebOrigins:         strings.Split(webOrigin, ","),
		WebOrigin:          strings.Split(webOrigin, ",")[0],
		SMTPHost:           env("SMTP_HOST", "mailpit"),
		SMTPPort:           env("SMTP_PORT", "1025"),
		SMTPUser:           os.Getenv("SMTP_USER"),
		SMTPPassword:       os.Getenv("SMTP_PASSWORD"),
		SMTPFrom:           env("SMTP_FROM", "noreply@localhost"),
		FCMProjectID:       os.Getenv("FCM_PROJECT_ID"),
		FCMClientEmail:     os.Getenv("FCM_CLIENT_EMAIL"),
		FCMPrivateKey:      strings.ReplaceAll(os.Getenv("FCM_PRIVATE_KEY"), `\n`, "\n"),
		APNSKeyID:          os.Getenv("APNS_KEY_ID"),
		APNSTeamID:         os.Getenv("APNS_TEAM_ID"),
		APNSParentBundleID: env("APNS_PARENT_BUNDLE_ID", env("APNS_BUNDLE_ID", "com.tracking.familyorbit")),
		APNSLinkBundleID:   env("APNS_LINK_BUNDLE_ID", "com.tracking.familyorbit.link"),
		APNSPrivateKey:     strings.ReplaceAll(os.Getenv("APNS_PRIVATE_KEY"), `\n`, "\n"),
		APNSEnvironment:    env("APNS_ENVIRONMENT", "production"),
		ShutdownTimeout:    10 * time.Second,
	}, nil
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}
