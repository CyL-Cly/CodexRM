package agent

import "testing"

func TestSelectCodexDesktopPIDsFollowsDesktopTreeOnly(t *testing.T) {
	pkg := `C:\Program Files\WindowsApps\OpenAI.Codex_26.1_x64__abc\app\`
	nodes := []processNode{
		{PID: 100, Parent: 1, Name: "ChatGPT.exe", Exe: pkg + `ChatGPT.exe`},
		{PID: 101, Parent: 100, Name: "codex.exe", Exe: pkg + `resources\codex.exe`},
		{PID: 102, Parent: 101, Name: "codex-code-mode-host.exe", Exe: pkg + `resources\codex-code-mode-host.exe`},
		{PID: 200, Parent: 1, Name: "codexrm-agent.exe", Exe: `E:\code\Go\CodexRM\build\bin\codexrm-agent.exe`},
		{PID: 201, Parent: 200, Name: "codex.exe", Exe: pkg + `resources\codex.exe`},
		{PID: 300, Parent: 1, Name: "ChatGPT.exe", Exe: `C:\Other\ChatGPT.exe`},
	}
	got := selectCodexDesktopPIDs(nodes)
	want := []uint32{102, 101, 100}
	if len(got) != len(want) {
		t.Fatalf("selected %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("selected %v, want %v", got, want)
		}
	}
}

func TestCodexDesktopProcessIgnoresBundledCLIAndForeignApps(t *testing.T) {
	pkg := `C:\Program Files\WindowsApps\OpenAI.Codex_26.1_x64__abc\app\`
	if !isCodexDesktopProcess(processNode{Name: "ChatGPT.exe", Exe: pkg + `ChatGPT.exe`}) {
		t.Fatal("desktop main process rejected")
	}
	if isCodexDesktopProcess(processNode{Name: "codex.exe", Exe: pkg + `resources\codex.exe`}) {
		t.Fatal("bundled CLI treated as desktop client")
	}
	if isCodexDesktopProcess(processNode{Name: "ChatGPT.exe", Exe: `C:\Other\ChatGPT.exe`}) {
		t.Fatal("foreign ChatGPT install treated as Codex desktop")
	}
}
