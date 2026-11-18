//go:build android

package main

/*
#include <stdlib.h>
*/
import "C"

import "github.com/codexrm/p2p-probe/core"

//export RunProbe
func RunProbe(config *C.char) *C.char { return C.CString(core.RunJSON(C.GoString(config))) }

func main() {}
