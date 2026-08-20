package app

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"
)

func TestPasswordHashRoundTrip(t *testing.T) {
	hash, err := hashPassword("correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	if !verifyPassword(hash, "correct horse battery staple") {
		t.Fatal("valid password was rejected")
	}
	if verifyPassword(hash, "incorrect password") {
		t.Fatal("invalid password was accepted")
	}
	if _, err := hashPassword("short"); err == nil {
		t.Fatal("short password must be rejected")
	}
}

func TestAccessTokenAndExpiry(t *testing.T) {
	app := &App{cfg: Config{JWTSecret: []byte("test-secret-with-at-least-32-bytes"), AccessTokenTTL: time.Minute}}
	user := User{ID: primitive.NewObjectID(), Email: "parent@example.jp"}
	token, err := app.signAccessToken(user, "parent_ios")
	if err != nil {
		t.Fatal(err)
	}
	claims, err := app.parseAccessToken(token)
	if err != nil || claims.Subject != user.ID.Hex() || claims.ClientType != "parent_ios" {
		t.Fatalf("unexpected claims: %#v, %v", claims, err)
	}

	expired := &App{cfg: Config{JWTSecret: app.cfg.JWTSecret, AccessTokenTTL: -time.Minute}}
	token, err = expired.signAccessToken(user, "web")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = expired.parseAccessToken(token); err == nil {
		t.Fatal("expired token must be rejected")
	}
}

func TestWebClientIsReadOnly(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	request := httptest.NewRequest(http.MethodPost, "/api/v1/zones", strings.NewReader(`{}`))
	request = request.WithContext(context.WithValue(request.Context(), claimsContextKey, &AccessClaims{ClientType: "web"}))
	recorder := httptest.NewRecorder()
	requireParentMobile(next).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("web mutation returned %d", recorder.Code)
	}
	var response apiError
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil || response.Code != "web_read_only" {
		t.Fatalf("unexpected error: %#v, %v", response, err)
	}

	request = httptest.NewRequest(http.MethodPost, "/api/v1/zones", strings.NewReader(`{}`))
	request = request.WithContext(context.WithValue(request.Context(), claimsContextKey, &AccessClaims{ClientType: "parent_android"}))
	recorder = httptest.NewRecorder()
	requireParentMobile(next).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("mobile mutation returned %d", recorder.Code)
	}
}

func TestTrackingRules(t *testing.T) {
	for _, value := range []string{"active", "paused", "permission_denied"} {
		if !validTrackingState(value) {
			t.Fatalf("valid state %q was rejected", value)
		}
	}
	if validTrackingState("hidden") {
		t.Fatal("hidden tracking state must never be valid")
	}
	distance := haversineMeters(35.681236, 139.767125, 35.689634, 139.700556)
	if distance < 5_000 || distance > 8_000 {
		t.Fatalf("unexpected Tokyo distance: %.0fm", distance)
	}
}

func TestSixDigitPairingCode(t *testing.T) {
	for range 50 {
		code, err := sixDigitCode()
		if err != nil {
			t.Fatal(err)
		}
		if len(code) != 6 {
			t.Fatalf("pairing code is not six digits: %q", code)
		}
		for _, character := range code {
			if character < '0' || character > '9' {
				t.Fatalf("pairing code contains a non-digit: %q", code)
			}
		}
	}
}

func TestHealthAndOpenAPI(t *testing.T) {
	app := &App{cfg: Config{WebOrigins: []string{"http://localhost:3000"}}, hub: NewHub()}
	for path, expected := range map[string]string{"/health": "family-orbit-api", "/api/docs/openapi.yaml": "/device/zones"} {
		recorder := httptest.NewRecorder()
		app.Router().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
		if recorder.Code != http.StatusOK || !strings.Contains(recorder.Body.String(), expected) {
			t.Fatalf("GET %s returned %d: %s", path, recorder.Code, recorder.Body.String())
		}
	}
}
