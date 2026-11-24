package desktop

import (
	"encoding/binary"
	"errors"
	"io"
)

const StreamMagic byte = 0x01

const (
	MsgFrame   byte = 1
	MsgPointer byte = 2
	MsgKey     byte = 3
	MsgMode    byte = 4
)

const (
	PointerDown byte = 1
	PointerMove byte = 2
	PointerUp   byte = 3
)

const (
	KeyDown    byte = 1
	KeyUp      byte = 2
	KeyUnicode byte = 3
)

const (
	ButtonLeft   byte = 1
	ButtonRight  byte = 2
	ButtonMiddle byte = 4
)

const (
	ModeMouse  byte = 0
	ModeTablet byte = 1
)

const MaxMessage = 2 << 20

var (
	errMessageSize = errors.New("invalid desktop message size")
	errMessageKind = errors.New("invalid desktop message")
)

type Pointer struct {
	Action  byte
	ID      byte
	X, Y    uint16
	Buttons byte
}

type Key struct {
	Action byte
	Code   uint16
}

type Frame struct {
	JPEG               []byte
	Width, Height      int
	OriginX, OriginY   int
	VirtualW, VirtualH int
}

func (p Pointer) Payload() []byte {
	var out [7]byte
	out[0] = p.Action
	out[1] = p.ID
	binary.BigEndian.PutUint16(out[2:], p.X)
	binary.BigEndian.PutUint16(out[4:], p.Y)
	out[6] = p.Buttons
	return out[:]
}

func DecodePointer(payload []byte) (Pointer, error) {
	if len(payload) != 7 {
		return Pointer{}, errMessageKind
	}
	return Pointer{
		Action: payload[0], ID: payload[1],
		X: binary.BigEndian.Uint16(payload[2:]), Y: binary.BigEndian.Uint16(payload[4:]),
		Buttons: payload[6],
	}, nil
}

func (k Key) Payload() []byte {
	var out [3]byte
	out[0] = k.Action
	binary.BigEndian.PutUint16(out[1:], k.Code)
	return out[:]
}

func DecodeKey(payload []byte) (Key, error) {
	if len(payload) != 3 {
		return Key{}, errMessageKind
	}
	return Key{Action: payload[0], Code: binary.BigEndian.Uint16(payload[1:])}, nil
}

func FramePayload(width, height int, jpeg []byte) ([]byte, error) {
	if width <= 0 || height <= 0 || width > 65535 || height > 65535 || len(jpeg) == 0 || 4+len(jpeg) > MaxMessage {
		return nil, errMessageSize
	}
	out := make([]byte, 4+len(jpeg))
	binary.BigEndian.PutUint16(out[0:], uint16(width))
	binary.BigEndian.PutUint16(out[2:], uint16(height))
	copy(out[4:], jpeg)
	return out, nil
}

func DecodeFrame(payload []byte) (width, height int, jpeg []byte, err error) {
	if len(payload) < 5 {
		return 0, 0, nil, errMessageKind
	}
	width = int(binary.BigEndian.Uint16(payload[0:]))
	height = int(binary.BigEndian.Uint16(payload[2:]))
	jpeg = payload[4:]
	if width == 0 || height == 0 {
		return 0, 0, nil, errMessageKind
	}
	return width, height, jpeg, nil
}

func WriteMessage(w io.Writer, kind byte, payload []byte) error {
	if len(payload) > MaxMessage {
		return errMessageSize
	}
	var header [5]byte
	header[0] = kind
	binary.BigEndian.PutUint32(header[1:], uint32(len(payload)))
	if err := writeFull(w, header[:]); err != nil {
		return err
	}
	return writeFull(w, payload)
}

func ReadMessage(r io.Reader) (byte, []byte, error) {
	var header [5]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return 0, nil, err
	}
	size := binary.BigEndian.Uint32(header[1:])
	if size > MaxMessage {
		return 0, nil, errMessageSize
	}
	payload := make([]byte, size)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	return header[0], payload, nil
}

func writeFull(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if n > 0 {
			data = data[n:]
		}
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}
