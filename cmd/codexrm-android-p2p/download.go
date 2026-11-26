//go:build android && cgo

package main

/*
#include <stdint.h>
*/
import "C"

import (
	"context"
	"os"
	"unsafe"

	"github.com/CyL-Cly/CodexRM-Server/protocol"
)

//export CRMDownload
func CRMDownload(h C.int64_t, rawID unsafe.Pointer, idLen C.int, path unsafe.Pointer, pathLen C.int, size C.int64_t, phone C.int) C.int {
	s := lookup(h)
	if s == nil || idLen != protocol.DirectShareIDSize || pathLen <= 0 || pathLen > 4096 || size < 0 || size > protocol.DirectFileMaxSize {
		return 0
	}
	link := s.link.Load()
	if link == nil || s.ctx.Err() != nil {
		return 0
	}
	id := string(C.GoBytes(rawID, idLen))
	ctx, cancel := context.WithCancel(s.ctx)
	defer cancel()
	if _, busy := s.downloads.LoadOrStore(id, cancel); busy {
		return 0
	}
	defer s.downloads.Delete(id)
	file, err := os.OpenFile(string(C.GoBytes(path, pathLen)), os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return 0
	}
	kind := protocol.DirectFileDownload
	if phone != 0 {
		kind = protocol.PhoneFileDownload
	}
	err = link.DownloadFile(ctx, kind, id, int64(size), file)
	if closeErr := file.Close(); err != nil || closeErr != nil || ctx.Err() != nil {
		return 0
	}
	return 1
}

//export CRMCancelDownload
func CRMCancelDownload(h C.int64_t, id unsafe.Pointer, idLen C.int) {
	if s := lookup(h); s != nil && idLen == protocol.DirectShareIDSize {
		if cancel, found := s.downloads.Load(string(C.GoBytes(id, idLen))); found {
			cancel.(context.CancelFunc)()
		}
	}
}
