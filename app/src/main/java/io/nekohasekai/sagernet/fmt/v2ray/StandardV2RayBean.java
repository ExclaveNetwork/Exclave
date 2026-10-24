/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean;
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import libsagernetcore.Libsagernetcore;

public abstract class StandardV2RayBean extends AbstractBean {

    public String uuid;
    public String encryption;
    public String type;
    public String host;
    public String path;
    public String headerType;
    public String mKcpSeed;
    public String quicSecurity;
    public String quicKey;
    public String security;
    public String sni;
    public String alpn;

    public String grpcServiceName;
    public Boolean grpcServiceNameCompat;
    public Boolean grpcMultiMode;
    public Integer maxEarlyData;
    public String earlyDataHeaderName;
    public String meekUrl;
    public String splithttpMode;
    public String splithttpExtra;

    public String certificates;
    public String pinnedPeerCertificateChainSha256;
    public String pinnedPeerCertificatePublicKeySha256;
    public String pinnedPeerCertificateSha256;
    public String mtlsCertificate;
    public String mtlsCertificatePrivateKey;
    public String utlsFingerprint;
    public Boolean echEnabled;
    public String echConfig;

    public Boolean wsUseBrowserForwarder;
    public Boolean shUseBrowserForwarder;
    public Boolean allowInsecure;
    public String packetEncoding;

    public String realityPublicKey;
    public String realityShortId;
    public String realityMldsa65Verify;
    public String realityFingerprint;
    public Boolean realityDisableX25519Mlkem768;

    public Long hy2DownMbps;
    public Long hy2UpMbps;
    public String hy2Password;

    public String mekyaKcpSeed;
    public String mekyaKcpHeaderType;
    public String mekyaUrl;

    public Boolean mux;
    public Integer muxConcurrency;
    public String muxPacketEncoding;

    public Boolean singMux;
    public String singMuxProtocol;
    public Integer singMuxMaxConnections;
    public Integer singMuxMinStreams;
    public Integer singMuxMaxStreams;
    public Boolean singMuxPadding;


    @Override
    public boolean canMapping() {
        return switch (type) {
            case "ws" -> !wsUseBrowserForwarder;
            case "splithttp" -> !shUseBrowserForwarder;
            default -> true;
        };
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (uuid == null) uuid = "";
        if (encryption == null) encryption = ""; // overridden in VMessBean and VLESSBean

        if (type == null) type = "tcp";

        if (host == null) host = "";
        if (path == null) path = "";
        if (headerType == null) headerType = "none";
        if (mKcpSeed == null) mKcpSeed = "";
        if (quicSecurity == null) quicSecurity = "none";
        if (quicKey == null) quicKey = "";
        if (meekUrl == null) meekUrl = "";
        if (splithttpMode == null) splithttpMode = "auto";
        if (splithttpExtra == null) splithttpExtra = "";

        if (security == null) security = "none";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";

        if (grpcServiceName == null) grpcServiceName = "";
        if (grpcServiceNameCompat == null) grpcServiceNameCompat = false;
        if (grpcMultiMode == null) grpcMultiMode = false;
        if (maxEarlyData == null) maxEarlyData = 0;
        if (wsUseBrowserForwarder == null) wsUseBrowserForwarder = false;
        if (shUseBrowserForwarder == null) shUseBrowserForwarder = false;
        if (certificates == null) certificates = "";
        if (pinnedPeerCertificateChainSha256 == null) pinnedPeerCertificateChainSha256 = "";
        if (pinnedPeerCertificatePublicKeySha256 == null) pinnedPeerCertificatePublicKeySha256 = "";
        if (pinnedPeerCertificateSha256 == null) pinnedPeerCertificateSha256 = "";
        if (mtlsCertificate == null) mtlsCertificate = "";
        if (mtlsCertificatePrivateKey == null) mtlsCertificatePrivateKey = "";
        if (earlyDataHeaderName == null) earlyDataHeaderName = "";
        if (allowInsecure == null) allowInsecure = false;
        if (packetEncoding == null) packetEncoding = "none";
        if (utlsFingerprint == null) utlsFingerprint = "";
        if (echEnabled == null) echEnabled = false;
        if (echConfig == null) echConfig = "";

        if (realityPublicKey == null) realityPublicKey = "";
        if (realityShortId == null) realityShortId = "";
        if (realityMldsa65Verify == null) realityMldsa65Verify = "";
        if (realityFingerprint == null) realityFingerprint = "chrome";
        if (realityDisableX25519Mlkem768 == null) realityDisableX25519Mlkem768 = false;

        if (hy2DownMbps == null) hy2DownMbps = 0L;
        if (hy2UpMbps == null) hy2UpMbps = 0L;
        if (hy2Password == null) hy2Password = "";

        if (mekyaKcpSeed == null) mekyaKcpSeed = "";
        if (mekyaKcpHeaderType == null) mekyaKcpHeaderType = "none";
        if (mekyaUrl == null) mekyaUrl = "";

        if (mux == null) mux = false;
        if (muxConcurrency == null) muxConcurrency = 8;
        if (muxPacketEncoding == null) muxPacketEncoding = "none";

        if (singMux == null) singMux = false;
        if (singMuxProtocol == null) singMuxProtocol = "h2mux";
        if (singMuxMaxConnections == null) singMuxMaxConnections = 0;
        if (singMuxMinStreams == null) singMuxMinStreams = 0;
        if (singMuxMaxStreams == null) singMuxMaxStreams = 0;
        if (singMuxPadding == null) singMuxPadding = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(36);
        super.serialize(output);

        output.writeString(uuid);
        output.writeString(encryption);
        output.writeString(type);

        switch (type) {
            case "tcp": {
                output.writeString(headerType);
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "kcp": {
                output.writeString(headerType);
                output.writeString(mKcpSeed);
                break;
            }
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(maxEarlyData);
                output.writeBoolean(wsUseBrowserForwarder);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "http": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "httpupgrade": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(maxEarlyData);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "splithttp": {
                output.writeString(host);
                output.writeString(path);
                output.writeBoolean(shUseBrowserForwarder);
                output.writeString(splithttpMode);
                output.writeString(splithttpExtra);
                break;
            }
            case "quic": {
                output.writeString(headerType);
                output.writeString(quicSecurity);
                output.writeString(quicKey);
                break;
            }
            case "grpc": {
                output.writeString(grpcServiceName);
                output.writeBoolean(grpcServiceNameCompat);
                output.writeBoolean(grpcMultiMode);
                break;
            }
            case "meek": {
                output.writeString(meekUrl);
                break;
            }
            case "hysteria2": {
                output.writeLong(hy2DownMbps);
                output.writeLong(hy2UpMbps);
                output.writeString(hy2Password);
                break;
            }
            case "mekya": {
                output.writeString(mekyaKcpHeaderType);
                output.writeString(mekyaKcpSeed);
                output.writeString(mekyaUrl);
                break;
            }
        }

        output.writeString(security);

        switch (security) {
            case "tls": {
                output.writeString(sni);
                output.writeString(alpn);
                output.writeString(certificates);
                output.writeString(pinnedPeerCertificateChainSha256);
                output.writeString(pinnedPeerCertificatePublicKeySha256);
                output.writeString(pinnedPeerCertificateSha256);
                output.writeBoolean(allowInsecure);
                output.writeString(utlsFingerprint);
                output.writeString(echConfig);
                output.writeString(mtlsCertificate);
                output.writeString(mtlsCertificatePrivateKey);
                break;
            }
            case "reality": {
                output.writeString(sni);
                output.writeString(realityPublicKey);
                output.writeString(realityShortId);
                output.writeString(realityFingerprint);
                output.writeBoolean(realityDisableX25519Mlkem768);
                break;
            }
        }

        if (this instanceof VMessBean) {
            output.writeInt(((VMessBean) this).alterId);
            output.writeBoolean(((VMessBean) this).experimentalAuthenticatedLength);
            output.writeBoolean(((VMessBean) this).experimentalNoTerminationSignal);
        }
        if (this instanceof VLESSBean) {
            output.writeString(((VLESSBean) this).flow);
        }

        output.writeString(packetEncoding);

        output.writeBoolean(mux);
        output.writeInt(muxConcurrency);
        output.writeString(muxPacketEncoding);

        if (this instanceof ShadowsocksBean) {
            output.writeBoolean(((ShadowsocksBean) this).singUoT);
        }
        if (this instanceof SOCKSBean) {
            output.writeBoolean(((SOCKSBean) this).singUoT);
        }
        output.writeBoolean(singMux);
        output.writeString(singMuxProtocol);
        output.writeInt(singMuxMaxConnections);
        output.writeInt(singMuxMinStreams);
        output.writeInt(singMuxMaxStreams);
        output.writeBoolean(singMuxPadding);

        output.writeBoolean(echEnabled);

        output.writeString(realityMldsa65Verify);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        uuid = input.readString();
        encryption = input.readString();
        type = input.readString();

        switch (type) {
            case "tcp": {
                headerType = input.readString();
                host = input.readString();
                path = input.readString();
                break;
            }
            case "kcp": {
                headerType = input.readString();
                mKcpSeed = input.readString();
                break;
            }
            case "ws": {
                host = input.readString();
                path = input.readString();
                maxEarlyData = input.readInt();
                wsUseBrowserForwarder = input.readBoolean();
                if (version >= 2) {
                    earlyDataHeaderName = input.readString();
                }
                break;
            }
            case "http": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "quic": {
                headerType = input.readString();
                quicSecurity = input.readString();
                quicKey = input.readString();
                if (version >= 16) {
                    break;
                }
            }
            case "grpc": {
                grpcServiceName = input.readString();
                if (version >= 8 && version <= 12) {
                    input.readString(); // grpcMode, removed
                }
                if (version >= 33) {
                    grpcServiceNameCompat = input.readBoolean();
                    grpcMultiMode = input.readBoolean();
                    if (version < 34) {
                        input.readString();
                    }
                }
                if (version >= 16) {
                    break;
                }
            }
            case "meek": {
                if (version >= 10) {
                    meekUrl = input.readString();
                }
                if (version >= 16) {
                    break;
                }
            }
            case "httpupgrade": {
                if (version >= 12) {
                    host = input.readString();
                    path = input.readString();
                }
                if (version >= 25) {
                    maxEarlyData = input.readInt();
                    earlyDataHeaderName = input.readString();
                }
                if (version >= 16) {
                    break;
                }
            }
            case "hysteria2": {
                if (version >= 14) {
                    if (version <= 28) {
                        hy2DownMbps = (long) input.readInt();
                    } else {
                        hy2DownMbps = input.readLong();
                    }
                    if (version <= 28) {
                        hy2UpMbps = (long) input.readInt();
                    } else {
                        hy2UpMbps = input.readLong();
                    }
                    if (version < 26) {
                        input.readString(); // hy2ObfsPassword, removed
                    }
                }
                if (version >= 15) {
                    hy2Password = input.readString();
                }
                break;
            }
            case "splithttp": {
                if (version >= 18) {
                    host = input.readString();
                    path = input.readString();
                }
                if (version >= 20) {
                    shUseBrowserForwarder = input.readBoolean();
                }
                if (version >= 23) {
                    splithttpMode = input.readString();
                }
                if (version >= 24) {
                    splithttpExtra = input.readString();
                }
                break;
            }
            case "mekya": {
                if (version >= 22) {
                    mekyaKcpHeaderType = input.readString();
                    mekyaKcpSeed = input.readString();
                    mekyaUrl = input.readString();
                }
                break;
            }
        }

        security = input.readString();
        switch (security) {
            case "tls": {
                sni = input.readString();
                alpn = input.readString();
                if (version >= 1) {
                    certificates = input.readString();
                    pinnedPeerCertificateChainSha256 = input.readString();
                }
                if (version >= 31) {
                    pinnedPeerCertificatePublicKeySha256 = input.readString();
                    pinnedPeerCertificateSha256 = input.readString();
                }
                if (version >= 3) {
                    allowInsecure = input.readBoolean();
                }
                if (version >= 9) {
                    utlsFingerprint = input.readString();
                }
                if (version >= 21) {
                    echConfig = input.readString();
                    if (version <= 34 && !echConfig.isEmpty()) {
                        echEnabled = true;
                    }
                    if (version <= 28) {
                        input.readString(); // echDohServer, removed
                    }
                }
                if (version >= 31) {
                    mtlsCertificate = input.readString();
                    mtlsCertificatePrivateKey = input.readString();
                }
                break;
            }
            case "xtls": { // removed, for compatibility
                if (version <= 8) {
                    security = "tls";
                    sni = input.readString();
                    alpn = input.readString();
                    input.readString(); // flow, removed
                }
                if (version >= 16) {
                    break;
                }
            }
            case "reality": {
                if (version >= 11) {
                    sni = input.readString();
                    realityPublicKey = input.readString();
                    realityShortId = input.readString();
                    if (version <= 25) {
                        input.readString(); // realitySpiderX, removed
                    }
                    realityFingerprint = input.readString();
                    if (version == 28 || version == 29) {
                        input.readString(); // echConfig, removed
                    }
                    if (version == 28) {
                        input.readString(); // echDohServer, removed
                    }
                    if (version >= 27) {
                        realityDisableX25519Mlkem768 = input.readBoolean();
                    }
                    if (version >= 27 && version <= 29) {
                        input.readBoolean(); // realityReenableChacha20Poly1305, removed
                    }
                }
                break;
            }
        }
        if (this instanceof VMessBean && version != 4 && version < 6) {
            ((VMessBean) this).alterId = input.readInt();
        }
        if (this instanceof VMessBean && version >= 4) {
            if (version >= 17) {
                ((VMessBean) this).alterId = input.readInt();
            }
            ((VMessBean) this).experimentalAuthenticatedLength = input.readBoolean();
            ((VMessBean) this).experimentalNoTerminationSignal = input.readBoolean();
        }
        if (this instanceof VLESSBean && version >= 11) {
            ((VLESSBean) this).flow = input.readString();
        }
        if (version >= 7 && version <= 15) {
            switch (input.readInt()) {
                case 0:
                    packetEncoding = "none";
                    break;
                case 1:
                    packetEncoding = "packet";
                    break;
                case 2:
                    packetEncoding = "xudp";
                    break;
            }
        }
        if (version >= 16) {
            packetEncoding = input.readString();
        }
        if (version >= 19) {
            mux = input.readBoolean();
            muxConcurrency = input.readInt();
            muxPacketEncoding = input.readString();
        }
        if (version >= 32) {
            if (this instanceof ShadowsocksBean) {
                ((ShadowsocksBean) this).singUoT = input.readBoolean();
            }
            if (this instanceof SOCKSBean) {
                ((SOCKSBean) this).singUoT = input.readBoolean();
            }
            singMux = input.readBoolean();
            singMuxProtocol = input.readString();
            singMuxMaxConnections = input.readInt();
            singMuxMinStreams = input.readInt();
            singMuxMaxStreams = input.readInt();
            singMuxPadding = input.readBoolean();
        }
        if (version >= 35) {
            echEnabled = input.readBoolean();
        }
        if (version >= 36) {
            realityMldsa65Verify = input.readString();
        }
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof StandardV2RayBean bean)) return;
        if (allowInsecure) {
            bean.allowInsecure = true;
        }
        if (bean.maxEarlyData == null || bean.maxEarlyData == 0 && maxEarlyData != 0) {
            bean.maxEarlyData = maxEarlyData;
        }
        if (bean.earlyDataHeaderName == null || bean.earlyDataHeaderName.isEmpty() && !earlyDataHeaderName.isEmpty()) {
            bean.earlyDataHeaderName = earlyDataHeaderName;
        }
        bean.wsUseBrowserForwarder = wsUseBrowserForwarder;
        bean.shUseBrowserForwarder = shUseBrowserForwarder;
        if (bean.certificates == null || bean.certificates.isEmpty() && !certificates.isEmpty()) {
            bean.certificates = certificates;
        }
        if (bean.pinnedPeerCertificateChainSha256 == null || bean.pinnedPeerCertificateChainSha256.isEmpty() &&
                !pinnedPeerCertificateChainSha256.isEmpty()) {
            bean.pinnedPeerCertificateChainSha256 = pinnedPeerCertificateChainSha256;
        }
        if (bean.pinnedPeerCertificatePublicKeySha256 == null || bean.pinnedPeerCertificatePublicKeySha256.isEmpty() &&
                !pinnedPeerCertificatePublicKeySha256.isEmpty()) {
            bean.pinnedPeerCertificatePublicKeySha256 = pinnedPeerCertificatePublicKeySha256;
        }
        if (bean.pinnedPeerCertificateSha256 == null || bean.pinnedPeerCertificateSha256.isEmpty() &&
                !pinnedPeerCertificateSha256.isEmpty()) {
            bean.pinnedPeerCertificateSha256 = pinnedPeerCertificateSha256;
        }
        if (bean instanceof VLESSBean || bean instanceof VMessBean || bean instanceof TrojanBean) {
            if (bean.echEnabled == null || !bean.echEnabled && !echEnabled) {
                bean.echEnabled = echEnabled;
            }
            if (bean.echConfig == null || bean.echConfig.isEmpty() && !echConfig.isEmpty()) {
                bean.echConfig = echConfig;
            }
        } else {
            bean.echEnabled = echEnabled;
            bean.echConfig = echConfig;
        }
        if (bean.packetEncoding == null) {
            bean.packetEncoding = packetEncoding;
        }
        bean.utlsFingerprint = utlsFingerprint;
        bean.realityFingerprint = realityFingerprint;
        bean.realityDisableX25519Mlkem768 = realityDisableX25519Mlkem768;
        bean.hy2DownMbps = hy2DownMbps;
        bean.hy2UpMbps = hy2UpMbps;
        bean.grpcServiceNameCompat = grpcServiceNameCompat;
        bean.mux = mux;
        bean.muxConcurrency = muxConcurrency;
        bean.muxPacketEncoding = muxPacketEncoding;
        bean.singMux = singMux;
        bean.singMuxProtocol = singMuxProtocol;
        bean.singMuxMaxConnections = singMuxMaxConnections;
        bean.singMuxMinStreams = singMuxMinStreams;
        bean.singMuxMaxStreams = singMuxMaxStreams;
        bean.singMuxPadding = singMuxPadding;
    }

    @Override
    public boolean isInsecure() {
        if (Libsagernetcore.isLoopbackIP(serverAddress) || serverAddress.equals("localhost")) {
            return false;
        }
        switch (security) {
            case "tls":
                if (echEnabled) {
                    // do not care if DNS server is reliable or not
                    return false;
                }
                if (!allowInsecure) {
                    return false;
                }
                if (!pinnedPeerCertificateChainSha256.isEmpty()) {
                    return false;
                }
                if (!pinnedPeerCertificatePublicKeySha256.isEmpty()) {
                    return false;
                }
                if (!pinnedPeerCertificateSha256.isEmpty()) {
                    return false;
                }
                break;
            case "reality":
                return false;
        }
        switch (type) {
            case "kcp":
                if (!mKcpSeed.isEmpty()) {
                    return false;
                }
                break;
            case "quic":
                if (!quicSecurity.equals("none")) {
                    return false;
                }
                break;
        }
        return true;
    }

}