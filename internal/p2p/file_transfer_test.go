package p2p

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"
)

func TestReceiveFilePayloadRequiresExactCleanEOF(t *testing.T) {
	reset := errors.New("stream reset")
	for _, test := range []struct {
		name, contents string
		size           int64
		trailer, want  error
	}{
		{name: "empty"},
		{name: "exact", contents: "abc", size: 3},
		{name: "truncated", contents: "ab", size: 3, want: io.ErrUnexpectedEOF},
		{name: "reset at exact length", contents: "abc", size: 3, trailer: reset, want: reset},
	} {
		t.Run(test.name, func(t *testing.T) {
			var raw io.Reader = strings.NewReader(test.contents)
			if test.trailer != nil {
				raw = io.MultiReader(raw, fileTransferErrorReader{test.trailer})
			}
			var got bytes.Buffer
			if err := ReceiveFilePayload(&got, raw, test.size); !errors.Is(err, test.want) {
				t.Fatalf("receive = %v, want %v", err, test.want)
			}
			if test.want == nil && got.String() != test.contents {
				t.Fatalf("got bytes %q, want %q", got.String(), test.contents)
			}
		})
	}
	if err := ReceiveFilePayload(io.Discard, strings.NewReader("abc!"), 3); err == nil {
		t.Fatal("accepted bytes past the advertised length")
	}
}

type fileTransferErrorReader struct{ err error }

func (r fileTransferErrorReader) Read([]byte) (int, error) { return 0, r.err }
