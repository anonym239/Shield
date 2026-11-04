// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"sync"
	"time"
)

// HealPolicy limits automatic repairs so a permanent problem cannot cause a restart loop.
type HealPolicy struct {
	mu       sync.Mutex
	max      int
	attempts []time.Time
}

func NewHealPolicy(max int) *HealPolicy { return &HealPolicy{max: max} }

func (h *HealPolicy) Allow(now time.Time) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	keep := h.attempts[:0]
	for _, t := range h.attempts {
		if now.Sub(t) <= time.Hour {
			keep = append(keep, t)
		}
	}
	h.attempts = keep
	if len(h.attempts) >= h.max {
		return false
	}
	h.attempts = append(h.attempts, now)
	return true
}

func (h *HealPolicy) Reset() { h.mu.Lock(); h.attempts = nil; h.mu.Unlock() }

// HealActionFor: only lists failed -> UPDATE_LISTS, anything else -> RESTART.
func HealActionFor(failed []string) string {
	if len(failed) == 0 {
		return ""
	}
	if len(failed) == 1 && failed[0] == "LISTS" {
		return "UPDATE_LISTS"
	}
	return "RESTART"
}
