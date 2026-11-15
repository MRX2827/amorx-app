// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	_ "embed"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

//go:embed image.zip
var image []byte

func main() {
	program, err := install()
	if err == nil {
		app := exec.Command(program, os.Args[1:]...)
		app.Dir = filepath.Dir(program)
		if len(os.Args) > 1 && os.Args[1] == "--verify-audio" {
			if err = app.Run(); err == nil {
				return
			}
		} else if err = app.Start(); err == nil {
			return
		}
	}
	message("Не удалось открыть AmorX.\r\n\r\n" + err.Error())
	os.Exit(1)
}

func message(text string) {
	title, _ := syscall.UTF16PtrFromString("AmorX")
	value, _ := syscall.UTF16PtrFromString(text)
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(
		0, uintptr(unsafe.Pointer(value)), uintptr(unsafe.Pointer(title)), 0x10)
}

func install() (string, error) {
	local, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(image)
	versions := filepath.Join(local, "AmorX Desktop", "versions")
	destination := filepath.Join(versions, fmt.Sprintf("%x", sum[:12]))
	program := filepath.Join(destination, "AmorX.exe")
	if _, err := os.Stat(program); err == nil {
		return program, nil
	}
	if err := os.MkdirAll(versions, 0700); err != nil {
		return "", err
	}
	stage, err := os.MkdirTemp(versions, ".opening-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(stage)
	archive, err := zip.NewReader(bytes.NewReader(image), int64(len(image)))
	if err != nil {
		return "", err
	}
	for _, entry := range archive.File {
		name := filepath.FromSlash(strings.TrimSuffix(entry.Name, "/"))
		if !filepath.IsLocal(name) || strings.ContainsAny(entry.Name, "\\:") {
			return "", fmt.Errorf("Недопустимое имя файла %q", entry.Name)
		}
		target := filepath.Join(stage, name)
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0700); err != nil {
				return "", err
			}
			continue
		}
		if !entry.Mode().IsRegular() {
			return "", fmt.Errorf("Недопустимый файл %q", entry.Name)
		}
		if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return "", err
		}
		if err := extract(target, entry); err != nil {
			return "", err
		}
	}
	if _, err := os.Stat(filepath.Join(stage, "AmorX.exe")); err != nil {
		return "", err
	}
	if err := os.Rename(stage, destination); err != nil {
		if _, completed := os.Stat(program); completed != nil {
			return "", err
		}
	}
	return program, nil
}

func extract(target string, entry *zip.File) error {
	source, err := entry.Open()
	if err != nil {
		return err
	}
	defer source.Close()
	destination, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(destination, source)
	closeErr := destination.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}
