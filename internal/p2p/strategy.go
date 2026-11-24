package p2p

import "github.com/codexrm/codexrm/internal/protocol"

// NATKind is the hole-punching view of a mapping hint. "Cone" covers
// endpoint-independent mappings; the symmetric cases follow whether the
// next port can be predicted from a small observed step.
type NATKind string

const (
	NATCone            NATKind = "cone"
	NATLinearSymmetric NATKind = "linear_symmetric"
	NATRandomSymmetric NATKind = "random_symmetric"
)

func natKind(mapping string) NATKind {
	switch mapping {
	case protocol.P2PMappingStable:
		return NATCone
	case protocol.P2PMappingLinear:
		return NATLinearSymmetric
	case protocol.P2PMappingVaries:
		return NATRandomSymmetric
	default:
		// No hint: treat as cone so we still probe published ports without
		// inventing a port pattern.
		return NATCone
	}
}

// PunchMode names the NAT-pair strategy. These are local identifiers, not
// a copy of any proprietary protocol string.
type PunchMode string

const (
	PunchExact                            PunchMode = "exact"
	ConePunchToLinearSymmetric            PunchMode = "cone_to_linear_symmetric"
	LinearSymmetricPunchToCone            PunchMode = "linear_symmetric_to_cone"
	ConePunchToRandomSymmetric            PunchMode = "cone_to_random_symmetric"
	RandomSymmetricPunchToCone            PunchMode = "random_symmetric_to_cone"
	BothLinearSymmetricPunch              PunchMode = "both_linear_symmetric"
	LinearSymmetricPunchToRandomSymmetric PunchMode = "linear_symmetric_to_random_symmetric"
	RandomSymmetricPunchToLinearSymmetric PunchMode = "random_symmetric_to_linear_symmetric"
	BothRandomSymmetricPunch              PunchMode = "both_random_symmetric"
)

func punchMode(local, remote NATKind) PunchMode {
	switch local {
	case NATLinearSymmetric:
		switch remote {
		case NATLinearSymmetric:
			return BothLinearSymmetricPunch
		case NATRandomSymmetric:
			return LinearSymmetricPunchToRandomSymmetric
		default:
			return LinearSymmetricPunchToCone
		}
	case NATRandomSymmetric:
		switch remote {
		case NATLinearSymmetric:
			return RandomSymmetricPunchToLinearSymmetric
		case NATRandomSymmetric:
			return BothRandomSymmetricPunch
		default:
			return RandomSymmetricPunchToCone
		}
	default:
		switch remote {
		case NATLinearSymmetric:
			return ConePunchToLinearSymmetric
		case NATRandomSymmetric:
			return ConePunchToRandomSymmetric
		default:
			return PunchExact
		}
	}
}

type punchStrategy struct {
	Mode      PunchMode
	Predict   bool // probe ports at the observed remote step
	Spray     bool // probe a neighborhood and random ports on the remote IP
	GrowLocal bool // open extra local source ports on this egress
}

func punchStrategyFor(localMapping, remoteMapping string, full, allowSpray bool) punchStrategy {
	if !full {
		return punchStrategy{Mode: PunchExact}
	}
	mode := punchMode(natKind(localMapping), natKind(remoteMapping))
	st := punchStrategy{Mode: mode}
	switch mode {
	case ConePunchToLinearSymmetric, BothLinearSymmetricPunch, RandomSymmetricPunchToLinearSymmetric:
		st.Predict = true
	}
	switch mode {
	case ConePunchToRandomSymmetric, LinearSymmetricPunchToRandomSymmetric,
		RandomSymmetricPunchToCone, BothRandomSymmetricPunch:
		st.Spray = allowSpray
	}
	switch mode {
	case RandomSymmetricPunchToCone, RandomSymmetricPunchToLinearSymmetric, BothRandomSymmetricPunch:
		st.GrowLocal = true
	}
	return st
}
