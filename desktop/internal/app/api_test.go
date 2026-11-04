package app

import (
	"net"
	"net/http"
	"strings"
	"testing"
	"testing/fstest"
	"time"
)

func TestAPISecurity(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	e := NewEngine()
	a := &API{E: e}
	addr, err := a.Listen()
	if err != nil {
		t.Fatal(err)
	}
	go a.Serve(fstest.MapFS{"index.html": {Data: []byte("ui")}})
	time.Sleep(100 * time.Millisecond)
	get := func(path, tok, host string) int {
		req, _ := http.NewRequest(http.MethodGet, "http://"+addr+path, nil)
		if tok != "" {
			req.Header.Set("X-Shield-Token", tok)
		}
		if host != "" {
			req.Host = host
		}
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		return r.StatusCode
	}
	if get("/api/state", "", "") != 403 || get("/api/state", "wrong", "") != 403 {
		t.Fatal("missing/wrong token accepted")
	}
	if get("/api/state", a.Token, "") != 200 {
		t.Fatal("valid token rejected")
	}
	if get("/api/state", a.Token, "evil.example:80") != http.StatusMisdirectedRequest {
		t.Fatal("DNS rebinding not blocked")
	}
	// form posts (no JSON content type) are refused
	req, _ := http.NewRequest(http.MethodPost, "http://"+addr+"/api/toggle", strings.NewReader("on=true"))
	req.Header.Set("X-Shield-Token", a.Token)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r, _ := http.DefaultClient.Do(req)
	if r.StatusCode != http.StatusUnsupportedMediaType {
		t.Fatal("form post accepted")
	}
	if _, _, err := net.SplitHostPort(addr); err != nil || !strings.HasPrefix(addr, "127.0.0.1:") {
		t.Fatal("API must listen on loopback only")
	}
	if RunningInstanceURL() == "" {
		t.Fatal("single instance detection")
	}
}
