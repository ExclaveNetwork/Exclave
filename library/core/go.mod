module libexclavecore

go 1.26.0

require github.com/exclavenetwork/libexclavecore v0.0.0-20260822075057-026e8fb36c8e

require (
	filippo.io/age v1.3.1 // indirect
	filippo.io/hpke v0.4.0 // indirect
	filippo.io/mldsa v0.0.0-20260711112038-ff3f469cee29 // indirect
	github.com/adrg/xdg v0.5.3 // indirect
	github.com/aead/chacha20 v0.0.0-20180709150244-8b13a72661da // indirect
	github.com/andybalholm/brotli v1.2.2 // indirect
	github.com/anytls/sing-anytls v0.0.13 // indirect
	github.com/apernet/quic-go v0.61.1-0.20260806010916-184d081eef3e // indirect
	github.com/dgryski/go-camellia v0.0.0-20191119043421-69a8a13fb23d // indirect
	github.com/dgryski/go-metro v0.0.0-20250106013310-edb8663e5e33 // indirect
	github.com/enfein/mieru/v3 v3.36.0 // indirect
	github.com/exclavenetwork/exclave-core/v5 v5.50.1-0.20260822074201-94b72818c136 // indirect
	github.com/exclavenetwork/go-stun v0.1.7-0.20260811120819-d09f4628f065 // indirect
	github.com/exclavenetwork/hysteria/core/v2 v2.12.1-1 // indirect
	github.com/exclavenetwork/hysteria/extras/v2 v2.12.1-1 // indirect
	github.com/exclavenetwork/sing-juicity v0.3.0-beta.1 // indirect
	github.com/exclavenetwork/sing-shadowquic v0.0.0-20260801175020-65c7acc31f93 // indirect
	github.com/gofrs/uuid/v5 v5.5.1 // indirect
	github.com/golang-collections/go-datastructures v0.0.0-20150211160725-59788d5eb259 // indirect
	github.com/golang/protobuf v1.5.4 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/gorilla/websocket v1.5.3 // indirect
	github.com/hashicorp/yamux v0.1.2 // indirect
	github.com/klauspost/compress v1.19.2 // indirect
	github.com/klauspost/cpuid/v2 v2.4.0 // indirect
	github.com/lunixbochs/struc v0.0.0-20241101090106-8d528fa2c543 // indirect
	github.com/metacubex/cpu v0.1.1 // indirect
	github.com/metacubex/hkdf v0.1.0 // indirect
	github.com/metacubex/hpke v0.1.0 // indirect
	github.com/metacubex/jls-quic-go v0.0.0-20260727080412-732f2fc9a34d // indirect
	github.com/metacubex/jls-tls v0.0.0-20260723084315-67adc0e2f796 // indirect
	github.com/metacubex/mlkem v0.1.0 // indirect
	github.com/metacubex/randv2 v0.2.1-0.20260726125100-81aa96a9b1a5 // indirect
	github.com/metacubex/utls v1.8.7 // indirect
	github.com/miekg/dns v1.1.73 // indirect
	github.com/pires/go-proxyproto v0.15.0 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/quic-go/quic-go v0.61.0 // indirect
	github.com/refraction-networking/utls v1.8.3-0.20260802151714-23b1dac19c06 // indirect
	github.com/riobard/go-bloom v0.0.0-20200614022211-cdc8013cb5b3 // indirect
	github.com/sagernet/quic-go v0.61.0-sing-box-mod.4 // indirect
	github.com/sagernet/sing v0.9.0-beta.3 // indirect
	github.com/sagernet/sing-mux v0.3.5 // indirect
	github.com/sagernet/sing-quic v0.7.0-beta.2 // indirect
	github.com/sagernet/sing-shadowsocks v0.2.9 // indirect
	github.com/sagernet/sing-shadowsocks2 v0.2.2 // indirect
	github.com/sagernet/sing-snell v0.0.0-20260727093646-7cb813e07b73 // indirect
	github.com/sagernet/smux v1.5.50-sing-box-mod.1 // indirect
	github.com/seiflotfy/cuckoofilter v0.0.0-20240715131351-a2f2c23f1771 // indirect
	github.com/v2fly/BrowserBridge v0.0.0-20210430233438-0570fc1d7d08 // indirect
	github.com/v2fly/ss-bloomring v0.0.0-20210312155135-28617310f63e // indirect
	github.com/v2fly/struc v0.0.0-20241227015403-8e8fa1badfd6 // indirect
	github.com/xtaci/smux v1.5.57 // indirect
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba // indirect
	golang.org/x/crypto v0.55.0 // indirect
	golang.org/x/exp v0.0.0-20260820122028-d6e0b57b1a69 // indirect
	golang.org/x/mobile v0.0.0-20260821190718-4776eadac327 // indirect
	golang.org/x/net v0.58.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard v0.0.0-20260522210424-ecfc5a8d5446 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20260819154853-08b0e4226688 // indirect
	google.golang.org/grpc v1.83.1 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
	gvisor.dev/gvisor v0.0.0-20250503011706-39ed1f5ac29c // indirect
	lukechampine.com/blake3 v1.4.1 // indirect
)

// workaround https://github.com/google/gvisor/commit/868dfbce4fd59f03145e2bc5ac0b585917c371fa
replace gvisor.dev/gvisor => gvisor.dev/gvisor v0.0.0-20250429202743-3a608a52255d
