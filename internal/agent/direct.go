package agent

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"sync/atomic"
	"time"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/gorilla/websocket"
)

type sendEnvelope func(protocol.Envelope) error

func (send sendEnvelope) write(env protocol.Envelope) error {
	if send == nil {
		return errors.New("transport closed")
	}
	return send(env)
}

type directSession struct {
	id, client      string
	ctx             context.Context
	cancel          context.CancelFunc
	offer           chan protocol.P2PSignal
	ready, activate chan struct{}
	out             chan []byte
	files           directFiles
	// Protected by Agent.eventMu, which also serializes publications with switching.
	active bool
	lease  time.Time
}

func (d *directSession) send(env protocol.Envelope) error {
	b, err := json.Marshal(env)
	if err != nil {
		return err
	}
	if len(b) > protocol.MaxEnvelopeSize {
		d.cancel()
		return errors.New("direct frame too large")
	}
	if d.ctx.Err() != nil {
		return d.ctx.Err()
	}
	select {
	case d.out <- b:
		return nil
	default:
		d.cancel()
		return errors.New("direct send queue full")
	}
}

type relayEventQueue struct {
	conn   *websocket.Conn
	frames chan []byte
	bytes  atomic.Int64
}

func newRelayEventQueue(conn *websocket.Conn) *relayEventQueue {
	return &relayEventQueue{conn: conn, frames: make(chan []byte, 64)}
}

func (q *relayEventQueue) enqueue(env protocol.Envelope) {
	if len(env.Message) > protocol.MaxEnvelopeSize {
		log.Print("discarded relay event exceeding the wire limit")
		return
	}
	env.Message, _ = protocol.WithoutInlineFiles(env.Message)
	frame, err := json.Marshal(env)
	if err != nil || len(frame) > protocol.MaxEnvelopeSize {
		log.Print("discarded relay event exceeding the wire limit")
		return
	}
	size := int64(len(frame))
	if q.bytes.Add(size) <= 2*protocol.MaxEnvelopeSize {
		select {
		case q.frames <- frame:
			return
		default:
		}
	}
	q.bytes.Add(-size)
	// Reconnect to refresh client state when the relay cannot keep up.
	_ = q.conn.Close()
}

func (a *Agent) writeRelayEvents(ctx context.Context, queue *relayEventQueue) {
	for ctx.Err() == nil {
		select {
		case <-ctx.Done():
			return
		case frame := <-queue.frames:
			err := a.writeRelayFrame(queue.conn, frame)
			queue.bytes.Add(-int64(len(frame)))
			if err != nil {
				return
			}
		}
	}
}

func (a *Agent) publishEvent(env protocol.Envelope) {
	a.eventMu.Lock()
	defer a.eventMu.Unlock()
	queue := a.relayEvents
	if queue == nil {
		return
	}
	delivered := 0
	for id, d := range a.direct {
		if d.active && time.Now().Before(d.lease) && (env.ClientID == "" || env.ClientID == id) {
			if d.send(env) == nil {
				if env.ClientID != "" {
					return
				}
				env.SkipDirect = append(env.SkipDirect, d.id)
				if a.eventClients[id] {
					delivered++
				}
			}
		}
	}
	if env.ClientID == "" && a.eventClients != nil && delivered == len(a.eventClients) {
		return
	}
	queue.enqueue(env)
}

func (a *Agent) directSignal(ctx context.Context, app *appserver.Client, conn *websocket.Conn, slots chan struct{}, env protocol.Envelope) {
	sig := env.P2P
	if sig == nil || sig.Version != protocol.P2PVersion {
		return
	}
	if sig.Kind == "diagnose_start" {
		if env.ClientID == "" || len(sig.ID) != 36 || len(sig.STUN) == 0 || len(sig.STUN) > 4 {
			return
		}
		select {
		case slots <- struct{}{}:
			go func() {
				defer func() { <-slots }()
				report := p2p.DiagnoseNAT(ctx, sig.STUN)
				if ctx.Err() != nil {
					return
				}
				result := protocol.P2P("diagnose_result", sig.ID)
				result.NATResult = &report
				_ = a.write(conn, protocol.Envelope{Type: protocol.TypeP2P, ClientID: env.ClientID, P2P: result})
			}()
		default:
			// Do not block the WebSocket reader when the Agent is busy.
			return
		}
		return
	}
	a.eventMu.Lock()
	defer a.eventMu.Unlock()
	switch sig.Kind {
	case "routing":
		a.eventClients = make(map[string]bool, len(sig.Clients))
		for _, id := range sig.Clients {
			a.eventClients[id] = true
		}
		return
	case "join":
		if a.eventClients != nil {
			a.eventClients[env.ClientID] = true
		}
		return
	case "leave":
		delete(a.eventClients, env.ClientID)
		return
	}
	if env.ClientID == "" {
		return
	}
	d := a.direct[env.ClientID]
	if sig.Kind == "start" {
		if d != nil {
			d.cancel()
			delete(a.direct, env.ClientID)
		}
		if len(a.direct) >= protocol.P2PMaxSessions {
			_ = a.write(conn, protocol.Envelope{Type: protocol.TypeP2P, ClientID: env.ClientID, P2P: protocol.P2P("stop", sig.ID)})
			return
		}
		life, cancel := context.WithCancel(ctx)
		d = &directSession{id: sig.ID, client: env.ClientID, ctx: life, cancel: cancel, offer: make(chan protocol.P2PSignal, 1), ready: make(chan struct{}, 1), activate: make(chan struct{}, 1), out: make(chan []byte, 8)}
		if a.direct == nil {
			a.direct = map[string]*directSession{}
		}
		a.direct[env.ClientID] = d
		go a.runDirect(app, conn, slots, d, *sig)
		return
	}
	if d == nil || d.id != sig.ID {
		return
	}
	switch sig.Kind {
	case "offer":
		select {
		case d.offer <- *sig:
		default:
		}
	case "ready":
		select {
		case d.ready <- struct{}{}:
		default:
		}
	case "activate":
		select {
		case d.activate <- struct{}{}:
		default:
		}
	case "lease":
		if d.active && time.Now().Before(d.lease) {
			d.lease = time.Now().Add(protocol.P2PLease)
		}
	case "stop":
		delete(a.direct, d.client)
		d.cancel()
	}
}

func (a *Agent) runDirect(app *appserver.Client, conn *websocket.Conn, slots chan struct{}, d *directSession, start protocol.P2PSignal) {
	signal := func(sig *protocol.P2PSignal) error {
		return a.write(conn, protocol.Envelope{Type: protocol.TypeP2P, ClientID: d.client, P2P: sig})
	}
	stop := protocol.P2P("stop", d.id)
	defer func() {
		d.cancel()
		d.files.close()
		a.clearDirectShares(d)
		a.eventMu.Lock()
		defer a.eventMu.Unlock()
		if a.direct[d.client] == d {
			delete(a.direct, d.client)
			_ = signal(stop)
		}
	}()
	attempt, cancel := context.WithTimeout(d.ctx, protocol.P2PAttempt)
	defer cancel()
	prepared, offer, err := p2p.Prepare(attempt, true, start)
	if err != nil {
		log.Printf("p2p negotiate: %v", err)
		return
	}
	defer prepared.Close()
	if signal(offer) != nil {
		return
	}
	var remote protocol.P2PSignal
	select {
	case <-attempt.Done():
		return
	case remote = <-d.offer:
	}
	link, err := prepared.Connect(attempt, remote, d.ready, func() error { return signal(protocol.P2P("ready", d.id)) })
	if err != nil {
		if errors.Is(err, p2p.ErrDualUnpredictableNAT) {
			stop.Reason = protocol.P2PReasonDualUnpredictableNAT
		}
		log.Printf("p2p negotiate: %v", err)
		return
	}
	defer link.Close()
	select {
	case <-attempt.Done():
		return
	case <-d.activate:
	}
	a.eventMu.Lock()
	if a.direct[d.client] != d || attempt.Err() != nil || signal(protocol.P2P("active", d.id)) != nil {
		a.eventMu.Unlock()
		return
	}
	d.active, d.lease = true, time.Now().Add(protocol.P2PLease)
	a.eventMu.Unlock()
	cancel()
	log.Printf("p2p direct active (lan=%t ipv6=%t bound=%t)", link.IsLAN(), link.IsIPv6(), link.UsesBoundSource())
	go link.WatchQuality(d.ctx)
	go a.serveDirectShares(d, link)
	go func() {
		<-d.ctx.Done()
		link.Close()
	}()
	go func() {
		defer d.cancel()
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			select {
			case <-d.ctx.Done():
				return
			case b := <-d.out:
				if link.Write(b) != nil {
					return
				}
			case <-tick.C:
				a.eventMu.Lock()
				live := time.Now().Before(d.lease)
				a.eventMu.Unlock()
				if !live {
					return
				}
			}
		}
	}()
	for {
		b, err := link.Read()
		if err != nil {
			return
		}
		var env protocol.Envelope
		if json.Unmarshal(b, &env) != nil || env.Type != protocol.TypeClientMessage || !json.Valid(env.Message) {
			return
		}
		a.eventMu.Lock()
		live := a.direct[d.client] == d && d.ctx.Err() == nil && time.Now().Before(d.lease)
		a.eventMu.Unlock()
		if !live {
			return
		}
		// The direct transport authenticates the socket, never the caller-supplied envelope.
		a.dispatch(d.ctx, app, d.send, slots, protocol.Envelope{Type: protocol.TypeClientMessage, ClientID: d.client, StreamID: env.StreamID, Message: env.Message}, d)
	}
}
