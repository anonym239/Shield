package app

import (
	"testing"
	"time"
)

func TestHeal(t *testing.T) {
	if HealActionFor(nil) != "" || HealActionFor([]string{"LISTS"}) != "UPDATE_LISTS" || HealActionFor([]string{"UPSTREAM"}) != "RESTART" {
		t.Fatal("actions")
	}
	h := NewHealPolicy(3)
	now := time.Unix(0, 0)
	for i := 0; i < 3; i++ {
		if !h.Allow(now) {
			t.Fatal("should allow")
		}
	}
	if h.Allow(now) {
		t.Fatal("loop protection")
	}
	if !h.Allow(now.Add(61 * time.Minute)) {
		t.Fatal("window")
	}
}
