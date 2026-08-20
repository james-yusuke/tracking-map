package app

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

type Hub struct {
	mu      sync.RWMutex
	clients map[primitive.ObjectID]map[*websocket.Conn]struct{}
}

func NewHub() *Hub { return &Hub{clients: make(map[primitive.ObjectID]map[*websocket.Conn]struct{})} }

func (h *Hub) add(familyID primitive.ObjectID, connection *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[familyID] == nil {
		h.clients[familyID] = make(map[*websocket.Conn]struct{})
	}
	h.clients[familyID][connection] = struct{}{}
}

func (h *Hub) remove(familyID primitive.ObjectID, connection *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients[familyID], connection)
	if len(h.clients[familyID]) == 0 {
		delete(h.clients, familyID)
	}
}

func (h *Hub) Emit(familyID primitive.ObjectID, event string, data any) {
	payload, _ := json.Marshal(map[string]any{"event": event, "data": data})
	h.mu.RLock()
	connections := make([]*websocket.Conn, 0, len(h.clients[familyID]))
	for connection := range h.clients[familyID] {
		connections = append(connections, connection)
	}
	h.mu.RUnlock()
	for _, connection := range connections {
		_ = connection.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if err := connection.WriteMessage(websocket.TextMessage, payload); err != nil {
			_ = connection.Close()
			h.remove(familyID, connection)
		}
	}
}

func (a *App) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	claims, err := a.parseAccessToken(r.URL.Query().Get("token"))
	if err != nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	userID, err := primitive.ObjectIDFromHex(claims.Subject)
	if err != nil {
		writeAPIError(w, http.StatusUnauthorized, "invalid_access_token", "再ログインしてください")
		return
	}
	var membership Membership
	if err := a.store.Memberships.FindOne(r.Context(), bson.M{"userId": userID}).Decode(&membership); err != nil {
		writeAPIError(w, http.StatusForbidden, "family_access_denied", "家族へのアクセス権がありません")
		return
	}
	upgrader := websocket.Upgrader{
		ReadBufferSize: 1024, WriteBufferSize: 1024,
		CheckOrigin: func(request *http.Request) bool {
			origin := request.Header.Get("Origin")
			for _, allowed := range a.cfg.WebOrigins {
				if origin == allowed || origin == "" {
					return true
				}
			}
			return false
		},
	}
	connection, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	a.hub.add(membership.FamilyID, connection)
	defer func() {
		a.hub.remove(membership.FamilyID, connection)
		_ = connection.Close()
	}()
	connection.SetReadLimit(4096)
	_ = connection.SetReadDeadline(time.Now().Add(90 * time.Second))
	connection.SetPongHandler(func(string) error {
		return connection.SetReadDeadline(time.Now().Add(90 * time.Second))
	})
	for {
		if _, _, err := connection.ReadMessage(); err != nil {
			return
		}
	}
}
