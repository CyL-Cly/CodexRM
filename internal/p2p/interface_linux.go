//go:build linux && !android

package p2p

import (
	"net"
	"os"
	"strings"

	"golang.org/x/sys/unix"
)

func bindInterface(fd uintptr, iface net.Interface, _ bool) error {
	return unix.BindToDevice(int(fd), iface.Name)
}

func adapterType(iface net.Interface) AdapterType {
	if _, err := os.Stat("/sys/class/net/" + iface.Name + "/wireless"); err == nil {
		return AdapterWiFi
	}
	if data, err := os.ReadFile("/sys/class/net/" + iface.Name + "/uevent"); err == nil {
		body := strings.ToLower(string(data))
		if strings.Contains(body, "devtype=wlan") {
			return AdapterWiFi
		}
		if strings.Contains(body, "devtype=wwan") {
			return AdapterCellular
		}
	}
	return classifyAdapterName(iface.Name)
}
