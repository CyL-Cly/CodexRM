package server

import (
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type rateEntry struct {
	tokens float64
	last   time.Time
}

type ipRateLimiter struct {
	mu           sync.Mutex
	entries      map[string]rateEntry
	refillSecond float64
	burst        float64
	now          func() time.Time
	lastSweep    time.Time
}

func newIPRateLimiter(perMinute, burst int) *ipRateLimiter {
	return &ipRateLimiter{
		entries:      map[string]rateEntry{},
		refillSecond: float64(perMinute) / 60,
		burst:        float64(burst),
		now:          time.Now,
	}
}

func (l *ipRateLimiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if l.lastSweep.IsZero() {
		l.lastSweep = now
	}
	if now.Sub(l.lastSweep) >= 5*time.Minute {
		for ip, entry := range l.entries {
			if now.Sub(entry.last) >= 15*time.Minute {
				delete(l.entries, ip)
			}
		}
		l.lastSweep = now
	}
	entry, exists := l.entries[key]
	if !exists {
		entry = rateEntry{tokens: l.burst, last: now}
	}
	if elapsed := now.Sub(entry.last).Seconds(); elapsed > 0 {
		entry.tokens = math.Min(l.burst, entry.tokens+elapsed*l.refillSecond)
		entry.last = now
	}
	if entry.tokens >= 1 {
		entry.tokens--
		l.entries[key] = entry
		return true, 0
	}
	l.entries[key] = entry
	retry := time.Duration(math.Ceil((1 - entry.tokens) / l.refillSecond * float64(time.Second)))
	if retry < time.Second {
		retry = time.Second
	}
	return false, retry
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(strings.TrimSpace(host))
	// The supplied Nginx configuration overwrites X-Real-IP. Direct public
	// clients cannot spoof their address to select another rate-limit bucket.
	if ip != nil && ip.IsLoopback() {
		if forwarded := net.ParseIP(strings.TrimSpace(r.Header.Get("X-Real-IP"))); forwarded != nil {
			return forwarded.String()
		}
	}
	if ip == nil {
		return "unknown"
	}
	return ip.String()
}

func rateLimit(limiter *ipRateLimiter, next http.HandlerFunc) http.HandlerFunc {
	return rateLimitKey(limiter, clientIP, next)
}

func rateLimitKey(limiter *ipRateLimiter, key func(*http.Request) string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		allowed, retry := limiter.allow(key(r))
		if !allowed {
			w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(retry.Seconds()))))
			fail(w, http.StatusTooManyRequests, "too many requests")
			return
		}
		next(w, r)
	}
}
