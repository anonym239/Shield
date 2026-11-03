package shieldcore

import "testing"

// query for example.com A with an EDNS OPT record appended.
var sampleQuery = []byte{
	0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
	7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
	0x00, 0x01, 0x00, 0x01,
	0x00, 0x00, 0x29, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
}

func TestServfailKeepsOnlyQuestion(t *testing.T) {
	r := servfail(sampleQuery)
	if len(r) != 29 {
		t.Fatalf("want 29 bytes, got %d", len(r))
	}
	if r[0] != 0x12 || r[1] != 0x34 {
		t.Fatal("transaction id changed")
	}
	if r[2]&0x80 == 0 || r[3]&0x0f != 2 {
		t.Fatal("not a SERVFAIL response")
	}
	if r[11] != 0 {
		t.Fatal("additional count not cleared")
	}
}

func TestQuestionEndRejectsGarbage(t *testing.T) {
	if questionEnd([]byte{1, 2, 3}) != -1 {
		t.Fatal("short packet accepted")
	}
}

func TestParseAddrs(t *testing.T) {
	a, err := parseAddrs("10.2.0.2/32, fd00::2/128")
	if err != nil || len(a) != 2 {
		t.Fatalf("unexpected %v %v", a, err)
	}
	if _, err := parseAddrs(""); err == nil {
		t.Fatal("empty list accepted")
	}
}
