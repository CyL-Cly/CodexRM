package protocol

import (
	"bytes"
	"encoding/json"
	"strings"
)

// ChatImagePrefix is the image-only counterpart to the direct-only attachment RPCs.
const ChatImagePrefix = "codexrm/chatImage/"

// RequiresDirectFileTransfer also checks Codex messages that carry inline file contents.
func RequiresDirectFileTransfer(method string, message json.RawMessage) bool {
	if IsFileTransferRPC(method) {
		return true
	}
	_, hasInlineFile := WithoutInlineFiles(message)
	return hasInlineFile
}

// WithoutInlineFiles removes structured file contents from Codex RPC messages.
// File references, message text and other metadata remain available on the relay.
func WithoutInlineFiles(message json.RawMessage) (json.RawMessage, bool) {
	if !bytes.Contains(message, []byte(`\u`)) &&
		(!bytes.Contains(message, []byte(`"type"`)) || (!bytes.Contains(message, []byte("image")) &&
			!bytes.Contains(message, []byte("Image")) && !bytes.Contains(message, []byte(`"blob"`)))) {
		return message, false
	}
	decoder := json.NewDecoder(bytes.NewReader(message))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil || !removeInlineFiles(value) {
		return message, false
	}
	filtered, err := json.Marshal(value)
	if err != nil {
		return message, false
	}
	return filtered, true
}

func removeInlineFiles(value any) (changed bool) {
	switch value := value.(type) {
	case []any:
		for _, item := range value {
			if removeInlineFiles(item) {
				changed = true
			}
		}
	case map[string]any:
		kind, _ := value["type"].(string)
		switch kind {
		case "imageGeneration":
			if result, ok := value["result"].(string); ok && result != "" {
				delete(value, "result")
				changed = true
			}
		case "resource":
			if resource, ok := value["resource"].(map[string]any); ok && resource["blob"] != nil {
				delete(resource, "blob")
				changed = true
			}
		}
		switch kind {
		case "image", "inputImage", "input_image", "output_image", "image_url", "localImage", "imageView", "imageGeneration":
			if data, ok := value["data"].(string); ok && data != "" {
				delete(value, "data")
				changed = true
			}
			for _, key := range []string{"url", "imageUrl", "image_url", "source", "path", "savedPath", "outputPath"} {
				field, container := key, value
				if nested, ok := value[key].(map[string]any); ok {
					field, container = "url", nested
				}
				if url, ok := container[field].(string); ok && len(url) >= len("data:") && strings.EqualFold(url[:len("data:")], "data:") {
					delete(container, field)
					changed = true
				}
			}
		}
		for _, item := range value {
			if removeInlineFiles(item) {
				changed = true
			}
		}
	}
	return changed
}
