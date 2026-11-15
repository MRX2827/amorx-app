// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed payload.apk
var bundledAPK []byte

// Set by build.py using the release metadata.
var launcherVersion, apkVersionCode string

const sdkTerms = "https://developer.android.com/studio/terms"

var emulatorArchive = androidArchive{
	prefix: "emulator", destination: "emulator", requiredFile: "emulator.exe",
	verifiedDownload: verifiedDownload{
		name:     "Android Emulator",
		url:      "https://dl.google.com/android/repository/emulator-windows_x64-16740729.zip",
		checksum: "625276a1d3ac6b15d00f8aad5e233e7dc3a7cb71435f97d64e3be298399e171d", size: 455344678,
	},
}

var adbArchive = androidArchive{
	prefix: "platform-tools", destination: "platform-tools", requiredFile: "adb.exe",
	verifiedDownload: verifiedDownload{
		name:     "Android Platform Tools",
		url:      "https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip",
		checksum: "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99", size: 8044989,
	},
}

var imageArchive = androidArchive{
	prefix: "x86_64", destination: imageLocation, requiredFile: "system.img",
	verifiedDownload: verifiedDownload{
		name:     "Android 16",
		url:      "https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip",
		checksum: "e1b9d9fb665001ef27b16e57d8762a2d54aec6bff617e17506edb8676667b9da", size: 844217077,
	},
}

func main() {
	runtime.LockOSThread()
	updates := !slices.Contains(os.Args[1:], "--no-launcher-update")
	next, err := run(updates)
	if err == nil && next != "" {
		command := exec.Command(next, os.Args[1:]...)
		if err = command.Start(); err == nil {
			return
		}
		message(0, "Новая версия не открылась. Запускаем текущую версию AmorX.", 0x30)
		_, err = run(false)
	}
	if err != nil {
		message(0, err.Error(), 0x10)
	}
}

func run(updates bool) (string, error) {
	if !isNativeAMD64() {
		return "", fmt.Errorf("Для AmorX нужен Windows 10 или 11 на 64-разрядном процессоре Intel или AMD.")
	}
	mutex, _, mutexErr := kernel.NewProc("CreateMutexW").Call(0, 0, uintptr(unsafe.Pointer(wide(`Local\AmorXWindowsLauncher`))))
	if mutex == 0 {
		return "", fmt.Errorf("Не удалось открыть AmorX: %w", mutexErr)
	}
	defer kernel.NewProc("CloseHandle").Call(mutex)
	if mutexErr == syscall.Errno(183) {
		message(0, "AmorX уже запускается.", 0x40)
		return "", nil
	}

	local, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	root := filepath.Join(local, "AmorX")
	if err := os.MkdirAll(root, 0700); err != nil {
		return "", err
	}
	if updates {
		if next := installedWindowsUpdate(root, launcherVersion); next != "" {
			return next, nil
		}
	}
	logPath := filepath.Join(root, "launcher.log")
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	defer logFile.Close()
	fmt.Fprintln(logFile, time.Now().Format(time.RFC3339), "AmorX Windows", launcherVersion)
	android := newRuntime(root, ".exe", 17537, logFile)
	android.hideConsole = func(cmd *exec.Cmd) {
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	}

	firstSetup := !emulatorArchive.installed(android.sdk) || !adbArchive.installed(android.sdk) || !imageArchive.installed(android.sdk)
	if firstSetup {
		if free, ok := freeDisk(root); ok && free < 8*1024*1024*1024 {
			return "", fmt.Errorf("Освободите не менее 8 ГБ на диске, где находится %s, и запустите AmorX снова.", root)
		}
		if !acceptSDKTerms() {
			return "", nil
		}
	}
	window, err := newProgressWindow()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type launchResult struct {
		next string
		err  error
	}
	result := make(chan launchResult, 1)
	go func() {
		defer window.finish()
		if updates {
			if next := checkForWindowsUpdate(ctx, root, window, logFile); next != "" {
				result <- launchResult{next: next}
				return
			}
		}
		result <- launchResult{err: start(ctx, android, window.status, slices.Contains(os.Args[1:], "--software"))}
	}()
	if err := window.loop(); err != nil {
		return "", err
	}
	select {
	case outcome := <-result:
		if outcome.err != nil {
			fmt.Fprintln(logFile, outcome.err)
			return "", fmt.Errorf("%w\n\nЖурнал запуска\n%s", outcome.err, logPath)
		}
		return outcome.next, nil
	default:
		// The user closed the progress window and canceled startup.
		cancel()
		<-result
	}
	return "", nil
}

func checkForWindowsUpdate(ctx context.Context, root string, window progressWindow, logFile *os.File) string {
	window.status("Проверка обновлений AmorX…")
	client := &http.Client{Timeout: 3 * time.Second}
	release, err := fetchWindowsRelease(ctx, client, windowsManifestURL)
	if err != nil {
		fmt.Fprintln(logFile, "Проверка обновлений Windows:", err)
		return ""
	}
	if !newerVersion(release.Version, launcherVersion) {
		return ""
	}
	prompt := fmt.Sprintf("Доступна версия AmorX %s для Windows (%.1f МБ). Скачать и открыть?", release.Version, float64(release.EXE.Size)/(1024*1024))
	notes := strings.TrimSpace(strings.ReplaceAll(release.Notes, "\x00", ""))
	if notes != "" {
		prompt += "\n\n" + notes
	}
	if message(window.handle, prompt, 0x124) != 6 {
		return ""
	}

	next, err := installWindowsUpdate(ctx, root, release, window.status)
	if err != nil {
		fmt.Fprintln(logFile, "Установка обновления Windows:", err)
		if ctx.Err() == nil {
			message(window.handle, "Обновление не установилось. Открываем текущую версию AmorX. Повторить загрузку можно при следующем запуске.", 0x30)
		}
	}
	return next
}

func start(ctx context.Context, android androidRuntime, status func(string), software bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	versionCode, err := strconv.Atoi(apkVersionCode)
	if err != nil || versionCode <= 0 {
		return fmt.Errorf("Не удалось определить версию встроенного APK.")
	}
	if err := emulatorArchive.install(ctx, android.sdk, status); err != nil {
		return err
	}
	status("Проверка аппаратной виртуализации…")
	check, cancel := context.WithTimeout(ctx, 20*time.Second)
	err = android.checkAcceleration(check)
	cancel()
	if err != nil {
		return err
	}
	for _, archive := range []androidArchive{adbArchive, imageArchive} {
		if err := archive.install(ctx, android.sdk, status); err != nil {
			return err
		}
	}
	if err := android.configureDevice(); err != nil {
		return err
	}
	status("Запуск Android…")
	boot, done := context.WithTimeout(ctx, bootTimeout)
	defer done()
	if out, err := android.adbCommand(boot, "--one-device", "amorx-virtual-only", "start-server"); err != nil {
		return fmt.Errorf("Запуск Android Platform Tools: %w (%s)", err, out)
	}
	gpu := "auto"
	if software {
		gpu = "software"
	}
	serial, exited, err := android.startDevice(boot, gpu)
	if err != nil {
		return err
	}
	if err := android.waitForBoot(boot, serial, exited); err != nil {
		return err
	}
	action, finish := context.WithTimeout(ctx, 90*time.Second)
	defer finish()
	status("Подготовка AmorX " + launcherVersion + "…")
	if err := android.ensureApp(action, serial, bytes.NewReader(bundledAPK), versionCode); err != nil {
		return err
	}
	return android.openApp(action, serial)
}

func acceptSDKTerms() bool {
	if message(0, "AmorX загрузит Android-среду с серверов Google (около 1,3 ГБ). Для установки нужно принять условия Android SDK. Открыть условия в браузере?", 0x24) != 6 {
		return false
	}
	shell := syscall.NewLazyDLL("shell32.dll")
	shell.NewProc("ShellExecuteW").Call(0, uintptr(unsafe.Pointer(wide("open"))), uintptr(unsafe.Pointer(wide(sdkTerms))), 0, 0, 1)
	return message(0, "Прочитайте условия Android SDK в браузере. Чтобы принять их и начать установку, нажмите «Да».\n\n"+sdkTerms, 0x124) == 6
}

func freeDisk(path string) (uint64, bool) {
	var bytes uint64
	ok, _, _ := kernel.NewProc("GetDiskFreeSpaceExW").Call(uintptr(unsafe.Pointer(wide(path))), uintptr(unsafe.Pointer(&bytes)), 0, 0)
	return bytes, ok != 0
}

func isNativeAMD64() bool {
	proc := kernel.NewProc("IsWow64Process2")
	if proc.Find() != nil {
		return true
	}
	current, _, _ := kernel.NewProc("GetCurrentProcess").Call()
	var process, native uint16
	ok, _, _ := proc.Call(current, uintptr(unsafe.Pointer(&process)), uintptr(unsafe.Pointer(&native)))
	return ok == 0 || native == 0x8664
}
