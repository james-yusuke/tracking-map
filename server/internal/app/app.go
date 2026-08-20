package app

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

//go:embed openapi.yaml
var openAPISpec []byte

type App struct {
	cfg   Config
	store *Store
	hub   *Hub
}

func New(ctx context.Context, cfg Config) (*App, error) {
	store, err := NewStore(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return &App{cfg: cfg, store: store, hub: NewHub()}, nil
}

func (a *App) Close(ctx context.Context) error { return a.store.Close(ctx) }

func (a *App) Router() http.Handler {
	router := chi.NewRouter()
	router.Use(middleware.RequestID, middleware.RealIP, middleware.Recoverer, middleware.Timeout(20*time.Second))
	router.Use(cors.Handler(cors.Options{AllowedOrigins: a.cfg.WebOrigins, AllowedMethods: []string{"GET", "POST", "PATCH", "DELETE", "OPTIONS"}, AllowedHeaders: []string{"Accept", "Authorization", "Content-Type", "Idempotency-Key"}, AllowCredentials: true, MaxAge: 300}))
	router.Get("/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "service": "family-orbit-api", "now": time.Now().UTC()})
	})
	router.Get("/api/docs/openapi.yaml", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
		_, _ = w.Write(openAPISpec)
	})
	router.Get("/ws", a.handleWebSocket)

	router.Route("/api/v1", func(api chi.Router) {
		api.Route("/auth", func(auth chi.Router) {
			auth.Post("/register", a.handleRegister)
			auth.Post("/login", a.handleLogin)
			auth.Post("/refresh", a.handleRefresh)
			auth.Post("/logout", a.handleLogout)
			auth.Post("/verify-email", a.handleVerifyEmail)
			auth.Post("/request-password-reset", a.handleRequestReset)
			auth.Post("/reset-password", a.handleResetPassword)
		})
		api.Post("/pairing", a.handlePairDevice)
		api.Group(func(guardian chi.Router) {
			guardian.Use(a.requireGuardian)
			guardian.Get("/dashboard", a.handleDashboard)
			guardian.Get("/children/{childID}/history", a.handleHistory)
			guardian.Group(func(mobile chi.Router) {
				mobile.Use(requireParentMobile)
				mobile.Delete("/account", a.handleDeleteAccount)
				mobile.Post("/children", a.handleCreateChild)
				mobile.Delete("/children/{childID}", a.handleDeleteChild)
				mobile.Post("/children/{childID}/pairing-code", a.handleCreatePairingCode)
				mobile.Post("/devices/push", a.handleRegisterPush)
				mobile.Post("/zones", a.handleCreateZone)
				mobile.Patch("/zones/{zoneID}", a.handleUpdateZone)
				mobile.Delete("/zones/{zoneID}", a.handleDeleteZone)
				mobile.Post("/children/{childID}/messages", a.handleCreateMessage)
				mobile.Get("/children/{childID}/messages", a.handleGuardianMessages)
			})
			guardian.Get("/children/{childID}/history-days", a.handleHistoryDays)
		})
		api.Group(func(device chi.Router) {
			device.Use(a.requireDevice)
			device.Delete("/device", a.handleUnpairDevice)
			device.Get("/device/config", a.handleDeviceConfig)
			device.Get("/device/zones", a.handleDeviceZones)
			device.Post("/device/locations", a.handleLocationBatch)
			device.Post("/device/tracking-state", a.handleTrackingState)
			device.Post("/device/push", a.handleDevicePush)
			device.Get("/device/messages", a.handleDeviceMessages)
			device.Post("/device/messages/{messageID}/read", a.handleReadMessage)
		})
	})
	return router
}

func (a *App) RunAPI(ctx context.Context) error {
	server := &http.Server{Addr: ":" + a.cfg.Port, Handler: a.Router(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 25 * time.Second, WriteTimeout: 25 * time.Second, IdleTimeout: 90 * time.Second}
	errChannel := make(chan error, 1)
	go func() {
		slog.Info("Family Orbit API started", "address", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errChannel <- err
		}
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), a.cfg.ShutdownTimeout)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	case err := <-errChannel:
		return err
	}
}

func (a *App) serverError(w http.ResponseWriter, err error) {
	slog.Error("request failed", "error", err)
	writeAPIError(w, http.StatusInternalServerError, "internal_error", "処理を完了できませんでした")
}

func Run(mode string) error {
	cfg, err := LoadConfig(mode)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	app, err := New(ctx, cfg)
	if err != nil {
		return fmt.Errorf("connect to MongoDB: %w", err)
	}
	defer func() {
		closeCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = app.Close(closeCtx)
	}()
	if mode == "worker" {
		return app.RunWorker(ctx)
	}
	return app.RunAPI(ctx)
}
