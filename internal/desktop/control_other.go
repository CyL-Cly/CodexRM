//go:build !windows

package desktop

import "errors"

func Supported() bool { return false }

func CaptureJPEG(maxEdge, quality int) (Frame, error) {
	return Frame{}, errors.New("desktop capture is only available on Windows")
}

func ApplyPointer(Pointer, byte, Frame) error {
	return errors.New("desktop input is only available on Windows")
}

func ApplyKey(Key) error {
	return errors.New("desktop input is only available on Windows")
}

func ReleaseInputs() {}
