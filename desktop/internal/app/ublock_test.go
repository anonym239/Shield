// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEveryLevelHasUblockLists(t *testing.T) {
	ids := map[string]bool{}
	for _, s := range Sources {
		ids[s.ID] = true
	}
	for lvl, ls := range levelLists {
		for _, id := range UblockLists {
			if !contains(ls, id) {
				t.Fatalf("%s misses %s", lvl, id)
			}
		}
		for _, id := range ls {
			if !ids[id] {
				t.Fatalf("%s lists unknown source %s", lvl, id)
			}
		}
	}
}

func TestOldSettingsGetUblockLists(t *testing.T) {
	dir := t.TempDir()
	old := `{"level":"STRICT","lists":["adguard_dns","hagezi_pro"]}`
	if err := os.WriteFile(filepath.Join(dir, "settings.json"), []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	s := loadSettings(dir).get()
	for _, id := range append([]string{"adguard_dns", "hagezi_pro"}, UblockLists...) {
		if !contains(s.Lists, id) {
			t.Fatalf("missing %s in %v", id, s.Lists)
		}
	}
	// a user who later removes a uBO list keeps that choice
	s.Lists = []string{"adguard_dns"}
	if got := s.MigrateLists(); len(got.Lists) != 1 {
		t.Fatalf("migration ran twice: %v", got.Lists)
	}
}
