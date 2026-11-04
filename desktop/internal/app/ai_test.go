package app

import (
	"strings"
	"testing"
)

func TestAI(t *testing.T) {
	body := `{"candidates":[{"content":{"parts":[{"text":"{\"status\":\"problem\",\"summary\":\"VPN getrennt. 100 % sicher.\",\"actions\":[\"RESTART_TUNNEL\",\"DISABLE_FIREWALL\"]}"}]}}]}`
	v, err := ParseAIResponse([]byte(body))
	if err != nil {
		t.Fatal(err)
	}
	if v.Status != "PROBLEM" || len(v.Actions) != 1 || v.Actions[0] != "RESTART_TUNNEL" || strings.Contains(v.Summary, "100") {
		t.Fatalf("%+v", v)
	}
	if _, err := ParseAIResponse([]byte(`{"error":{"message":"API key not valid"}}`)); err == nil {
		t.Fatal("api error ignored")
	}
	s := AISanitize("DoH dns.quad9.net 9.9.9.9 key AIzaSyA1234567890abcdefghijklmnopqrstuv")
	if strings.Contains(s, "quad9") || strings.Contains(s, "9.9.9.9") || strings.Contains(s, "AIza") {
		t.Fatal(s)
	}
}
