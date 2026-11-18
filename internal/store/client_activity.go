package store

import (
	"maps"
	"time"
)

const ClientRetention = 7 * 24 * time.Hour

func clientInactive(c *ClientRecord, now time.Time) bool {
	last := c.LastConnectedAt
	if last.IsZero() {
		last = c.RetentionStartedAt
	}
	return !last.IsZero() && now.Sub(last) > ClientRetention
}

// RecordClientConnection persists successful connections and live heartbeats.
// Heartbeat writes are limited to once a minute per device.
func (s *Store) RecordClientConnection(clientID, ip string, now time.Time, heartbeat bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.clients[clientID]
	if !ok {
		return ErrNotFound
	}
	if heartbeat && c.LastIP == ip && now.Sub(c.LastConnectedAt) < time.Minute {
		return nil
	}
	updated := *c
	updated.LastConnectedAt, updated.LastIP = now.UTC(), ip
	clients := maps.Clone(s.clients)
	clients[clientID] = &updated
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: s.pairings}); err != nil {
		return err
	}
	s.clients = clients
	return nil
}

// PruneInactiveClients commits deletion before publishing it to live readers.
// active is supplied by the relay while holding its peer registration lock.
func (s *Store) PruneInactiveClients(now time.Time, active map[string]bool) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	clients := maps.Clone(s.clients)
	for id, c := range clients {
		if !active[id] && clientInactive(c, now) {
			delete(clients, id)
		}
	}
	removed := len(s.clients) - len(clients)
	if removed == 0 {
		return 0, nil
	}
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: s.pairings}); err != nil {
		return 0, err
	}
	s.clients = clients
	return removed, nil
}
