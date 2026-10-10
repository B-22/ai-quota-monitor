package claudedesktop

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"testing"
)

func encryptV10(t *testing.T, key, plaintext []byte) string {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	nonce := bytes.Repeat([]byte{0x01}, 12)
	sealed := gcm.Seal(nil, nonce, plaintext, nil)
	blob := append([]byte("v10"), nonce...)
	blob = append(blob, sealed...)
	return base64.StdEncoding.EncodeToString(blob)
}

func TestDecryptChromiumValueRoundTrip(t *testing.T) {
	key := bytes.Repeat([]byte{0x2a}, 32)
	plaintext := []byte(`{"oauth:tokenCacheV2":"decrypted"}`)
	got, err := decryptChromiumValue(encryptV10(t, key, plaintext), key)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("got %q, want %q", got, plaintext)
	}
}

func TestDecryptChromiumValueWrongKey(t *testing.T) {
	encoded := encryptV10(t, bytes.Repeat([]byte{0x2a}, 32), []byte("secret"))
	if _, err := decryptChromiumValue(encoded, bytes.Repeat([]byte{0x11}, 32)); err == nil {
		t.Fatal("expected authentication failure with wrong key")
	}
}

func TestDecryptChromiumValueRejectsUnknownPrefix(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString([]byte("plain-value"))
	if _, err := decryptChromiumValue(encoded, bytes.Repeat([]byte{0x2a}, 32)); err == nil {
		t.Fatal("expected error for non-v10/v11 value")
	}
}
