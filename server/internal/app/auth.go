package app

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"net/http"
	"net/smtp"
	"net/url"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
)

type contextKey string

const (
	claimsContextKey contextKey = "guardian-claims"
	deviceContextKey contextKey = "child-device"
)

type registerRequest struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"displayName"`
	FamilyName  string `json:"familyName"`
	ClientType  string `json:"clientType"`
}

type loginRequest struct {
	Email      string `json:"email"`
	Password   string `json:"password"`
	ClientType string `json:"clientType"`
}

type tokenRequest struct {
	Token string `json:"token"`
}

type authResponse struct {
	AccessToken     string `json:"accessToken"`
	RefreshToken    string `json:"refreshToken,omitempty"`
	ExpiresInSecond int    `json:"expiresInSeconds"`
	User            struct {
		ID            string `json:"id"`
		Email         string `json:"email"`
		DisplayName   string `json:"displayName"`
		EmailVerified bool   `json:"emailVerified"`
	} `json:"user"`
}

func validClientType(value string) bool {
	return value == "web" || value == "parent_android" || value == "parent_ios"
}

func (a *App) handleRegister(w http.ResponseWriter, r *http.Request) {
	var input registerRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	input.Email = strings.ToLower(strings.TrimSpace(input.Email))
	if !strings.Contains(input.Email, "@") || len(input.Password) < 12 || strings.TrimSpace(input.DisplayName) == "" || strings.TrimSpace(input.FamilyName) == "" || !validClientType(input.ClientType) {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "入力内容を確認してください")
		return
	}
	passwordHash, err := hashPassword(input.Password)
	if err != nil {
		writeAPIError(w, http.StatusBadRequest, "weak_password", "パスワードは12文字以上にしてください")
		return
	}
	now := time.Now().UTC()
	user := User{Email: input.Email, PasswordHash: passwordHash, DisplayName: strings.TrimSpace(input.DisplayName), CreatedAt: now, UpdatedAt: now}
	result, err := a.store.Users.InsertOne(r.Context(), user)
	if mongo.IsDuplicateKeyError(err) {
		writeAPIError(w, http.StatusConflict, "email_in_use", "このメールアドレスは登録済みです")
		return
	}
	if err != nil {
		a.serverError(w, err)
		return
	}
	user.ID = result.InsertedID.(primitive.ObjectID)
	family := Family{Name: strings.TrimSpace(input.FamilyName), OwnerID: user.ID, CreatedAt: now, UpdatedAt: now}
	familyResult, err := a.store.Families.InsertOne(r.Context(), family)
	if err != nil {
		_, _ = a.store.Users.DeleteOne(r.Context(), bson.M{"_id": user.ID})
		a.serverError(w, err)
		return
	}
	family.ID = familyResult.InsertedID.(primitive.ObjectID)
	if _, err := a.store.Memberships.InsertOne(r.Context(), Membership{FamilyID: family.ID, UserID: user.ID, Role: "owner"}); err != nil {
		_, _ = a.store.Families.DeleteOne(r.Context(), bson.M{"_id": family.ID})
		_, _ = a.store.Users.DeleteOne(r.Context(), bson.M{"_id": user.ID})
		a.serverError(w, err)
		return
	}
	verifyToken, _ := a.createOneTimeToken(r.Context(), user.ID, "verify_email", 24*time.Hour)
	go func() {
		if err := a.sendActionMail(user.Email, "Family Orbit メールアドレス確認", "/verify-email", verifyToken); err != nil {
			slog.Warn("email delivery failed", "purpose", "verify_email", "error", err)
		}
	}()
	a.writeSession(w, r, user, input.ClientType, http.StatusCreated)
}

func (a *App) handleLogin(w http.ResponseWriter, r *http.Request) {
	var input loginRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	if !validClientType(input.ClientType) {
		writeAPIError(w, http.StatusBadRequest, "validation_failed", "クライアント種別が正しくありません")
		return
	}
	var user User
	err := a.store.Users.FindOne(r.Context(), bson.M{"email": strings.ToLower(strings.TrimSpace(input.Email)), "deletedAt": bson.M{"$exists": false}}).Decode(&user)
	if err != nil || !verifyPassword(user.PasswordHash, input.Password) {
		writeAPIError(w, http.StatusUnauthorized, "invalid_credentials", "メールアドレスまたはパスワードが違います")
		return
	}
	a.writeSession(w, r, user, input.ClientType, http.StatusOK)
}

func (a *App) writeSession(w http.ResponseWriter, r *http.Request, user User, clientType string, status int) {
	accessToken, err := a.signAccessToken(user, clientType)
	if err != nil {
		a.serverError(w, err)
		return
	}
	rawRefresh, err := randomToken(48)
	if err != nil {
		a.serverError(w, err)
		return
	}
	now := time.Now().UTC()
	_, err = a.store.RefreshSessions.InsertOne(r.Context(), RefreshSession{
		UserID: user.ID, TokenHash: hashToken(rawRefresh), ClientType: clientType,
		ExpiresAt: now.Add(a.cfg.RefreshTokenTTL), UserAgent: r.UserAgent(), CreatedAt: now,
	})
	if err != nil {
		a.serverError(w, err)
		return
	}
	secure := strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") || r.TLS != nil
	http.SetCookie(w, &http.Cookie{Name: "family_orbit_refresh", Value: rawRefresh, Path: "/api/v1/auth", MaxAge: int(a.cfg.RefreshTokenTTL.Seconds()), HttpOnly: true, Secure: secure, SameSite: http.SameSiteLaxMode})
	response := authResponse{AccessToken: accessToken, ExpiresInSecond: int(a.cfg.AccessTokenTTL.Seconds())}
	if clientType != "web" {
		response.RefreshToken = rawRefresh
	}
	response.User.ID = user.ID.Hex()
	response.User.Email = user.Email
	response.User.DisplayName = user.DisplayName
	response.User.EmailVerified = user.EmailVerifiedAt != nil
	writeJSON(w, status, response)
}

func (a *App) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var input tokenRequest
	_ = json.NewDecoder(r.Body).Decode(&input)
	if input.Token == "" {
		if cookie, err := r.Cookie("family_orbit_refresh"); err == nil {
			input.Token = cookie.Value
		}
	}
	if input.Token == "" {
		writeAPIError(w, http.StatusUnauthorized, "missing_refresh_token", "再ログインしてください")
		return
	}
	var session RefreshSession
	if err := a.store.RefreshSessions.FindOne(r.Context(), bson.M{"tokenHash": hashToken(input.Token), "revokedAt": bson.M{"$exists": false}, "expiresAt": bson.M{"$gt": time.Now().UTC()}}).Decode(&session); err != nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_refresh_token", "セッションの有効期限が切れています")
		return
	}
	now := time.Now().UTC()
	_, _ = a.store.RefreshSessions.UpdateByID(r.Context(), session.ID, bson.M{"$set": bson.M{"revokedAt": now}})
	var user User
	if err := a.store.Users.FindOne(r.Context(), bson.M{"_id": session.UserID, "deletedAt": bson.M{"$exists": false}}).Decode(&user); err != nil {
		writeAPIError(w, http.StatusUnauthorized, "user_not_found", "再ログインしてください")
		return
	}
	a.writeSession(w, r, user, session.ClientType, http.StatusOK)
}

func (a *App) handleLogout(w http.ResponseWriter, r *http.Request) {
	var input tokenRequest
	_ = json.NewDecoder(r.Body).Decode(&input)
	if input.Token == "" {
		if cookie, err := r.Cookie("family_orbit_refresh"); err == nil {
			input.Token = cookie.Value
		}
	}
	if input.Token != "" {
		now := time.Now().UTC()
		_, _ = a.store.RefreshSessions.UpdateOne(r.Context(), bson.M{"tokenHash": hashToken(input.Token)}, bson.M{"$set": bson.M{"revokedAt": now}})
	}
	http.SetCookie(w, &http.Cookie{Name: "family_orbit_refresh", Value: "", Path: "/api/v1/auth", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleVerifyEmail(w http.ResponseWriter, r *http.Request) {
	var input tokenRequest
	if !decodeJSON(w, r, &input) {
		return
	}
	token, ok := a.consumeOneTimeToken(r.Context(), input.Token, "verify_email")
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "invalid_token", "リンクが無効か有効期限切れです")
		return
	}
	now := time.Now().UTC()
	_, _ = a.store.Users.UpdateByID(r.Context(), token.UserID, bson.M{"$set": bson.M{"emailVerifiedAt": now, "updatedAt": now}})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) handleRequestReset(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Email string `json:"email"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	var user User
	if err := a.store.Users.FindOne(r.Context(), bson.M{"email": strings.ToLower(strings.TrimSpace(input.Email))}).Decode(&user); err == nil {
		token, _ := a.createOneTimeToken(r.Context(), user.ID, "reset_password", 30*time.Minute)
		go func() {
			_ = a.sendActionMail(user.Email, "Family Orbit パスワード再設定", "/reset-password", token)
		}()
	}
	w.WriteHeader(http.StatusAccepted)
}

func (a *App) handleResetPassword(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Token    string `json:"token"`
		Password string `json:"password"`
	}
	if !decodeJSON(w, r, &input) {
		return
	}
	passwordHash, err := hashPassword(input.Password)
	if err != nil {
		writeAPIError(w, http.StatusBadRequest, "weak_password", "パスワードは12文字以上にしてください")
		return
	}
	token, ok := a.consumeOneTimeToken(r.Context(), input.Token, "reset_password")
	if !ok {
		writeAPIError(w, http.StatusBadRequest, "invalid_token", "リンクが無効か有効期限切れです")
		return
	}
	now := time.Now().UTC()
	_, _ = a.store.Users.UpdateByID(r.Context(), token.UserID, bson.M{"$set": bson.M{"passwordHash": passwordHash, "updatedAt": now}})
	_, _ = a.store.RefreshSessions.UpdateMany(r.Context(), bson.M{"userId": token.UserID, "revokedAt": bson.M{"$exists": false}}, bson.M{"$set": bson.M{"revokedAt": now}})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) createOneTimeToken(ctx context.Context, userID primitive.ObjectID, purpose string, ttl time.Duration) (string, error) {
	raw, err := randomToken(32)
	if err != nil {
		return "", err
	}
	_, err = a.store.OneTimeTokens.InsertOne(ctx, OneTimeToken{UserID: userID, TokenHash: hashToken(raw), Purpose: purpose, ExpiresAt: time.Now().UTC().Add(ttl)})
	return raw, err
}

func (a *App) consumeOneTimeToken(ctx context.Context, raw, purpose string) (OneTimeToken, bool) {
	var token OneTimeToken
	err := a.store.OneTimeTokens.FindOne(ctx, bson.M{"tokenHash": hashToken(raw), "purpose": purpose, "usedAt": bson.M{"$exists": false}, "expiresAt": bson.M{"$gt": time.Now().UTC()}}).Decode(&token)
	if err != nil {
		return token, false
	}
	now := time.Now().UTC()
	_, _ = a.store.OneTimeTokens.UpdateByID(ctx, token.ID, bson.M{"$set": bson.M{"usedAt": now}})
	return token, true
}

func (a *App) sendActionMail(email, subject, path, token string) error {
	to := []string{email}
	link := a.cfg.WebOrigin + path + "?token=" + url.QueryEscape(token)
	message := []byte(fmt.Sprintf("To: %s\r\nSubject: %s\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n%s\n\n%s\n", email, subject, subject, link))
	var auth smtp.Auth
	if a.cfg.SMTPUser != "" {
		auth = smtp.PlainAuth("", a.cfg.SMTPUser, a.cfg.SMTPPassword, a.cfg.SMTPHost)
	}
	return smtp.SendMail(a.cfg.SMTPHost+":"+a.cfg.SMTPPort, auth, a.cfg.SMTPFrom, to, message)
}

func (a *App) signAccessToken(user User, clientType string) (string, error) {
	now := time.Now().UTC()
	claims := AccessClaims{
		Email: user.Email, ClientType: clientType,
		RegisteredClaims: jwt.RegisteredClaims{Issuer: "family-orbit-api", Subject: user.ID.Hex(), IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(a.cfg.AccessTokenTTL))},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.cfg.JWTSecret)
}

func (a *App) parseAccessToken(raw string) (*AccessClaims, error) {
	parsed, err := jwt.ParseWithClaims(raw, &AccessClaims{}, func(token *jwt.Token) (any, error) {
		if token.Method != jwt.SigningMethodHS256 {
			return nil, errors.New("unexpected signing method")
		}
		return a.cfg.JWTSecret, nil
	}, jwt.WithIssuer("family-orbit-api"), jwt.WithExpirationRequired())
	if err != nil || !parsed.Valid {
		return nil, errors.New("invalid access token")
	}
	claims, ok := parsed.Claims.(*AccessClaims)
	if !ok {
		return nil, errors.New("invalid claims")
	}
	return claims, nil
}

func (a *App) requireGuardian(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			writeAPIError(w, http.StatusUnauthorized, "missing_access_token", "ログインが必要です")
			return
		}
		claims, err := a.parseAccessToken(strings.TrimPrefix(header, "Bearer "))
		if err != nil {
			writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), claimsContextKey, claims)))
	})
}

func requireParentMobile(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		claims, _ := r.Context().Value(claimsContextKey).(*AccessClaims)
		if claims == nil || claims.ClientType == "web" {
			writeAPIError(w, http.StatusForbidden, "web_read_only", "Webダッシュボードは閲覧専用です")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *App) requireDevice(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Device ") {
			writeAPIError(w, http.StatusUnauthorized, "missing_device_token", "端末のペアリングが必要です")
			return
		}
		var device Device
		err := a.store.Devices.FindOne(r.Context(), bson.M{"tokenHash": hashToken(strings.TrimPrefix(header, "Device ")), "revokedAt": bson.M{"$exists": false}}).Decode(&device)
		if err != nil {
			writeAPIError(w, http.StatusUnauthorized, "invalid_device_token", "端末を再ペアリングしてください")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), deviceContextKey, &device)))
	})
}

func claimsFrom(ctx context.Context) (*AccessClaims, primitive.ObjectID, bool) {
	claims, ok := ctx.Value(claimsContextKey).(*AccessClaims)
	if !ok {
		return nil, primitive.NilObjectID, false
	}
	id, err := primitive.ObjectIDFromHex(claims.Subject)
	return claims, id, err == nil
}

func sixDigitCode() (string, error) {
	value, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", value.Int64()), nil
}
