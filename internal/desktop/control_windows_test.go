//go:build windows

package desktop

import (
	"testing"
	"unsafe"
)

func TestWindowsDesktopLayouts(t *testing.T) {
	if unsafe.Sizeof(inputMouse{}) != 40 || unsafe.Offsetof(inputMouse{}.Extra) != 32 {
		t.Fatalf("mouse layout size=%d extra=%d", unsafe.Sizeof(inputMouse{}), unsafe.Offsetof(inputMouse{}.Extra))
	}
	if unsafe.Sizeof(inputKey{}) != 40 || unsafe.Offsetof(inputKey{}.Extra) != 24 {
		t.Fatalf("key layout size=%d extra=%d", unsafe.Sizeof(inputKey{}), unsafe.Offsetof(inputKey{}.Extra))
	}
	if unsafe.Sizeof(cursorInfo{}) != 24 {
		t.Fatalf("cursor layout size=%d", unsafe.Sizeof(cursorInfo{}))
	}
	touch := pointerTouch{}
	if unsafe.Sizeof(touch) != 144 || unsafe.Offsetof(touch.PixelX) != 32 || unsafe.Offsetof(touch.TouchFlags) != 96 || unsafe.Offsetof(touch.Pressure) != 140 {
		t.Fatalf("touch layout size=%d pixel=%d flags=%d pressure=%d", unsafe.Sizeof(touch), unsafe.Offsetof(touch.PixelX), unsafe.Offsetof(touch.TouchFlags), unsafe.Offsetof(touch.Pressure))
	}
}

func TestCaptureJPEGProducesImage(t *testing.T) {
	frame, err := CaptureJPEG(960, 40)
	if err != nil {
		t.Fatal(err)
	}
	if len(frame.JPEG) < 100 || frame.JPEG[0] != 0xff || frame.JPEG[1] != 0xd8 || frame.Width < 1 || frame.Height < 1 || frame.VirtualW < 1 || frame.VirtualH < 1 {
		t.Fatalf("frame %dx%d virtual %dx%d bytes %d", frame.Width, frame.Height, frame.VirtualW, frame.VirtualH, len(frame.JPEG))
	}
}
