package p2p

import (
	"net/netip"
	"slices"
	"testing"
)

func TestClassifyAdapterName(t *testing.T) {
	cases := map[string]AdapterType{
		"eth0":        AdapterEthernet,
		"enp3s0":      AdapterEthernet,
		"Ethernet 2":  AdapterEthernet,
		"以太网":         AdapterEthernet,
		"wlan0":       AdapterWiFi,
		"wlp2s0":      AdapterWiFi,
		"Wi-Fi":       AdapterWiFi,
		"wwan0":       AdapterCellular,
		"rmnet_data0": AdapterCellular,
		"ccmni0":      AdapterCellular,
		"en0":         AdapterOther,
		"br0":         AdapterOther,
		"":            "",
	}
	for name, want := range cases {
		if got := classifyAdapterName(name); got != want {
			t.Fatalf("%q: got %q, want %q", name, got, want)
		}
	}
}

func TestFilterAmbiguousCellularIPv4(t *testing.T) {
	wifi := Source{Network: "wlan0", Adapter: AdapterWiFi, Prefix: netip.MustParsePrefix("192.168.1.2/24")}
	cellA := Source{Network: "rmnet0", Adapter: AdapterCellular, Prefix: netip.MustParsePrefix("10.1.0.2/32")}
	cellB := Source{Network: "rmnet1", Adapter: AdapterCellular, Prefix: netip.MustParsePrefix("10.2.0.2/32")}
	got := filterAmbiguousCellular([]Source{wifi, cellA, cellB})
	if len(got) != 1 || got[0].Network != "wlan0" {
		t.Fatalf("expected only wifi to remain, got %+v", got)
	}
	kept := filterAmbiguousCellular([]Source{wifi, cellA})
	if len(kept) != 2 {
		t.Fatalf("a single cellular network should be kept, got %+v", kept)
	}
}

func TestSortSourcesRanksEthernetAheadOfWifi(t *testing.T) {
	wifi := Source{Network: "wlan0", Adapter: AdapterWiFi, Prefix: netip.MustParsePrefix("192.168.1.2/24")}
	eth := Source{Network: "eth0", Adapter: AdapterEthernet, Prefix: netip.MustParsePrefix("192.168.0.2/24")}
	cell := Source{Network: "wwan0", Adapter: AdapterCellular, Prefix: netip.MustParsePrefix("10.1.0.2/32")}
	got := sortSources([]Source{cell, wifi, eth}, "")
	var names []string
	for _, s := range got {
		names = append(names, s.Network)
	}
	if !slices.Equal(names, []string{"eth0", "wlan0", "wwan0"}) {
		t.Fatalf("got %v", names)
	}
	preferred := sortSources([]Source{cell, wifi, eth}, "wwan0")
	if preferred[0].Network != "wwan0" {
		t.Fatalf("preferred egress should be first, got %s", preferred[0].Network)
	}
}

func TestRankedPathsPrefersPhysicalEthernetThenRTT(t *testing.T) {
	t.Cleanup(func() { RememberEgress("") })
	RememberEgress("")
	s := &Session{
		pathSockets: 3,
		paths: []directPath{
			{},
			{source: Source{Network: "wlan0", Adapter: AdapterWiFi}, rtt: 10},
			{source: Source{Network: "eth0", Adapter: AdapterEthernet}, rtt: 40},
		},
	}
	got := s.rankedPaths()
	if !slices.Equal(got, []int{2, 1, 0}) {
		t.Fatalf("got %v, want ethernet, wifi, default", got)
	}
	RememberEgress("wlan0")
	got = s.rankedPaths()
	if !slices.Equal(got, []int{1, 2, 0}) {
		t.Fatalf("preferred wifi should lead, got %v", got)
	}
}

func TestPrepareSourcesFillsAdapterAndDropsAmbiguousCell(t *testing.T) {
	t.Cleanup(func() { RememberEgress("") })
	RememberEgress("")
	got := prepareSources([]Source{
		{Network: "rmnet0", Prefix: netip.MustParsePrefix("10.1.0.2/32")},
		{Network: "eth0", Prefix: netip.MustParsePrefix("192.168.0.2/24")},
		{Network: "rmnet1", Prefix: netip.MustParsePrefix("10.2.0.2/32")},
	})
	if len(got) != 1 || got[0].Network != "eth0" || got[0].Adapter != AdapterEthernet {
		t.Fatalf("got %+v", got)
	}
}
