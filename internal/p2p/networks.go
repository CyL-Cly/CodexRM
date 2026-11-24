package p2p

import (
	"slices"
	"strings"
	"sync"
)

// AdapterType is a coarse NIC class used only to rank local egress.
// The order is a documented heuristic: ethernet, then wifi, then other,
// then cellular. It is not a clone of any proprietary rank tuple.
type AdapterType string

const (
	AdapterEthernet AdapterType = "ethernet"
	AdapterWiFi     AdapterType = "wifi"
	AdapterCellular AdapterType = "cellular"
	AdapterOther    AdapterType = "other"
)

const (
	channelDefault  = "default_route"
	channelPhysical = "physical_route"
)

func (t AdapterType) rank() int {
	switch t {
	case AdapterEthernet:
		return 4
	case AdapterWiFi:
		return 3
	case AdapterCellular:
		return 1
	default:
		return 2
	}
}

func classifyAdapterName(name string) AdapterType {
	n := strings.ToLower(strings.TrimSpace(name))
	n = strings.ReplaceAll(n, " ", "")
	n = strings.ReplaceAll(n, "_", "")
	n = strings.ReplaceAll(n, "-", "")
	switch {
	case n == "":
		return ""
	case containsAny(n, "wlan", "wifi", "awdl", "无线"):
		return AdapterWiFi
	case strings.HasPrefix(n, "wlp") || strings.HasPrefix(n, "wlx") || strings.HasPrefix(n, "wl"):
		return AdapterWiFi
	case containsAny(n, "wwan", "rmnet", "ccmni", "pdpip", "pdp", "mbim", "qmimux", "cellular", "蜂窝", "移动宽带"):
		return AdapterCellular
	case containsAny(n, "ethernet", "以太网") || strings.HasPrefix(n, "eth") ||
		strings.HasPrefix(n, "enp") || strings.HasPrefix(n, "ens") ||
		strings.HasPrefix(n, "eno") || strings.HasPrefix(n, "em") ||
		(strings.HasPrefix(n, "lan") && !strings.Contains(n, "wlan")):
		return AdapterEthernet
	default:
		return AdapterOther
	}
}

func containsAny(s string, parts ...string) bool {
	for _, p := range parts {
		if strings.Contains(s, p) {
			return true
		}
	}
	return false
}

func normalizeSource(src Source) Source {
	if src.Adapter == "" {
		src.Adapter = classifyAdapterName(src.Network)
	}
	return src
}

// Drop cellular IPv4 sources when more than one cellular network is present.
// Binding the wrong cell face is worse than waiting for another path or relay.
func filterAmbiguousCellular(sources []Source) []Source {
	cell := make(map[string]bool)
	for _, src := range sources {
		if src.Adapter == AdapterCellular && src.Prefix.Addr().Is4() && src.Network != "" {
			cell[src.Network] = true
		}
	}
	if len(cell) <= 1 {
		return sources
	}
	var out []Source
	for _, src := range sources {
		if src.Adapter != AdapterCellular {
			out = append(out, src)
		}
	}
	return out
}

func sortSources(sources []Source, preferred string) []Source {
	out := append([]Source(nil), sources...)
	slices.SortStableFunc(out, func(a, b Source) int {
		if preferred != "" {
			ap, bp := a.Network == preferred, b.Network == preferred
			if ap != bp {
				if ap {
					return -1
				}
				return 1
			}
		}
		ra, rb := a.Adapter.rank(), b.Adapter.rank()
		if ra != rb {
			return rb - ra
		}
		return 0
	})
	return out
}

func prepareSources(sources []Source) []Source {
	for i := range sources {
		sources[i] = normalizeSource(sources[i])
	}
	return sortSources(filterAmbiguousCellular(sources), preferredEgress())
}

var (
	egressMu       sync.Mutex
	lastGoodEgress string
)

// RememberEgress stores the network that last completed a direct path so the
// next Prepare can try that physical egress first.
func RememberEgress(network string) {
	egressMu.Lock()
	defer egressMu.Unlock()
	lastGoodEgress = network
}

func preferredEgress() string {
	egressMu.Lock()
	defer egressMu.Unlock()
	return lastGoodEgress
}

// PathInfo describes the local egress that won a direct connection.
type PathInfo struct {
	Network  string
	Adapter  AdapterType
	Physical bool
	Channel  string
	ExitIP   string
}

func pathInfo(p directPath) PathInfo {
	info := PathInfo{
		Network:  p.source.Network,
		Adapter:  p.source.Adapter,
		Physical: p.source.Network != "",
		Channel:  channelDefault,
	}
	if info.Physical {
		info.Channel = channelPhysical
	}
	if p.exitIP.IsValid() {
		info.ExitIP = p.exitIP.String()
	}
	return info
}
