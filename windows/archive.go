// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

type verifiedDownload struct {
	name, url, checksum string
	size                int64
}

type androidArchive struct {
	verifiedDownload
	prefix, destination, requiredFile string
}

func (a androidArchive) installed(sdk string) bool {
	return fileExists(filepath.Join(sdk, a.destination, a.requiredFile))
}

func (a androidArchive) install(ctx context.Context, sdk string, status func(string)) error {
	if a.installed(sdk) {
		return nil
	}
	dir := filepath.Join(sdk, a.destination)
	if err := os.MkdirAll(filepath.Dir(dir), 0700); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Dir(dir), ".amorx-setup-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)

	archive := filepath.Join(stage, "android.zip")
	if err := a.download(ctx, archive, status); err != nil {
		return err
	}
	status("Распаковка. " + a.name)
	files := filepath.Join(stage, "files")
	if err := extractArchive(ctx, archive, a.prefix, files); err != nil {
		return err
	}
	if !fileExists(filepath.Join(files, a.requiredFile)) {
		return fmt.Errorf("В архиве «%s» отсутствует %s", a.name, a.requiredFile)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	// A complete installation only becomes visible after the archive is verified and extracted.
	return os.Rename(files, dir)
}

func (a verifiedDownload) download(ctx context.Context, destination string, status func(string)) error {
	client := &http.Client{Timeout: 30 * time.Minute}
	for attempt := 1; ; attempt++ {
		if err := a.downloadOnce(ctx, client, destination, status); err != nil {
			if attempt == 3 || ctx.Err() != nil {
				return fmt.Errorf("Не удалось скачать «%s»: %w", a.name, err)
			}
			status(fmt.Sprintf("Соединение прервалось. Повтор загрузки «%s» (%d/3)", a.name, attempt+1))
			select {
			case <-time.After(time.Duration(attempt) * time.Second):
			case <-ctx.Done():
				return ctx.Err()
			}
			continue
		}
		return nil
	}
}

func (a verifiedDownload) downloadOnce(ctx context.Context, client *http.Client, destination string, status func(string)) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.url, nil)
	if err != nil {
		return err
	}
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || response.ContentLength != -1 && response.ContentLength != a.size {
		return fmt.Errorf("неожиданный ответ загрузки (%s, %d байт)", response.Status, response.ContentLength)
	}
	file, err := os.Create(destination)
	if err != nil {
		return err
	}
	hash := sha256.New()
	progress := &downloadProgress{total: a.size, name: a.name, status: status}
	progress.report()
	size, copyErr := io.Copy(io.MultiWriter(file, hash, progress), io.LimitReader(response.Body, a.size+1))
	if err := errors.Join(copyErr, file.Close()); err != nil {
		return err
	}
	if size != a.size || hex.EncodeToString(hash.Sum(nil)) != a.checksum {
		return errors.New("размер или SHA-256 архива отличается от ожидаемого")
	}
	return nil
}

type downloadProgress struct {
	received, total int64
	name            string
	last            time.Time
	status          func(string)
}

func (p *downloadProgress) Write(data []byte) (int, error) {
	p.received += int64(len(data))
	if time.Since(p.last) >= time.Second || p.received == p.total {
		p.report()
	}
	return len(data), nil
}

func (p *downloadProgress) report() {
	p.last = time.Now()
	p.status(fmt.Sprintf("Загрузка. %s — %d%% (%d из %d МБ)", p.name, 100*p.received/p.total,
		p.received/(1024*1024), p.total/(1024*1024)))
}

func extractArchive(ctx context.Context, source, prefix, destination string) error {
	archive, err := zip.OpenReader(source)
	if err != nil {
		return err
	}
	defer archive.Close()
	for _, entry := range archive.File {
		if err := ctx.Err(); err != nil {
			return err
		}
		name, ok := strings.CutPrefix(entry.Name, prefix+"/")
		if !ok || name == "" {
			continue
		}
		name = strings.TrimSuffix(name, "/")
		if !filepath.IsLocal(filepath.FromSlash(name)) || path.Clean(name) != name || strings.ContainsAny(name, "\\:") {
			return fmt.Errorf("недопустимый путь в архиве %q", entry.Name)
		}
		target := filepath.Join(destination, filepath.FromSlash(name))
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0700); err != nil {
				return err
			}
			continue
		}
		if !entry.Mode().IsRegular() {
			return fmt.Errorf("неподдерживаемый файл в архиве %q", entry.Name)
		}
		if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return err
		}
		if err := extractFile(entry, target); err != nil {
			return err
		}
	}
	return nil
}

func extractFile(entry *zip.File, target string) error {
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
	return errors.Join(copyErr, destination.Close())
}
