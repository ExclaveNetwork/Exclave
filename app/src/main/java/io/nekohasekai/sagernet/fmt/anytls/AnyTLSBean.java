/******************************************************************************
 *                                                                            *
 * Copyright (C) 2025  dyhkwong                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.anytls;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import libsagernetcore.Libsagernetcore;

public class AnyTLSBean extends AbstractBean {

    public String password;
    public Integer idleSessionCheckInterval;
    public Integer idleSessionTimeout;
    public Integer minIdleSession;
    public String security;
    public String sni;
    public String alpn;
    public String certificates;
    public String pinnedPeerCertificateChainSha256;
    public String pinnedPeerCertificatePublicKeySha256;
    public String pinnedPeerCertificateSha256;
    public Boolean allowInsecure;
    public String utlsFingerprint;
    public Boolean echEnabled;
    public String echConfig;
    public String realityPublicKey;
    public String realityShortId;
    public String realityFingerprint;
    public Boolean realityDisableX25519Mlkem768;
    public String mtlsCertificate;
    public String mtlsCertificatePrivateKey;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (password == null) password = "";
        if (idleSessionCheckInterval == null) idleSessionCheckInterval = 30;
        if (idleSessionTimeout == null) idleSessionTimeout = 30;
        if (minIdleSession == null) minIdleSession = 0;
        if (security == null) security = "tls";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (certificates == null) certificates = "";
        if (pinnedPeerCertificateChainSha256 == null) pinnedPeerCertificateChainSha256 = "";
        if (pinnedPeerCertificatePublicKeySha256 == null) pinnedPeerCertificatePublicKeySha256 = "";
        if (pinnedPeerCertificateSha256 == null) pinnedPeerCertificateSha256 = "";
        if (allowInsecure == null) allowInsecure = false;
        if (utlsFingerprint == null) utlsFingerprint = "";
        if (echEnabled == null) echEnabled = false;
        if (echConfig == null) echConfig = "";
        if (realityPublicKey == null) realityPublicKey = "";
        if (realityShortId == null) realityShortId = "";
        if (realityFingerprint == null) realityFingerprint = "chrome";
        if (realityDisableX25519Mlkem768 == null) realityDisableX25519Mlkem768 = false;
        if (mtlsCertificate == null) mtlsCertificate = "";
        if (mtlsCertificatePrivateKey == null) mtlsCertificatePrivateKey = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        super.serialize(output);
        output.writeString(password);
        output.writeInt(idleSessionCheckInterval);
        output.writeInt(idleSessionTimeout);
        output.writeInt(minIdleSession);
        output.writeString(security);
        output.writeString(sni);
        output.writeString(alpn);
        output.writeString(certificates);
        output.writeString(pinnedPeerCertificateChainSha256);
        output.writeString(pinnedPeerCertificatePublicKeySha256);
        output.writeString(pinnedPeerCertificateSha256);
        output.writeBoolean(allowInsecure);
        output.writeString(utlsFingerprint);
        output.writeString(echConfig);
        output.writeString(realityPublicKey);
        output.writeString(realityShortId);
        output.writeString(realityFingerprint);
        output.writeBoolean(realityDisableX25519Mlkem768);
        output.writeString(mtlsCertificate);
        output.writeString(mtlsCertificatePrivateKey);

        output.writeBoolean(echEnabled);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        if (version >= 2) {
            idleSessionCheckInterval = input.readInt();
            idleSessionTimeout = input.readInt();
            minIdleSession = input.readInt();
        }
        security = input.readString();
        sni = input.readString();
        alpn = input.readString();
        certificates = input.readString();
        pinnedPeerCertificateChainSha256 = input.readString();
        if (version >= 4) {
            pinnedPeerCertificatePublicKeySha256 = input.readString();
            pinnedPeerCertificateSha256 = input.readString();
        }
        allowInsecure = input.readBoolean();
        utlsFingerprint = input.readString();
        echConfig = input.readString();
        if (version <= 4 && !echConfig.isEmpty()) {
            echEnabled = true;
        }
        if (version <= 2) {
            input.readString(); // echDohServer, removed
        }
        realityPublicKey = input.readString();
        realityShortId = input.readString();
        realityFingerprint = input.readString();
        if (version >= 1) {
            realityDisableX25519Mlkem768 = input.readBoolean();
        }
        if (version <= 2) {
            input.readBoolean(); // realityReenableChacha20Poly1305, removed
        }
        if (version >= 4) {
            mtlsCertificate = input.readString();
            mtlsCertificatePrivateKey = input.readString();
        }
        if (version >= 5) {
            echEnabled = input.readBoolean();
        }
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof AnyTLSBean bean)) return;
        if (allowInsecure) {
            bean.allowInsecure = true;
        }
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
        bean.utlsFingerprint = utlsFingerprint;
        bean.echEnabled = echEnabled;
        bean.echConfig = echConfig;
        bean.realityFingerprint = realityFingerprint;
        bean.realityDisableX25519Mlkem768 = realityDisableX25519Mlkem768;
    }

    @NotNull
    @Override
    public AnyTLSBean clone() {
        return KryoConverters.deserialize(new AnyTLSBean(), KryoConverters.serialize(this));
    }

    public static final Creator<AnyTLSBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public AnyTLSBean newInstance() {
            return new AnyTLSBean();
        }

        @Override
        public AnyTLSBean[] newArray(int size) {
            return new AnyTLSBean[size];
        }
    };

    @Override
    public boolean isInsecure() {
        if (Libsagernetcore.isLoopbackIP(serverAddress) || serverAddress.equals("localhost")) {
            return false;
        }
        switch(security) {
            case "reality":
                return false;
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
        }
        return true;
    }

}
