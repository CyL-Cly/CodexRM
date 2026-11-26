//go:build android && cgo

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"unsafe"

	"github.com/CyL-Cly/CodexRM-Server/p2p"
)

//export CRMDiagnoseNAT
func CRMDiagnoseNAT(ptr unsafe.Pointer, n C.int, out *C.int) unsafe.Pointer {
	if n <= 0 || n > 1024 {
		return nil
	}
	var endpoints []string
	if json.Unmarshal(C.GoBytes(ptr, n), &endpoints) != nil || len(endpoints) == 0 || len(endpoints) > 4 {
		return nil
	}
	report := p2p.DiagnoseNAT(context.Background(), endpoints)
	b, err := json.Marshal(report)
	if err != nil {
		return nil
	}
	*out = C.int(len(b))
	return C.CBytes(b)
}
