package app

import "testing"

func TestExtractJSONArray(t *testing.T) {
	cases := map[string]string{
		"Get-NetAdapter : warning text\r\n[{\"a\":1}]\r\n": `[{"a":1}]`,
		"{\"a\":1}":              `[{"a":1}]`,
		"":                       "[]",
		"WARNUNG: irgendwas\r\n": "[]",
		"\r\n  [ ]  \r\n":        "[ ]",
	}
	for in, want := range cases {
		if got := extractJSONArray(in); got != want {
			t.Errorf("%q -> %q, want %q", in, got, want)
		}
	}
}
