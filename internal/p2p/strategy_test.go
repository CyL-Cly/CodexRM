package p2p

import (
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestPunchModeByNATPair(t *testing.T) {
	cases := []struct {
		local, remote        NATKind
		mode                 PunchMode
		predict, spray, grow bool
	}{
		{NATCone, NATCone, PunchExact, false, false, false},
		{NATCone, NATLinearSymmetric, ConePunchToLinearSymmetric, true, false, false},
		{NATLinearSymmetric, NATCone, LinearSymmetricPunchToCone, false, false, false},
		{NATCone, NATRandomSymmetric, ConePunchToRandomSymmetric, false, true, false},
		{NATRandomSymmetric, NATCone, RandomSymmetricPunchToCone, false, true, true},
		{NATLinearSymmetric, NATLinearSymmetric, BothLinearSymmetricPunch, true, false, false},
		{NATLinearSymmetric, NATRandomSymmetric, LinearSymmetricPunchToRandomSymmetric, false, true, false},
		{NATRandomSymmetric, NATLinearSymmetric, RandomSymmetricPunchToLinearSymmetric, true, false, true},
		{NATRandomSymmetric, NATRandomSymmetric, BothRandomSymmetricPunch, false, true, true},
	}
	for _, c := range cases {
		st := punchStrategyFor(mappingFor(c.local), mappingFor(c.remote), true, true)
		if st.Mode != c.mode || st.Predict != c.predict || st.Spray != c.spray || st.GrowLocal != c.grow {
			t.Fatalf("%s vs %s: got %+v, want mode=%s predict=%t spray=%t grow=%t",
				c.local, c.remote, st, c.mode, c.predict, c.spray, c.grow)
		}
	}
}

func mappingFor(k NATKind) string {
	switch k {
	case NATLinearSymmetric:
		return protocol.P2PMappingLinear
	case NATRandomSymmetric:
		return protocol.P2PMappingVaries
	case NATCone:
		return protocol.P2PMappingStable
	default:
		return ""
	}
}

func TestPunchStrategyDisableFullPunchIsExactOnly(t *testing.T) {
	st := punchStrategyFor(protocol.P2PMappingVaries, protocol.P2PMappingLinear, false, true)
	if st != (punchStrategy{Mode: PunchExact}) {
		t.Fatalf("got %+v", st)
	}
}

func TestPunchStrategyGatesRandomSpray(t *testing.T) {
	st := punchStrategyFor(protocol.P2PMappingStable, protocol.P2PMappingVaries, true, false)
	if st.Spray || st.Mode != ConePunchToRandomSymmetric {
		t.Fatalf("spray should require allowSpray, got %+v", st)
	}
}

func TestNatKindTreatsUnknownAsCone(t *testing.T) {
	if natKind("") != NATCone || natKind(protocol.P2PMappingStable) != NATCone {
		t.Fatal("empty or stable should be cone")
	}
}
