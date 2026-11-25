package p2p

import "testing"

func TestDirectReady(t *testing.T) {
	if DirectReady(ConnControl) || DirectReady(ConnTransport) || !DirectReady(ConnConnected) {
		t.Fatalf("only ConnConnected is ready")
	}
}

func TestSignalPhaseFromFlags(t *testing.T) {
	cases := []struct {
		ao, co, ar, cr, req, active bool
		want                        SignalPhase
	}{
		{false, false, false, false, false, false, SignalStarting},
		{true, false, false, false, false, false, SignalOffering},
		{false, true, false, false, false, false, SignalOffering},
		{true, true, false, false, false, false, SignalExchanging},
		{true, true, true, true, false, false, SignalReady},
		{true, true, true, true, true, false, SignalActivating},
		{true, true, true, true, true, true, SignalActive},
	}
	for _, c := range cases {
		if got := SignalPhaseFromFlags(c.ao, c.co, c.ar, c.cr, c.req, c.active); got != c.want {
			t.Fatalf("got %s, want %s for %+v", got, c.want, c)
		}
	}
}

func TestSessionStateTransitionsOnPrepareAndConnectErrors(t *testing.T) {
	s := &Session{}
	if s.State() != ConnIdle {
		t.Fatalf("fresh session state %s", s.State())
	}
	s.setState(ConnPreparing)
	s.setState(ConnFailed)
	s.Close()
	if s.State() != ConnFailed {
		t.Fatalf("Close must not overwrite Failed, got %s", s.State())
	}
	s2 := &Session{}
	s2.setState(ConnConnected)
	s2.Close()
	if s2.State() != ConnClosed {
		t.Fatalf("got %s, want closed", s2.State())
	}
}

func TestSessionMarkRelaySurvivesClose(t *testing.T) {
	s := &Session{}
	s.setState(ConnConnected)
	s.MarkRelay()
	if s.State() != ConnRelay {
		t.Fatalf("got %s, want relay", s.State())
	}
	s.Close()
	if s.State() != ConnRelay {
		t.Fatalf("Close must not overwrite Relay, got %s", s.State())
	}
}
