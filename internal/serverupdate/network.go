package serverupdate

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/updater"
)

func isHTTPS(u *url.URL) bool {
	return u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.Fragment == ""
}

func ParseFeed(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || !isHTTPS(u) || u.RawQuery != "" || u.ForceQuery {
		return nil, errors.New("the Server update feed must be an HTTPS URL without credentials, query or fragment")
	}
	return u, nil
}

// A manifest stays on its configured origin; the checksummed artifact can use a CDN.
func redirectPolicy(sameOrigin bool) func(*http.Request, []*http.Request) error {
	return func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 || !isHTTPS(req.URL) || (sameOrigin && !strings.EqualFold(req.URL.Host, via[0].URL.Host)) {
			return errors.New("disallowed Server update redirect")
		}
		return nil
	}
}

func get(ctx context.Context, c *http.Client, u string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, errors.New("could not construct update request")
	}
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := c.Do(req)
	if err != nil {
		// Avoid logging signed download URLs or redirect query strings.
		var e *url.Error
		if errors.As(err, &e) {
			return nil, e.Err
		}
		return nil, err
	}
	return resp, nil
}

func readFeed(ctx context.Context, client *http.Client, feed *url.URL) (updater.Manifest, *url.URL, error) {
	c := *client
	c.CheckRedirect = redirectPolicy(true)
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	resp, err := get(ctx, &c, feed.String())
	if err != nil {
		return updater.Manifest{}, nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return updater.Manifest{}, nil, nil // The source has not published any Server release yet.
	}
	if resp.StatusCode != http.StatusOK {
		return updater.Manifest{}, nil, fmt.Errorf("Server update feed returned HTTP %d", resp.StatusCode)
	}
	manifest, err := updater.ReadManifest(resp.Body)
	if err != nil {
		return updater.Manifest{}, nil, err
	}
	u, err := resp.Request.URL.Parse(manifest.DownloadURL)
	if err != nil || !isHTTPS(u) {
		return updater.Manifest{}, nil, errors.New("the Server package download must use HTTPS")
	}
	return manifest, u, nil
}

func download(ctx context.Context, client *http.Client, u *url.URL, filename string, m updater.Manifest) error {
	c := *client
	c.CheckRedirect = redirectPolicy(false)
	ctx, cancel := context.WithTimeout(ctx, 10*time.Minute)
	defer cancel()
	resp, err := get(ctx, &c, u.String())
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Server package download returned HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength >= 0 && resp.ContentLength != m.FileSize {
		return errors.New("Server package download size differs from the manifest")
	}
	f, err := os.OpenFile(filename, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	hash := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, hash), io.LimitReader(resp.Body, m.FileSize+1))
	if err = errors.Join(copyErr, f.Close()); err != nil {
		return err
	}
	if n != m.FileSize || !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), m.SHA256) {
		return errors.New("Server package failed size or SHA-256 verification")
	}
	return nil
}

func ReadHealth(ctx context.Context, client *http.Client, endpoint string) (release.ServerHealth, error) {
	var h release.ServerHealth
	resp, err := get(ctx, client, endpoint)
	if err != nil {
		return h, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return h, fmt.Errorf("health endpoint returned HTTP %d", resp.StatusCode)
	}
	if err = json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&h); err != nil {
		return h, err
	}
	if !h.OK || h.Service != "codexrm" || h.VersionCode <= 0 || h.VersionName == "" {
		return h, errors.New("health endpoint did not identify a versioned CodexRM Server")
	}
	return h, nil
}
