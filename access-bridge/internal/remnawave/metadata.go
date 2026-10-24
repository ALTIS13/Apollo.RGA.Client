package remnawave

import (
	"context"
	"encoding/json"
	"net/http"
	"regexp"
)

// Strict SemVer 2.0.0: no prefix, whitespace, or leading zero numeric IDs.
var semanticVersion = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(\+([0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*))?$`)

// ValidateVersion validates a server-owned exact version pin, not a range.
func ValidateVersion(version string) error {
	if !semanticVersion.MatchString(version) {
		return ErrInvalidConfig
	}
	return nil
}

// MetadataVersion reports live version provenance, not source-byte parity or
// any bound on accounting lag. The shared request enforces auth and size/time limits.
func (c *Client) MetadataVersion(ctx context.Context) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	body, err := c.request(ctx, http.MethodGet, "/api/system/metadata", nil)
	if err != nil {
		return "", err
	}
	var metadata struct {
		Response struct {
			Version string `json:"version"`
		} `json:"response"`
	}
	if json.Unmarshal(body, &metadata) != nil || ValidateVersion(metadata.Response.Version) != nil {
		return "", ErrInvalidResponse
	}
	return metadata.Response.Version, nil
}
