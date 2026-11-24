//go:build windows

package desktop

import (
	"bytes"
	"encoding/binary"
	"errors"
	"image"
	"image/jpeg"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32                 = windows.NewLazySystemDLL("user32.dll")
	gdi32                  = windows.NewLazySystemDLL("gdi32.dll")
	procGetDC              = user32.NewProc("GetDC")
	procReleaseDC          = user32.NewProc("ReleaseDC")
	procGetSystemMetrics   = user32.NewProc("GetSystemMetrics")
	procGetCursorInfo      = user32.NewProc("GetCursorInfo")
	procDrawIconEx         = user32.NewProc("DrawIconEx")
	procSendInput          = user32.NewProc("SendInput")
	procInitializeTouch    = user32.NewProc("InitializeTouchInjection")
	procInjectTouch        = user32.NewProc("InjectTouchInput")
	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procStretchBlt         = gdi32.NewProc("StretchBlt")
	procSetStretchBltMode  = gdi32.NewProc("SetStretchBltMode")
	procDeleteDC           = gdi32.NewProc("DeleteDC")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
)

const (
	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCXVirtualScreen = 78
	smCYVirtualScreen = 79
	srcCopy           = 0x00CC0020
	colorOnColor      = 3
	cursorShowing     = 0x0001
	diNormal          = 0x0003

	inputKindMouse = 0
	inputKindKey   = 1

	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseVirtual    = 0x4000
	mouseAbsolute   = 0x8000
	keyUp           = 0x0002
	keyUnicode      = 0x0004
	keyExtended     = 0x0001

	ptTouch            = 2
	touchFeedbackNone  = 3
	touchMaskContact   = 0x0001
	touchMaskPressure  = 0x0004
	pointerFlagInRange = 0x00000002
	pointerFlagContact = 0x00000004
	pointerFlagPrimary = 0x00002000
	pointerFlagDown    = 0x00010000
	pointerFlagUpdate  = 0x00020000
	pointerFlagUp      = 0x00040000
)

type inputMouse struct {
	Type  uint32
	_     uint32
	Dx    int32
	Dy    int32
	Data  uint32
	Flags uint32
	Time  uint32
	_     uint32
	Extra uintptr
}

type inputKey struct {
	Type  uint32
	_     uint32
	Vk    uint16
	Scan  uint16
	Flags uint32
	Time  uint32
	_     uint32
	Extra uintptr
	_     uint64
}

type cursorInfo struct {
	Size   uint32
	Flags  uint32
	Cursor uintptr
	X      int32
	Y      int32
}

type pointerTouch struct {
	Type         uint32
	ID           uint32
	Frame        uint32
	Flags        uint32
	Source       uintptr
	Target       uintptr
	PixelX       int32
	PixelY       int32
	HimiX        int32
	HimiY        int32
	RawX         int32
	RawY         int32
	RawHimiX     int32
	RawHimiY     int32
	Time         uint32
	History      uint32
	InputData    int32
	KeyStates    uint32
	Performance  uint64
	ButtonChange uint32
	_            uint32
	TouchFlags   uint32
	TouchMask    uint32
	ContactL     int32
	ContactT     int32
	ContactR     int32
	ContactB     int32
	RawL         int32
	RawT         int32
	RawR         int32
	RawB         int32
	Orientation  uint32
	Pressure     uint32
}

func Supported() bool { return true }

func CaptureJPEG(maxEdge, quality int) (Frame, error) {
	if maxEdge < 640 {
		maxEdge = 640
	}
	if maxEdge > 1920 {
		maxEdge = 1920
	}
	if quality < 30 {
		quality = 30
	}
	if quality > 85 {
		quality = 85
	}
	originX := metric(smXVirtualScreen)
	originY := metric(smYVirtualScreen)
	width := metric(smCXVirtualScreen)
	height := metric(smCYVirtualScreen)
	if width <= 0 || height <= 0 {
		return Frame{}, errors.New("desktop size is unavailable")
	}
	destW, destH := width, height
	edge := destW
	if destH > edge {
		edge = destH
	}
	if edge > maxEdge {
		destW = destW * maxEdge / edge
		destH = destH * maxEdge / edge
	}
	if destW < 1 {
		destW = 1
	}
	if destH < 1 {
		destH = 1
	}
	screen, _, _ := procGetDC.Call(0)
	if screen == 0 {
		return Frame{}, errors.New("desktop device context is unavailable")
	}
	defer procReleaseDC.Call(0, screen)
	memory, _, _ := procCreateCompatibleDC.Call(screen)
	if memory == 0 {
		return Frame{}, errors.New("desktop memory context is unavailable")
	}
	defer procDeleteDC.Call(memory)
	var header [40]byte
	binary.LittleEndian.PutUint32(header[0:], 40)
	binary.LittleEndian.PutUint32(header[4:], uint32(int32(destW)))
	binary.LittleEndian.PutUint32(header[8:], uint32(int32(-destH)))
	binary.LittleEndian.PutUint16(header[12:], 1)
	binary.LittleEndian.PutUint16(header[14:], 32)
	var bits unsafe.Pointer
	bitmap, _, _ := procCreateDIBSection.Call(screen, uintptr(unsafe.Pointer(&header[0])), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if bitmap == 0 || bits == nil {
		return Frame{}, errors.New("desktop bitmap is unavailable")
	}
	defer procDeleteObject.Call(bitmap)
	previous, _, _ := procSelectObject.Call(memory, bitmap)
	if previous != 0 {
		defer procSelectObject.Call(memory, previous)
	}
	procSetStretchBltMode.Call(memory, colorOnColor)
	if ok, _, _ := procStretchBlt.Call(memory, 0, 0, uintptr(destW), uintptr(destH), screen, uintptr(int32(originX)), uintptr(int32(originY)), uintptr(int32(width)), uintptr(int32(height)), srcCopy); ok == 0 {
		return Frame{}, errors.New("desktop capture failed")
	}
	drawCursor(memory, originX, originY, width, height, destW, destH)
	img := image.NewRGBA(image.Rect(0, 0, destW, destH))
	src := unsafe.Slice((*byte)(bits), destW*destH*4)
	for i := 0; i < destW*destH; i++ {
		img.Pix[i*4] = src[i*4+2]
		img.Pix[i*4+1] = src[i*4+1]
		img.Pix[i*4+2] = src[i*4]
		img.Pix[i*4+3] = 255
	}
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, img, &jpeg.Options{Quality: quality}); err != nil {
		return Frame{}, err
	}
	return Frame{JPEG: encoded.Bytes(), Width: destW, Height: destH, OriginX: originX, OriginY: originY, VirtualW: width, VirtualH: height}, nil
}

func drawCursor(memory uintptr, originX, originY, width, height, destW, destH int) {
	info := cursorInfo{Size: uint32(unsafe.Sizeof(cursorInfo{}))}
	if ok, _, _ := procGetCursorInfo.Call(uintptr(unsafe.Pointer(&info))); ok == 0 || info.Flags&cursorShowing == 0 || info.Cursor == 0 {
		return
	}
	x := int((int(info.X) - originX) * destW / width)
	y := int((int(info.Y) - originY) * destH / height)
	procDrawIconEx.Call(memory, uintptr(int32(x)), uintptr(int32(y)), info.Cursor, 0, 0, 0, 0, diNormal)
}

func metric(index uintptr) int {
	value, _, _ := procGetSystemMetrics.Call(index)
	return int(int32(value))
}

var inputState struct {
	sync.Mutex
	left, right, middle bool
	keys                map[uint16]struct{}
	touches             map[byte]pointerTouch
	touchReady          bool
	tabletFailed        bool
}

func ApplyPointer(pointer Pointer, mode byte, frame Frame) error {
	inputState.Lock()
	defer inputState.Unlock()
	if mode == ModeTablet && !inputState.tabletFailed {
		if err := applyTouch(pointer, frame); err == nil {
			return nil
		}
		inputState.tabletFailed = true
		clearTouches()
	}
	return applyMouse(pointer)
}

func ApplyKey(key Key) error {
	inputState.Lock()
	defer inputState.Unlock()
	if key.Action == KeyUnicode {
		down := inputKey{Type: inputKindKey, Scan: key.Code, Flags: keyUnicode}
		up := down
		up.Flags |= keyUp
		return sendKeys([]inputKey{down, up})
	}
	if key.Action != KeyDown && key.Action != KeyUp {
		return errors.New("unknown key action")
	}
	flags := uint32(0)
	if key.Action == KeyUp {
		flags |= keyUp
	}
	if extendedKey(key.Code) {
		flags |= keyExtended
	}
	if err := sendKeys([]inputKey{{Type: inputKindKey, Vk: key.Code, Flags: flags}}); err != nil {
		return err
	}
	if inputState.keys == nil {
		inputState.keys = map[uint16]struct{}{}
	}
	if key.Action == KeyDown {
		inputState.keys[key.Code] = struct{}{}
	} else {
		delete(inputState.keys, key.Code)
	}
	return nil
}

func ReleaseInputs() {
	inputState.Lock()
	defer inputState.Unlock()
	_ = applyMouse(Pointer{Action: PointerUp})
	if len(inputState.keys) > 0 {
		keys := make([]inputKey, 0, len(inputState.keys))
		for code := range inputState.keys {
			flags := uint32(keyUp)
			if extendedKey(code) {
				flags |= keyExtended
			}
			keys = append(keys, inputKey{Type: inputKindKey, Vk: code, Flags: flags})
		}
		_ = sendKeys(keys)
		inputState.keys = nil
	}
	clearTouches()
}

func applyMouse(pointer Pointer) error {
	x, y := int32(pointer.X), int32(pointer.Y)
	inputs := []inputMouse{{Type: inputKindMouse, Dx: x, Dy: y, Flags: mouseMove | mouseAbsolute | mouseVirtual}}
	wantLeft := pointer.Action != PointerUp && pointer.Buttons&ButtonLeft != 0
	wantRight := pointer.Action != PointerUp && pointer.Buttons&ButtonRight != 0
	wantMiddle := pointer.Action != PointerUp && pointer.Buttons&ButtonMiddle != 0
	inputs = appendButton(inputs, &inputState.left, wantLeft, mouseLeftDown, mouseLeftUp, x, y)
	inputs = appendButton(inputs, &inputState.right, wantRight, mouseRightDown, mouseRightUp, x, y)
	inputs = appendButton(inputs, &inputState.middle, wantMiddle, mouseMiddleDown, mouseMiddleUp, x, y)
	return sendMice(inputs)
}

func appendButton(inputs []inputMouse, held *bool, want bool, down, up uint32, x, y int32) []inputMouse {
	if want == *held {
		return inputs
	}
	flag := down
	if !want {
		flag = up
	}
	*held = want
	return append(inputs, inputMouse{Type: inputKindMouse, Dx: x, Dy: y, Flags: flag | mouseAbsolute | mouseVirtual})
}

func sendMice(inputs []inputMouse) error {
	if len(inputs) == 0 {
		return nil
	}
	sent, _, err := procSendInput.Call(uintptr(len(inputs)), uintptr(unsafe.Pointer(&inputs[0])), unsafe.Sizeof(inputs[0]))
	if sent != uintptr(len(inputs)) {
		if err == nil || err == windows.ERROR_SUCCESS {
			return errors.New("mouse input was rejected")
		}
		return err
	}
	return nil
}

func sendKeys(inputs []inputKey) error {
	if len(inputs) == 0 {
		return nil
	}
	sent, _, err := procSendInput.Call(uintptr(len(inputs)), uintptr(unsafe.Pointer(&inputs[0])), unsafe.Sizeof(inputs[0]))
	if sent != uintptr(len(inputs)) {
		if err == nil || err == windows.ERROR_SUCCESS {
			return errors.New("keyboard input was rejected")
		}
		return err
	}
	return nil
}

func extendedKey(code uint16) bool {
	switch code {
	case 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C, 0x6F, 0xA3, 0xA5:
		return true
	default:
		return false
	}
}

func applyTouch(pointer Pointer, frame Frame) error {
	if frame.VirtualW <= 0 || frame.VirtualH <= 0 {
		return errors.New("desktop size is unavailable")
	}
	if !inputState.touchReady {
		if ok, _, _ := procInitializeTouch.Call(10, touchFeedbackNone); ok == 0 {
			return errors.New("touch injection is unavailable")
		}
		inputState.touchReady = true
		inputState.touches = map[byte]pointerTouch{}
	}
	x := int32(frame.OriginX + int(uint32(pointer.X)*uint32(frame.VirtualW)/65535))
	y := int32(frame.OriginY + int(uint32(pointer.Y)*uint32(frame.VirtualH)/65535))
	flags := uint32(pointerFlagInRange | pointerFlagPrimary)
	if pointer.Action == PointerUp {
		flags |= pointerFlagUp
	} else if _, active := inputState.touches[pointer.ID]; active {
		flags |= pointerFlagUpdate | pointerFlagContact
	} else {
		flags |= pointerFlagDown | pointerFlagContact
	}
	contact := pointerTouch{
		Type: ptTouch, ID: uint32(pointer.ID) + 1, Flags: flags,
		PixelX: x, PixelY: y, RawX: x, RawY: y,
		TouchMask: touchMaskContact | touchMaskPressure, Pressure: 512,
		ContactL: x - 2, ContactT: y - 2, ContactR: x + 2, ContactB: y + 2,
		RawL: x - 2, RawT: y - 2, RawR: x + 2, RawB: y + 2,
	}
	if pointer.Action == PointerUp {
		delete(inputState.touches, pointer.ID)
	} else {
		inputState.touches[pointer.ID] = contact
	}
	batch := make([]pointerTouch, 0, len(inputState.touches)+1)
	if pointer.Action == PointerUp {
		batch = append(batch, contact)
	}
	for _, item := range inputState.touches {
		batch = append(batch, item)
	}
	if len(batch) == 0 {
		return nil
	}
	if ok, _, _ := procInjectTouch.Call(uintptr(len(batch)), uintptr(unsafe.Pointer(&batch[0]))); ok == 0 {
		return errors.New("touch injection failed")
	}
	return nil
}

func clearTouches() {
	if len(inputState.touches) == 0 {
		return
	}
	batch := make([]pointerTouch, 0, len(inputState.touches))
	for _, item := range inputState.touches {
		item.Flags = pointerFlagInRange | pointerFlagUp
		batch = append(batch, item)
	}
	_, _, _ = procInjectTouch.Call(uintptr(len(batch)), uintptr(unsafe.Pointer(&batch[0])))
	inputState.touches = nil
}
