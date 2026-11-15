// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"fmt"
	"syscall"
	"unsafe"
)

var (
	kernel = syscall.NewLazyDLL("kernel32.dll")
	user32 = syscall.NewLazyDLL("user32.dll")
)

const (
	wmDestroy = 0x0002
	wmClose   = 0x0010
	wmSetFont = 0x0030
	wmFinish  = 0x8001
)

func wide(value string) *uint16 {
	ptr, err := syscall.UTF16PtrFromString(value)
	if err != nil {
		panic(err)
	}
	return ptr
}

func message(parent uintptr, value string, flags uintptr) uintptr {
	answer, _, _ := user32.NewProc("MessageBoxW").Call(parent, uintptr(unsafe.Pointer(wide(value))), uintptr(unsafe.Pointer(wide("AmorX"))), flags)
	return answer
}

type windowClass struct {
	style       uint32
	wndProc     uintptr
	extraClass  int32
	extraWindow int32
	instance    uintptr
	icon        uintptr
	cursor      uintptr
	background  uintptr
	menuName    uintptr
	className   *uint16
}

type progressWindow struct {
	handle, label uintptr
}

func newProgressWindow() (progressWindow, error) {
	instance, _, _ := kernel.NewProc("GetModuleHandleW").Call(0)
	icon, _, _ := user32.NewProc("LoadIconW").Call(instance, 1)
	if icon == 0 {
		icon, _, _ = user32.NewProc("LoadIconW").Call(0, 32512)
	}
	cursor, _, _ := user32.NewProc("LoadCursorW").Call(0, 32512)
	className, _ := syscall.UTF16PtrFromString("AmorXLauncher")
	class := windowClass{
		wndProc:  syscall.NewCallback(progressWndProc),
		instance: instance, icon: icon, cursor: cursor, background: 16,
		className: className,
	}
	if ok, _, err := user32.NewProc("RegisterClassW").Call(uintptr(unsafe.Pointer(&class))); ok == 0 && err != syscall.Errno(1410) {
		return progressWindow{}, fmt.Errorf("Окно AmorX: %w", err)
	}
	create := user32.NewProc("CreateWindowExW")
	const width, height = 480, 145
	screenX, _, _ := user32.NewProc("GetSystemMetrics").Call(0)
	screenY, _, _ := user32.NewProc("GetSystemMetrics").Call(1)
	x, y := (screenX-width)/2, (screenY-height)/2
	handle, _, err := create.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(wide("AmorX"))), 0x00C80000,
		x, y, width, height, 0, 0, instance, 0)
	if handle == 0 {
		return progressWindow{}, fmt.Errorf("Окно AmorX: %w", err)
	}
	label, _, err := create.Call(0, uintptr(unsafe.Pointer(wide("STATIC"))), uintptr(unsafe.Pointer(wide("Запуск AmorX…"))), 0x50000000,
		24, 24, 410, 60, handle, 0, instance, 0)
	if label == 0 {
		user32.NewProc("DestroyWindow").Call(handle)
		return progressWindow{}, fmt.Errorf("Окно AmorX: %w", err)
	}
	font, _, _ := syscall.NewLazyDLL("gdi32.dll").NewProc("GetStockObject").Call(17)
	user32.NewProc("SendMessageW").Call(label, wmSetFont, font, 1)
	user32.NewProc("ShowWindow").Call(handle, 1)
	return progressWindow{handle: handle, label: label}, nil
}

func progressWndProc(handle, event, wparam, lparam uintptr) uintptr {
	switch event {
	case wmClose, wmFinish:
		user32.NewProc("DestroyWindow").Call(handle)
	case wmDestroy:
		user32.NewProc("PostQuitMessage").Call(0)
	default:
		value, _, _ := user32.NewProc("DefWindowProcW").Call(handle, event, wparam, lparam)
		return value
	}
	return 0
}

func (w progressWindow) status(value string) {
	// A call through SendMessage executes SetWindowText on the window thread.
	user32.NewProc("SendMessageW").Call(w.label, 0x000C, 0, uintptr(unsafe.Pointer(wide(value))))
}

func (w progressWindow) finish() {
	user32.NewProc("PostMessageW").Call(w.handle, wmFinish, 0, 0)
}

func (w progressWindow) loop() error {
	var msg struct {
		handle  uintptr
		message uint32
		wparam  uintptr
		lparam  uintptr
		time    uint32
		x, y    int32
		private uint32
	}
	get := user32.NewProc("GetMessageW")
	translate := user32.NewProc("TranslateMessage")
	dispatch := user32.NewProc("DispatchMessageW")
	for {
		status, _, err := get.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		switch int32(status) {
		case -1:
			return fmt.Errorf("Окно AmorX: %w", err)
		case 0:
			return nil
		default:
			translate.Call(uintptr(unsafe.Pointer(&msg)))
			dispatch.Call(uintptr(unsafe.Pointer(&msg)))
		}
	}
}
