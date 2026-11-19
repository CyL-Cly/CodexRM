package protocol

// RobotMethodAllowed reports whether a paired robot may use the RPC method.
func RobotMethodAllowed(method string) bool {
	switch method {
	case "thread/start", "thread/resume", "thread/read", "thread/turns/list",
		"turn/start", "turn/interrupt", "codexrm/thread/snapshot",
		"codexrm/wx/exec", "codexrm/wx/commands/list", "codexrm/wx/policy/set", "codexrm/wx/textonly/status":
		return true
	default:
		return false
	}
}
