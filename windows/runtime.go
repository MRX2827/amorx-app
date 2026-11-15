// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const (
	deviceName    = "AmorX_API_36"
	appPackage    = "chromahub.rhythm.app"
	appActivity   = appPackage + "/.activities.MainActivity"
	bootTimeout   = 4 * time.Minute
	imageLocation = "system-images/android-36/default/x86_64/"
)

type androidRuntime struct {
	root, sdk, avds string
	adb, emulator   string
	adbPort         int
	log             *os.File
	hideConsole     func(*exec.Cmd)
}

func newRuntime(root, suffix string, adbPort int, log *os.File) androidRuntime {
	sdk := filepath.Join(root, "sdk")
	return androidRuntime{
		root: root, sdk: sdk, avds: filepath.Join(root, "android", "avd"), log: log,
		adb:      filepath.Join(sdk, "platform-tools", "adb"+suffix),
		emulator: filepath.Join(sdk, "emulator", "emulator"+suffix),
		adbPort:  adbPort,
	}
}

func (a androidRuntime) command(ctx context.Context, program string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, program, args...)
	environment := slices.DeleteFunc(os.Environ(), func(entry string) bool {
		key, _, _ := strings.Cut(entry, "=")
		return strings.EqualFold(key, "ADB_SERVER_SOCKET") || strings.EqualFold(key, "ANDROID_ADB_SERVER_ADDRESS")
	})
	cmd.Env = append(environment,
		"ANDROID_HOME="+a.sdk, "ANDROID_SDK_ROOT="+a.sdk,
		"ANDROID_USER_HOME="+filepath.Dir(a.avds), "ANDROID_AVD_HOME="+a.avds,
		"ANDROID_EMULATOR_HOME="+filepath.Dir(a.avds),
		"ANDROID_ADB_SERVER_PORT="+strconv.Itoa(a.adbPort),
		"ADB_MDNS_AUTO_CONNECT=0",
	)
	return cmd
}

func (a androidRuntime) adbCommand(ctx context.Context, args ...string) (string, error) {
	cmd := a.command(ctx, a.adb, args...)
	if a.hideConsole != nil {
		a.hideConsole(cmd)
	}
	output, err := cmd.CombinedOutput()
	return strings.TrimSpace(string(output)), err
}

func (a androidRuntime) configureDevice() error {
	device := filepath.Join(a.avds, deviceName+".avd")
	if err := os.MkdirAll(device, 0700); err != nil {
		return err
	}
	index := fmt.Sprintf("avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-36\n", filepath.ToSlash(device), deviceName)
	if err := os.WriteFile(filepath.Join(a.avds, deviceName+".ini"), []byte(index), 0600); err != nil {
		return err
	}
	configFile := filepath.Join(device, "config.ini")
	if fileExists(configFile) {
		return nil
	}
	config := `AvdId = AmorX_API_36
avd.ini.displayname = AmorX
avd.ini.encoding = UTF-8
abi.type = x86_64
tag.display = Default Android System Image
tag.id = default
PlayStore.enabled = false
disk.dataPartition.size = 6442450944
hw.cpu.arch = x86_64
hw.cpu.ncore = 4
hw.gpu.enabled = yes
hw.gpu.mode = auto
hw.keyboard = yes
hw.lcd.density = 420
hw.lcd.height = 2400
hw.lcd.width = 1080
hw.ramSize = 2560
showDeviceFrame = no
vm.heapSize = 256
image.sysdir.1 = ` + imageLocation + "\n"
	return os.WriteFile(configFile, []byte(config), 0600)
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func (a androidRuntime) checkAcceleration(ctx context.Context) error {
	cmd := a.command(ctx, a.emulator, "-accel-check")
	if a.hideConsole != nil {
		a.hideConsole(cmd)
	}
	data, err := cmd.CombinedOutput()
	fmt.Fprintln(a.log, string(data))
	output := strings.ReplaceAll(string(data), "\r\n", "\n")
	if err != nil || !strings.Contains(output, "accel:\n0\n") {
		return errors.New("Включите виртуализацию в BIOS/UEFI и «Платформа низкоуровневой оболочки Windows» (Windows Hypervisor Platform) в компонентах Windows, перезагрузите ПК и запустите AmorX снова.")
	}
	return nil
}

func (a androidRuntime) runningDevice(ctx context.Context) (string, error) {
	list, err := a.adbCommand(ctx, "devices")
	if err != nil {
		return "", fmt.Errorf("ADB devices: %w (%s)", err, list)
	}
	for _, line := range strings.Split(list, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 || fields[1] != "device" || !strings.HasPrefix(fields[0], "emulator-") {
			continue
		}
		name, err := a.adbCommand(ctx, "-s", fields[0], "emu", "avd", "name")
		if err == nil && strings.TrimSpace(strings.SplitN(name, "\n", 2)[0]) == deviceName {
			return fields[0], nil
		}
	}
	return "", nil
}

func availableConsolePort() (int, error) {
	for port := 5568; port <= 5584; port += 2 {
		console, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			continue
		}
		adb, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", port+1))
		console.Close()
		if err == nil {
			adb.Close()
			return port, nil
		}
	}
	return 0, errors.New("Нет свободного порта для Android. Закройте лишние окна эмулятора и повторите запуск.")
}

func (a androidRuntime) startDevice(ctx context.Context, gpu string, options ...string) (string, <-chan error, error) {
	serial, err := a.runningDevice(ctx)
	if err != nil || serial != "" {
		return serial, nil, err
	}
	port, err := availableConsolePort()
	if err != nil {
		return "", nil, err
	}
	args := []string{"-avd", deviceName, "-port", strconv.Itoa(port), "-no-boot-anim", "-gpu", gpu}
	args = append(args, options...)
	// The Android window must outlive the launcher and its startup timeout.
	cmd := a.command(context.Background(), a.emulator, args...)
	cmd.Stdout, cmd.Stderr = a.log, a.log
	if err := cmd.Start(); err != nil {
		return "", nil, fmt.Errorf("Запуск Android: %w", err)
	}
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	return fmt.Sprintf("emulator-%d", port), exited, nil
}

func (a androidRuntime) waitForBoot(ctx context.Context, serial string, exited <-chan error) error {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		query, cancel := context.WithTimeout(ctx, 4*time.Second)
		ready, err := a.adbCommand(query, "-s", serial, "shell", "getprop", "sys.boot_completed")
		cancel()
		if err == nil && ready == "1" {
			return nil
		}
		select {
		case err := <-exited:
			return fmt.Errorf("Окно Android закрылось до загрузки (%v)", err)
		case <-ctx.Done():
			return errors.New("Android не успел загрузиться. Если окно Android ещё открыто, подождите и снова запустите AmorX.exe.")
		case <-ticker.C:
		}
	}
}

var versionLine = regexp.MustCompile(`(?m)^\s*versionCode=(\d+)\b`)

func (a androidRuntime) ensureApp(ctx context.Context, serial string, apk io.Reader, bundledVersionCode int) error {
	if output, err := a.adbCommand(ctx, "-s", serial, "shell", "dumpsys", "package", appPackage); err == nil {
		if match := versionLine.FindStringSubmatch(output); match != nil {
			version, err := strconv.Atoi(match[1])
			if err == nil && version >= bundledVersionCode {
				return nil
			}
		}
	}
	temp, err := os.CreateTemp(a.root, "AmorX-*.apk")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())
	_, copyErr := io.Copy(temp, apk)
	if err := errors.Join(copyErr, temp.Close()); err != nil {
		return err
	}
	output, err := a.adbCommand(ctx, "-s", serial, "install", "-r", "--no-incremental", temp.Name())
	if err != nil || output != "Success" && !strings.HasSuffix(output, "\nSuccess") {
		return fmt.Errorf("Установка AmorX: %s (%v)", output, err)
	}
	return nil
}

func (a androidRuntime) openApp(ctx context.Context, serial string) error {
	a.adbCommand(ctx, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
	a.adbCommand(ctx, "-s", serial, "shell", "wm", "dismiss-keyguard")
	output, err := a.adbCommand(ctx, "-s", serial, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
		"-c", "android.intent.category.LAUNCHER", "-n", appActivity)
	fmt.Fprintln(a.log, output)
	if err != nil || !strings.Contains(output, "Status: ok") {
		return fmt.Errorf("Запуск AmorX: %s (%v)", output, err)
	}
	return nil
}
