// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"crypto/sha256"
	"debug/pe"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

const (
	windowsManifestURL  = "https://mrx2827.github.io/amorxsyte/windows-update.json"
	maxManifestBytes    = 64 * 1024
	maxWindowsAssetSize = 256 * 1024 * 1024
)

type windowsRelease struct {
	Version string `json:"version"`
	Notes   string `json:"notes"`
	EXE     struct {
		URL    string `json:"url"`
		Size   int64  `json:"size"`
		SHA256 string `json:"sha256"`
	} `json:"exe"`
}

func versionParts(version string) ([]uint32, error) {
	fields := strings.Split(version, ".")
	if len(fields) != 4 {
		return nil, fmt.Errorf("неверная версия %q", version)
	}
	parts := make([]uint32, len(fields))
	for i, field := range fields {
		number, err := strconv.ParseUint(field, 10, 32)
		if err != nil || strconv.FormatUint(number, 10) != field {
			return nil, fmt.Errorf("неверная версия %q", version)
		}
		parts[i] = uint32(number)
	}
	return parts, nil
}

func newerVersion(candidate, current string) bool {
	candidateParts, candidateErr := versionParts(candidate)
	currentParts, currentErr := versionParts(current)
	return candidateErr == nil && currentErr == nil && slices.Compare(candidateParts, currentParts) > 0
}

func (r windowsRelease) valid() bool {
	if _, err := versionParts(r.Version); err != nil || r.EXE.Size <= 0 || r.EXE.Size > maxWindowsAssetSize {
		return false
	}
	digest, err := hex.DecodeString(r.EXE.SHA256)
	if err != nil || len(digest) != sha256.Size {
		return false
	}
	asset, err := url.Parse(r.EXE.URL)
	if err != nil || asset.Scheme != "https" || !strings.EqualFold(asset.Host, "github.com") ||
		asset.User != nil || asset.RawQuery != "" || asset.Fragment != "" {
		return false
	}
	segments := strings.Split(strings.TrimPrefix(asset.Path, "/"), "/")
	return len(segments) == 6 && strings.EqualFold(strings.Join(segments[:4], "/"), "MRX2827/amorxsyte/releases/download") &&
		strings.TrimPrefix(segments[4], "v") == r.Version && segments[5] == r.filename()
}

func (r windowsRelease) filename() string {
	return "AmorX-" + r.Version + "-Windows-x64.exe"
}

func readWindowsRelease(reader io.Reader) (windowsRelease, error) {
	var release windowsRelease
	data, err := io.ReadAll(io.LimitReader(reader, maxManifestBytes+1))
	if err != nil {
		return release, err
	}
	if len(data) > maxManifestBytes || json.Unmarshal(data, &release) != nil || !release.valid() {
		return release, errors.New("неверный файл обновления AmorX для Windows")
	}
	release.EXE.SHA256 = strings.ToLower(release.EXE.SHA256)
	return release, nil
}

func fetchWindowsRelease(ctx context.Context, client *http.Client, manifestURL string) (windowsRelease, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return windowsRelease{}, err
	}
	req.Header.Set("Cache-Control", "no-cache")
	response, err := client.Do(req)
	if err != nil {
		return windowsRelease{}, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return windowsRelease{}, fmt.Errorf("сервер обновлений вернул %s", response.Status)
	}
	return readWindowsRelease(response.Body)
}

func verifyWindowsExecutable(binary string, release windowsRelease) error {
	file, err := os.Open(binary)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	if info.Size() != release.EXE.Size {
		return errors.New("размер EXE отличается от ожидаемого")
	}
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return err
	}
	if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), release.EXE.SHA256) {
		return errors.New("SHA-256 EXE отличается от ожидаемой")
	}
	exe, err := pe.NewFile(file)
	if err != nil {
		return fmt.Errorf("формат обновления EXE: %w", err)
	}
	if exe.Machine != pe.IMAGE_FILE_MACHINE_AMD64 || exe.Characteristics&pe.IMAGE_FILE_DLL != 0 {
		return errors.New("обновление не является приложением Windows x64")
	}
	return nil
}

func installedWindowsUpdate(root, current string) string {
	versions := filepath.Join(root, "windows-releases")
	entries, err := os.ReadDir(versions)
	if err != nil {
		return ""
	}
	var latest string
	for _, entry := range entries {
		version := entry.Name()
		if !entry.IsDir() || !newerVersion(version, current) {
			continue
		}
		releaseDir := filepath.Join(versions, version)
		record, err := os.Open(filepath.Join(releaseDir, "release.json"))
		if err != nil {
			continue
		}
		release, err := readWindowsRelease(record)
		record.Close()
		if err != nil || release.Version != version {
			continue
		}
		exe := filepath.Join(releaseDir, release.filename())
		if verifyWindowsExecutable(exe, release) == nil {
			current, latest = version, exe
		}
	}
	return latest
}

func installWindowsUpdate(ctx context.Context, root string, release windowsRelease, status func(string)) (string, error) {
	if !release.valid() {
		return "", errors.New("неверные данные обновления AmorX для Windows")
	}
	versions := filepath.Join(root, "windows-releases")
	if err := os.MkdirAll(versions, 0700); err != nil {
		return "", err
	}
	stage, err := os.MkdirTemp(versions, ".download-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(stage)

	filename := release.filename()
	binary := filepath.Join(stage, filename)
	client := &http.Client{Timeout: 10 * time.Minute, CheckRedirect: allowGitHubAssetRedirect}
	download := verifiedDownload{name: "AmorX " + release.Version + " для Windows", url: release.EXE.URL,
		size: release.EXE.Size, checksum: strings.ToLower(release.EXE.SHA256)}
	if err := download.downloadOnce(ctx, client, binary, status); err != nil {
		return "", err
	}
	status("Проверка обновления…")
	if err := verifyWindowsExecutable(binary, release); err != nil {
		return "", err
	}
	record, err := json.Marshal(release)
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(stage, "release.json"), record, 0600); err != nil {
		return "", err
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	destination := filepath.Join(versions, release.Version)
	// A failed update can leave a cached directory. Replace it only after verifying the new EXE.
	if err := os.RemoveAll(destination); err != nil {
		return "", err
	}
	if err := os.Rename(stage, destination); err != nil {
		return "", err
	}
	return filepath.Join(destination, filename), nil
}

func allowGitHubAssetRedirect(req *http.Request, via []*http.Request) error {
	host := req.URL.Host
	if len(via) >= 5 || req.URL.Scheme != "https" || req.URL.User != nil ||
		!(strings.EqualFold(host, "github.com") || strings.EqualFold(host, "release-assets.githubusercontent.com") ||
			strings.EqualFold(host, "objects.githubusercontent.com")) {
		return errors.New("GitHub перенаправил загрузку на неожиданный адрес")
	}
	return nil
}
