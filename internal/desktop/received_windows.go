package desktop

import (
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const ReceivesFiles = true

func Downloads() (string, error) {
	return windows.KnownFolderPath(windows.FOLDERID_Downloads, 0)
}

func MoveReceivedFile(source, target string) error {
	from, err := windows.UTF16PtrFromString(source)
	if err != nil {
		return err
	}
	to, err := windows.UTF16PtrFromString(target)
	if err != nil {
		return err
	}
	return windows.MoveFile(from, to)
}

var receiveDialogMu sync.Mutex

func ShowReceivedFile(sender, name, path string, size int64) {
	go func() {
		receiveDialogMu.Lock()
		defer receiveDialogMu.Unlock()
		title := windows.StringToUTF16Ptr("CodexRM · 文件接收")
		heading := windows.StringToUTF16Ptr("收到「" + sender + "」发来的文件")
		sizeLabel := fmt.Sprintf("%d B", size)
		if size >= 1<<20 {
			sizeLabel = fmt.Sprintf("%.1f MB", float64(size)/(1<<20))
		} else if size >= 1<<10 {
			sizeLabel = fmt.Sprintf("%.1f KB", float64(size)/(1<<10))
		}
		content := windows.StringToUTF16Ptr(name + "\n" + sizeLabel + "\n已保存到下载文件夹")
		buttons := []taskDialogButton{
			{ID: 100, Text: windows.StringToUTF16Ptr("打开文件夹")},
			{ID: 101, Text: windows.StringToUTF16Ptr("打开")},
			{ID: 102, Text: windows.StringToUTF16Ptr("关闭")},
		}
		selected, displayed := receivedTaskDialog(title, heading, content, buttons)
		if !displayed {
			message := windows.StringToUTF16Ptr("收到「" + sender + "」发来的文件\n\n" + name + " · " + sizeLabel +
				"\n\n是：打开文件夹\n否：打开文件\n取消：关闭")
			response, _ := windows.MessageBox(0, message, title, windows.MB_YESNOCANCEL|windows.MB_DEFBUTTON3|windows.MB_ICONINFORMATION|windows.MB_SETFOREGROUND)
			switch response {
			case 6:
				selected = 100
			case 7:
				selected = 101
			}
		}
		target := path
		if selected == 100 {
			target = filepath.Dir(path)
		} else if selected != 101 {
			return
		}
		if err := windows.ShellExecute(0, windows.StringToUTF16Ptr("open"), windows.StringToUTF16Ptr(target), nil, nil, windows.SW_SHOWNORMAL); err != nil {
			_, _ = windows.MessageBox(0, windows.StringToUTF16Ptr("无法打开 "+filepath.Base(target)), title,
				windows.MB_OK|windows.MB_ICONERROR|windows.MB_SETFOREGROUND)
		}
	}()
}

const controlsManifest = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity version="1.0.0.0" processorArchitecture="*" name="CodexRM.ReceiveDialog" type="win32"/>
  <dependency><dependentAssembly>
    <assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"/>
  </dependentAssembly></dependency>
</assembly>`

type activationContext struct {
	Size, Flags                        uint32
	Source                             *uint16
	Architecture, Language             uint16
	AssemblyDir, Resource, Application *uint16
	Module                             windows.Handle
}

func receivedTaskDialog(title, heading, content *uint16, buttons []taskDialogButton) (int32, bool) {
	manifest, err := os.CreateTemp("", "codexrm-controls-*.manifest")
	if err != nil {
		return 0, false
	}
	defer os.Remove(manifest.Name())
	_, writeErr := manifest.WriteString(controlsManifest)
	closeErr := manifest.Close()
	if writeErr != nil || closeErr != nil {
		return 0, false
	}

	// The activation context is thread-local and selects Common Controls v6.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	kernel := windows.NewLazySystemDLL("kernel32.dll")
	act := activationContext{Source: windows.StringToUTF16Ptr(manifest.Name())}
	act.Size = uint32(unsafe.Sizeof(act))
	handle, _, _ := kernel.NewProc("CreateActCtxW").Call(uintptr(unsafe.Pointer(&act)))
	if handle == ^uintptr(0) {
		return 0, false
	}
	defer kernel.NewProc("ReleaseActCtx").Call(handle)
	var cookie uintptr
	ok, _, _ := kernel.NewProc("ActivateActCtx").Call(handle, uintptr(unsafe.Pointer(&cookie)))
	if ok == 0 {
		return 0, false
	}
	defer kernel.NewProc("DeactivateActCtx").Call(0, cookie)
	module, err := windows.LoadLibraryEx("comctl32.dll", 0, windows.LOAD_LIBRARY_SEARCH_SYSTEM32)
	if err != nil {
		return 0, false
	}
	defer windows.FreeLibrary(module)
	taskDialog, err := windows.GetProcAddress(module, "TaskDialogIndirect")
	if err != nil {
		return 0, false
	}

	// CommCtrl.h declares TASKDIALOGCONFIG and TASKDIALOG_BUTTON with pack(1).
	appendPtr := func(buffer []byte, pointer uintptr) []byte {
		if unsafe.Sizeof(pointer) == 8 {
			return binary.LittleEndian.AppendUint64(buffer, uint64(pointer))
		}
		return binary.LittleEndian.AppendUint32(buffer, uint32(pointer))
	}
	var packedButtons []byte
	for _, button := range buttons {
		packedButtons = binary.LittleEndian.AppendUint32(packedButtons, uint32(button.ID))
		packedButtons = appendPtr(packedButtons, uintptr(unsafe.Pointer(button.Text)))
	}
	config := make([]byte, 4, 160)
	config = appendPtr(config, 0)                        // Owner window
	config = appendPtr(config, 0)                        // Resource instance
	config = binary.LittleEndian.AppendUint32(config, 8) // Allow close via Escape or title bar.
	config = binary.LittleEndian.AppendUint32(config, 0) // No standard buttons
	config = appendPtr(config, uintptr(unsafe.Pointer(title)))
	config = appendPtr(config, 0xfffd) // TD_INFORMATION_ICON
	config = appendPtr(config, uintptr(unsafe.Pointer(heading)))
	config = appendPtr(config, uintptr(unsafe.Pointer(content)))
	config = binary.LittleEndian.AppendUint32(config, uint32(len(buttons)))
	config = appendPtr(config, uintptr(unsafe.Pointer(&packedButtons[0])))
	config = binary.LittleEndian.AppendUint32(config, 102)
	config = binary.LittleEndian.AppendUint32(config, 0) // Radio button count
	config = appendPtr(config, 0)                        // Radio button array
	config = binary.LittleEndian.AppendUint32(config, 0) // Default radio button
	for range 8 {                                        // Verification, expanded text, footer and callback fields.
		config = appendPtr(config, 0)
	}
	config = binary.LittleEndian.AppendUint32(config, 0) // Automatic width
	binary.LittleEndian.PutUint32(config, uint32(len(config)))
	var selected int32
	code, _, _ := syscall.SyscallN(taskDialog, uintptr(unsafe.Pointer(&config[0])), uintptr(unsafe.Pointer(&selected)), 0, 0)
	runtime.KeepAlive(packedButtons)
	runtime.KeepAlive(buttons)
	runtime.KeepAlive(title)
	runtime.KeepAlive(heading)
	runtime.KeepAlive(content)
	return selected, int32(code) >= 0
}
