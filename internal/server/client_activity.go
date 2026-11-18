package server

import (
	"log"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
)

func (s *Server) recordClientHeartbeat(p *peer) error {
	if p.role != protocol.RoleClient {
		return nil
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.clients[p.clientID] != p {
		return store.ErrUnauthorized
	}
	return s.store.RecordClientConnection(p.clientID, p.ip, time.Now(), true)
}

func (s *Server) pruneInactiveClients(now time.Time) error {
	// Use the same lock order as WebSocket registration so cleanup cannot
	// remove a client between the final authorization check and registration.
	s.mu.Lock()
	defer s.mu.Unlock()
	active := make(map[string]bool, len(s.clients))
	for id, p := range s.clients {
		select {
		case <-p.closed:
		default:
			active[id] = true
		}
	}
	_, err := s.store.PruneInactiveClients(now, active)
	return err
}

func (s *Server) maintainClients() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-s.maintenanceDone:
			return
		case now := <-ticker.C:
			if err := s.pruneInactiveClients(now); err != nil {
				log.Printf("prune inactive clients: %v", err)
			}
		}
	}
}
