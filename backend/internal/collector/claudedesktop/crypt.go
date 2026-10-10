package claudedesktop

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

func base64Decode(s string) ([]byte, error) {
	if pad := len(s) % 4; pad != 0 {
		s += strings.Repeat("=", 4-pad)
	}
	return base64.StdEncoding.DecodeString(s)
}

// decryptChromiumValue decodes a Chromium "v10"/"v11" AES-GCM value (used by
// Claude Desktop for Local State and config.json token caches). Only the GCM
// scheme is handled, which is what Windows Desktop produces.
func decryptChromiumValue(encoded string, key []byte) ([]byte, error) {
	blob, err := base64Decode(encoded)
	if err != nil {
		return nil, err
	}
	if len(blob) > 3 && blob[0] == 'v' && (blob[1] == '1' || blob[1] == '0') &&
		(blob[2] == '0' || blob[2] == '1') {
		return aesGCMDecrypt(key, blob[3:])
	}
	return nil, errors.New("unsupported credential encoding (expected v10/v11 prefix)")
}

func aesGCMDecrypt(key, data []byte) ([]byte, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("unexpected AES key length %d", len(key))
	}
	if len(data) < 12+16 {
		return nil, errors.New("ciphertext shorter than nonce+tag")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, data[:12], data[12:], nil)
}
