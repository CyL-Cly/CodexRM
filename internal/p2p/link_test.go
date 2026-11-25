package p2p

import (
	"bytes"
	"encoding/binary"
	"testing"
)

type directFrameRecorder struct {
	buf    bytes.Buffer
	writes int
}

func (w *directFrameRecorder) Write(p []byte) (int, error) {
	w.writes++
	return w.buf.Write(p)
}

func TestWriteDirectFrame(t *testing.T) {
	var scratch [4 + smallDirectPayload]byte
	for _, tc := range []struct {
		name         string
		size, writes int
	}{
		{"small RPC", smallDirectPayload, 1},
		{"large response", smallDirectPayload + 1, 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			payload := bytes.Repeat([]byte{0x42}, tc.size)
			var w directFrameRecorder
			if err := writeDirectFrame(&w, scratch[:], payload); err != nil {
				t.Fatal(err)
			}
			if w.writes != tc.writes {
				t.Fatalf("writes = %d, want %d", w.writes, tc.writes)
			}
			got := w.buf.Bytes()
			if len(got) != len(payload)+4 || binary.BigEndian.Uint32(got[:4]) != uint32(len(payload)) || !bytes.Equal(got[4:], payload) {
				t.Fatal("direct wire format changed")
			}
		})
	}
}
