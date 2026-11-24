package protocol

import "time"

const (
	NATProbeTimeout         = 10 * time.Second
	NATDiagnosticWindow     = 14 * time.Second
	NATUnknown              = "unknown"
	NATNoResponse           = "no_response"
	NATUnsupported          = "unsupported"
	NATEndpointIndependent  = "endpoint_independent"
	NATAddressDependent     = "address_dependent"
	NATAddressPortDependent = "address_port_dependent"
)

// NATReport contains classifications, not addresses or credentials.
type NATReport struct {
	RFC3489       string `json:"rfc3489"`
	Mapping       string `json:"mapping"`
	Filtering     string `json:"filtering"`
	MappingSample string `json:"mappingSample,omitempty"`
}

func (r NATReport) Valid() bool {
	switch r.RFC3489 {
	case NATUnknown, NATNoResponse, NATUnsupported, "open_internet", "full_cone", "restricted_cone", "port_restricted_cone", "symmetric", "udp_firewall":
	default:
		return false
	}
	switch r.MappingSample {
	case "", P2PMappingStable, P2PMappingLinear, P2PMappingVaries:
	default:
		return false
	}
	valid := func(s string) bool {
		switch s {
		case NATUnknown, NATNoResponse, NATUnsupported, NATEndpointIndependent, NATAddressDependent, NATAddressPortDependent:
			return true
		default:
			return false
		}
	}
	return valid(r.Mapping) && valid(r.Filtering)
}
