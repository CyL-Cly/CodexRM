package core

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net"
	"time"
)

type Mapping struct {
	Server     string `json:"server"`
	Mapped     string `json:"mapped,omitempty"`
	Error      string `json:"error,omitempty"`
	ChangePort bool   `json:"changePort,omitempty"`
	Responder  string `json:"responder,omitempty"`
}

func stun(c *net.UDPConn, server string, change bool) Mapping {
	out := Mapping{Server: server, ChangePort: change}
	dst, err := net.ResolveUDPAddr("udp4", server)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	b := make([]byte, 20)
	binary.BigEndian.PutUint16(b, 1)
	copy(b[4:], []byte{0x21, 0x12, 0xa4, 0x42})
	rand.Read(b[8:])
	if change {
		binary.BigEndian.PutUint16(b[2:], 8)
		b = append(b, 0, 3, 0, 4, 0, 0, 0, 2)
	}
	defer c.SetReadDeadline(time.Time{})
	buf := make([]byte, 2048)
	for attempt := 0; attempt < 2; attempt++ {
		c.WriteToUDP(b, dst)
		c.SetReadDeadline(time.Now().Add(700 * time.Millisecond))
		for {
			n, from, e := c.ReadFromUDP(buf)
			if e != nil {
				err = e
				break
			}
			if n < 20 || binary.BigEndian.Uint16(buf) != 0x0101 || string(buf[4:8]) != string(b[4:8]) || string(buf[8:20]) != string(b[8:20]) {
				continue
			}
			if !from.IP.Equal(dst.IP) {
				continue
			}
			if !change && from.Port != dst.Port {
				continue
			}
			out.Responder = from.String()
			if change && from.Port == dst.Port {
				out.Error = "server did not honor CHANGE-PORT"
				return out
			}
			for p := 20; p+4 <= n; {
				typ := binary.BigEndian.Uint16(buf[p:])
				l := int(binary.BigEndian.Uint16(buf[p+2:]))
				p += 4
				if p+l > n {
					break
				}
				if (typ == 0x20 || typ == 1) && l >= 8 && buf[p+1] == 1 {
					port := binary.BigEndian.Uint16(buf[p+2:])
					ip := append(net.IP(nil), buf[p+4:p+8]...)
					if typ == 0x20 {
						port ^= 0x2112
						for i := 0; i < 4; i++ {
							ip[i] ^= b[4+i]
						}
					}
					out.Mapped = net.JoinHostPort(ip.String(), fmt.Sprint(port))
					return out
				}
				p += (l + 3) &^ 3
			}
		}
	}
	out.Error = fmt.Sprint(err)
	return out
}

func Diagnose(servers []string) []Mapping {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		return []Mapping{{Error: err.Error()}}
	}
	defer c.Close()
	observations := diagnoseSocket(c, servers)
	// Diagnostic-only control: after normal packets have been sent to the
	// alternate endpoint, check whether its previously filtered reply arrives.
	// This does not change the frozen per-round validation procedure.
	if len(servers) > 1 {
		observations = append(observations, stun(c, servers[0], true))
	}
	return observations
}

func diagnoseSocket(c *net.UDPConn, servers []string) []Mapping {
	var out []Mapping
	if len(servers) == 0 {
		return out
	}
	// Alternate-port reply is tested BEFORE ever sending to that alternate port.
	if len(servers) > 0 {
		out = append(out, stun(c, servers[0], false), stun(c, servers[0], true))
	}
	for _, s := range servers[1:] {
		out = append(out, stun(c, s, false))
	}
	if len(servers) > 0 {
		out = append(out, stun(c, servers[0], false))
	}
	return out
}
