package app

import (
	"strings"
	"testing"
)

const testConf = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.2.0.2/32
DNS = 10.2.0.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.org:51820
PersistentKeepalive = 25
`

func TestWgConfig(t *testing.T) {
	c, err := ParseWgConfig(testConf)
	if err != nil {
		t.Fatal(err)
	}
	u, err := c.UAPI(func(string) (string, error) { return "203.0.113.5", nil })
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"private_key=c809f3e5", "endpoint=203.0.113.5:51820", "allowed_ip=0.0.0.0/0", "allowed_ip=::/0", "persistent_keepalive_interval=25"} {
		if !strings.Contains(u, want) {
			t.Errorf("missing %s", want)
		}
	}
	if _, err := ParseWgConfig(strings.Replace(testConf, "Address = 10.2.0.2/32", "", 1)); err == nil {
		t.Fatal("missing address accepted")
	}
	if _, err := ParseWgConfig(strings.Replace(testConf, "xTIBA5", "zz", 1)); err == nil {
		t.Fatal("bad key accepted")
	}
}
