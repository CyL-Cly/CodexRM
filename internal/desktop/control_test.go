package desktop

import (
	"bytes"
	"testing"
)

func TestDesktopMessagesRoundTrip(t *testing.T) {
	pointer := Pointer{Action: PointerDown, ID: 1, X: 0, Y: 65535, Buttons: ButtonLeft}
	key := Key{Action: KeyUnicode, Code: 0x4e2d}
	frame, err := FramePayload(2, 1, []byte{0xff, 0xd8, 0xff})
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	for _, message := range []struct {
		kind    byte
		payload []byte
	}{
		{MsgPointer, pointer.Payload()},
		{MsgKey, key.Payload()},
		{MsgMode, []byte{ModeTablet}},
		{MsgFrame, frame},
	} {
		if err := WriteMessage(&buf, message.kind, message.payload); err != nil {
			t.Fatal(err)
		}
	}
	wantPrefix := []byte{MsgPointer, 0, 0, 0, 7, PointerDown, 1, 0, 0, 0xff, 0xff, ButtonLeft}
	if !bytes.HasPrefix(buf.Bytes(), wantPrefix) {
		t.Fatalf("pointer message = %x", buf.Bytes()[:len(wantPrefix)])
	}
	for _, want := range []struct {
		kind byte
		body []byte
	}{
		{MsgPointer, pointer.Payload()},
		{MsgKey, key.Payload()},
		{MsgMode, []byte{ModeTablet}},
		{MsgFrame, frame},
	} {
		kind, payload, err := ReadMessage(&buf)
		if err != nil || kind != want.kind || !bytes.Equal(payload, want.body) {
			t.Fatalf("message %d: kind=%d err=%v payload=%x", want.kind, kind, err, payload)
		}
	}
	gotPointer, err := DecodePointer(pointer.Payload())
	if err != nil || gotPointer != pointer {
		t.Fatalf("pointer = %+v, %v", gotPointer, err)
	}
	gotKey, err := DecodeKey(key.Payload())
	if err != nil || gotKey != key {
		t.Fatalf("key = %+v, %v", gotKey, err)
	}
	width, height, jpeg, err := DecodeFrame(frame)
	if err != nil || width != 2 || height != 1 || !bytes.Equal(jpeg, []byte{0xff, 0xd8, 0xff}) {
		t.Fatalf("frame = %d %d %x %v", width, height, jpeg, err)
	}
}

func TestDesktopMessageRejectsTruncationAndOverflow(t *testing.T) {
	if _, _, err := ReadMessage(bytes.NewReader([]byte{MsgKey, 0, 0, 0, 3, 1})); err == nil {
		t.Fatal("truncated message was accepted")
	}
	if _, err := FramePayload(0, 1, []byte{1}); err == nil {
		t.Fatal("empty frame dimension was accepted")
	}
	if _, err := DecodePointer([]byte{1}); err == nil {
		t.Fatal("short pointer was accepted")
	}
}
