// Package securechannel authenticates CodexRM endpoints and protects their
// ordered application messages independently of the relay transport.
package securechannel

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"

	"github.com/flynn/noise"
	"github.com/google/uuid"
)

const (
	CodePrefix = "rm2"
	KeySize    = 32
	// An individual Noise ciphertext is limited to 65535 bytes. CodexRM
	// carries larger application messages as independently sealed records.
	recordSize = noise.MaxMsgLen - 16
	// Bound the aggregate and leave room for JSON/base64 on the relay.
	MaxPlaintext = 11 << 20
)

type Key [KeySize]byte

func NewKey() (key Key, err error) {
	_, err = rand.Read(key[:])
	return key, err
}

// ValidRoute checks the public, relay-assigned portion of a pairing code.
// Routes are not authentication credentials, even though they are random.
func ValidRoute(route string) bool {
	if !strings.HasPrefix(route, "route_") {
		return false
	}
	id, err := uuid.Parse(strings.TrimPrefix(route, "route_"))
	return err == nil && "route_"+id.String() == route
}

// SignDirectPin binds the QUIC certificate to the device key and current
// P2P attempt, even when the relay modifies the candidate list.
func SignDirectPin(key Key, client, attempt, role, pin string) string {
	mac := hmac.New(sha256.New, key[:])
	_, _ = mac.Write([]byte("CodexRM direct pin v2\n" + client + "\n" + attempt + "\n" + role + "\n" + pin))
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func VerifyDirectPin(key Key, client, attempt, role, pin, signature string) bool {
	return hmac.Equal([]byte(SignDirectPin(key, client, attempt, role, pin)), []byte(signature))
}

func ValidClient(client string) bool {
	if !strings.HasPrefix(client, "cli_") {
		return false
	}
	id, err := uuid.Parse(strings.TrimPrefix(client, "cli_"))
	return err == nil && "cli_"+id.String() == client
}

func PairingCode(route string, key Key) string {
	return CodePrefix + "." + route + "." + base64.RawURLEncoding.EncodeToString(key[:])
}

func ParsePairingCode(code string) (string, Key, error) {
	p := strings.Split(code, ".")
	var key Key
	if len(p) != 3 || p[0] != CodePrefix || !ValidRoute(p[1]) || len(p[2]) != 43 {
		return "", key, errors.New("invalid secure pairing code")
	}
	b, err := base64.RawURLEncoding.Strict().DecodeString(p[2])
	if err != nil || len(b) != KeySize {
		return "", key, errors.New("invalid secure pairing secret")
	}
	copy(key[:], b)
	return p[1], key, nil
}

// Noise revision 34 NNpsk0 uses a full-entropy PSK, never a short password.
// The prologue binds the relay's untrusted routing labels, separates first
// pairing from reconnection, and prevents cross-protocol use of the key.
func Handshake(initiator bool, client, route string, key Key) (*noise.HandshakeState, error) {
	if !ValidClient(client) || (route != "" && !ValidRoute(route)) {
		return nil, errors.New("invalid secure channel endpoint")
	}
	return noise.NewHandshakeState(noise.Config{
		CipherSuite: noise.NewCipherSuite(noise.DH25519, noise.CipherAESGCM, noise.HashSHA256),
		Pattern:     noise.HandshakeNN, Initiator: initiator,
		PresharedKey: key[:], PresharedKeyPlacement: 0,
		Prologue: []byte("CodexRM relay e2ee v2\n" + client + "\n" + route),
	})
}

// Channel owns its two cipher states. The caller must serialize writes with
// transmission, serialize reads in wire order, and discard it upon any error.
// A channel is never resumed after a disconnect.
type Channel struct {
	send, receive *noise.CipherState
}

func InitiatorChannel(toAgent, toClient *noise.CipherState) *Channel {
	return &Channel{send: toAgent, receive: toClient}
}

func ResponderChannel(toAgent, toClient *noise.CipherState) *Channel {
	return &Channel{send: toClient, receive: toAgent}
}

// The associated data marks the final record. A relay cannot drop a suffix,
// concatenate packets, or reorder records without invalidating a tag.
func recordAD(final bool) []byte {
	if final {
		return []byte{1}
	}
	return []byte{0}
}

func (c *Channel) Seal(plaintext []byte) ([]byte, error) {
	if len(plaintext) > MaxPlaintext {
		return nil, errors.New("secure relay payload too large")
	}
	out := make([]byte, 0, len(plaintext)+16*(len(plaintext)/recordSize+1))
	for len(plaintext) > recordSize {
		var err error
		out, err = c.send.Encrypt(out, recordAD(false), plaintext[:recordSize])
		if err != nil {
			return nil, err
		}
		plaintext = plaintext[recordSize:]
	}
	return c.send.Encrypt(out, recordAD(true), plaintext)
}

func (c *Channel) Open(ciphertext []byte) ([]byte, error) {
	if len(ciphertext) < 16 || len(ciphertext) > MaxPlaintext+16*(MaxPlaintext/recordSize+1) {
		return nil, errors.New("invalid secure relay frame size")
	}
	out := make([]byte, 0, len(ciphertext)-16)
	for len(ciphertext) > noise.MaxMsgLen {
		var err error
		out, err = c.receive.Decrypt(out, recordAD(false), ciphertext[:noise.MaxMsgLen])
		if err != nil {
			return nil, err
		}
		ciphertext = ciphertext[noise.MaxMsgLen:]
	}
	if len(ciphertext) < 16 || len(out)+len(ciphertext)-16 > MaxPlaintext {
		return nil, errors.New("invalid secure relay record size")
	}
	return c.receive.Decrypt(out, recordAD(true), ciphertext)
}
