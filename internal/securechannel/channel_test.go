package securechannel

import (
	"bytes"
	"testing"
)

const testClient = "cli_01234567-89ab-cdef-0123-456789abcdef"

func testChannels(t *testing.T) (*Channel, *Channel) {
	t.Helper()
	key, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	init, err := Handshake(true, testClient, "", key)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := Handshake(false, testClient, "", key)
	if err != nil {
		t.Fatal(err)
	}
	hello, _, _, err := init.WriteMessage(nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err = resp.ReadMessage(nil, hello); err != nil {
		t.Fatal(err)
	}
	reply, r1, r2, err := resp.WriteMessage(nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, i1, i2, err := init.ReadMessage(nil, reply)
	if err != nil {
		t.Fatal(err)
	}
	return InitiatorChannel(i1, i2), ResponderChannel(r1, r2)
}

func TestChannelRejectsModifiedReplayAndTruncatedRecords(t *testing.T) {
	for _, size := range []int{0, 5, recordSize, recordSize + 1, 2 * recordSize} {
		plain := bytes.Repeat([]byte("x"), size)
		left, right := testChannels(t)
		wire, err := left.Seal(plain)
		if err != nil {
			t.Fatal(err)
		}
		got, err := right.Open(wire)
		if err != nil || !bytes.Equal(got, plain) {
			t.Fatalf("size %d: %v", size, err)
		}
		if _, err := right.Open(wire); err == nil {
			t.Fatal("accepted replay")
		}
		left, right = testChannels(t)
		wire, _ = left.Seal(plain)
		wire[len(wire)-1] ^= 1
		if _, err := right.Open(wire); err == nil {
			t.Fatal("accepted modified tag")
		}
		if size > recordSize {
			left, right = testChannels(t)
			wire, _ = left.Seal(plain)
			if _, err := right.Open(wire[:recordSize+16]); err == nil {
				t.Fatal("accepted truncated frame")
			}
		}
	}
}

func TestPairingRequiresSecretAndBindsClient(t *testing.T) {
	key, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	const route = "route_01234567-89ab-cdef-0123-456789abcdef"
	parsedRoute, parsedKey, err := ParsePairingCode(PairingCode(route, key))
	if err != nil || parsedKey != key || parsedRoute != route {
		t.Fatal("pairing code round trip failed")
	}
	init, _ := Handshake(true, testClient, route, key)
	hello, _, _, err := init.WriteMessage(nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		client, route string
		key           Key
	}{
		{testClient, route, Key{}},
		{"cli_11111111-89ab-cdef-0123-456789abcdef", route, key},
		{testClient, "", key},
	} {
		resp, _ := Handshake(false, tc.client, tc.route, tc.key)
		if _, _, _, err := resp.ReadMessage(nil, hello); err == nil {
			t.Fatal("accepted wrong secret or routing context")
		}
	}
	pin := SignDirectPin(key, testClient, "attempt", "agent", "pin")
	if VerifyDirectPin(key, testClient, "attempt", "client", "pin", pin) || VerifyDirectPin(key, testClient, "attempt", "agent", "other", pin) {
		t.Fatal("accepted substituted direct offer")
	}
}
