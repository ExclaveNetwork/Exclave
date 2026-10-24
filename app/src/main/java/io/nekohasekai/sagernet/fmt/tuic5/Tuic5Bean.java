/******************************************************************************
 *                                                                            *
 * Copyright (C) 2023  dyhkwong                                               *
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

package io.nekohasekai.sagernet.fmt.tuic5;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import libsagernetcore.Libsagernetcore;

public class Tuic5Bean extends AbstractBean {

    public String uuid;
    public String password;
    public String certificates;
    public String udpRelayMode;
    public String congestionControl;
    public String alpn;
    public Boolean disableSNI;
    public Boolean zeroRTTHandshake;
    public String sni;
    public Boolean allowInsecure;
    public Boolean echEnabled;
    public String echConfig;
    public String pinnedPeerCertificateChainSha256;
    public String pinnedPeerCertificatePublicKeySha256;
    public String pinnedPeerCertificateSha256;
    public String mtlsCertificate;
    public String mtlsCertificatePrivateKey;
    public Boolean singUDPOverStream;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (uuid == null) uuid = "";
        if (password == null) password = "";
        if (certificates == null) certificates = "";
        if (udpRelayMode == null) udpRelayMode = "native";
        if (congestionControl == null) congestionControl = "cubic";
        if (alpn == null) alpn = "";
        if (disableSNI == null) disableSNI = false;
        if (zeroRTTHandshake == null) zeroRTTHandshake = false;
        if (sni == null) sni = "";
        if (allowInsecure == null) allowInsecure = false;
        if (echEnabled == null) echEnabled = false;
        if (echConfig == null) echConfig = "";
        if (pinnedPeerCertificateChainSha256 == null) pinnedPeerCertificateChainSha256 = "";
        if (pinnedPeerCertificatePublicKeySha256 == null) pinnedPeerCertificatePublicKeySha256 = "";
        if (pinnedPeerCertificateSha256 == null) pinnedPeerCertificateSha256 = "";
        if (mtlsCertificate == null) mtlsCertificate = "";
        if (mtlsCertificatePrivateKey == null) mtlsCertificatePrivateKey = "";
        if (singUDPOverStream == null) singUDPOverStream = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        super.serialize(output);
        output.writeString(password);
        output.writeString(certificates);
        output.writeString(udpRelayMode);
        output.writeString(congestionControl);
        output.writeString(alpn);
        output.writeBoolean(disableSNI);
        output.writeBoolean(zeroRTTHandshake);
        output.writeString(sni);
        output.writeString(uuid);
        output.writeBoolean(allowInsecure);
        output.writeString(echConfig);
        output.writeString(pinnedPeerCertificateChainSha256);
        output.writeString(pinnedPeerCertificatePublicKeySha256);
        output.writeString(pinnedPeerCertificateSha256);
        output.writeString(mtlsCertificate);
        output.writeString(mtlsCertificatePrivateKey);
        output.writeBoolean(singUDPOverStream);

        output.writeBoolean(echEnabled);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        certificates = input.readString();
        udpRelayMode = input.readString();
        congestionControl = input.readString();
        alpn = input.readString();
        disableSNI = input.readBoolean();
        zeroRTTHandshake = input.readBoolean();
        if (version < 4) {
            input.readInt(); // mtu, removed
        }
        sni = input.readString();
        uuid = input.readString();
        if (version >= 1) {
            allowInsecure = input.readBoolean();
        }
        if (version >= 2) {
            echConfig = input.readString();
            if (version <= 4 && !echConfig.isEmpty()) {
                echEnabled = true;
            }
            pinnedPeerCertificateChainSha256 = input.readString();
            pinnedPeerCertificatePublicKeySha256 = input.readString();
            pinnedPeerCertificateSha256 = input.readString();
            mtlsCertificate = input.readString();
            mtlsCertificatePrivateKey = input.readString();
        }
        if (version >= 3) {
            singUDPOverStream = input.readBoolean();
        }
        if (version >= 5) {
            echEnabled = input.readBoolean();
        }
    }

    @Override
    public String network() {
        return "udp";
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof Tuic5Bean bean)) return;
        if (bean.certificates == null || bean.certificates.isEmpty() && !certificates.isEmpty()) {
            bean.certificates = certificates;
        }
        bean.zeroRTTHandshake = zeroRTTHandshake;
        if (allowInsecure) {
            bean.allowInsecure = true;
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
        bean.echEnabled = echEnabled;
        bean.echConfig = echConfig;
        bean.singUDPOverStream = singUDPOverStream;
    }

    @NotNull
    @Override
    public Tuic5Bean clone() {
        return KryoConverters.deserialize(new Tuic5Bean(), KryoConverters.serialize(this));
    }

    public static final Creator<Tuic5Bean> CREATOR = new CREATOR<Tuic5Bean>() {
        @NonNull
        @Override
        public Tuic5Bean newInstance() {
            return new Tuic5Bean();
        }

        @Override
        public Tuic5Bean[] newArray(int size) {
            return new Tuic5Bean[size];
        }
    };

    @Override
    public boolean isInsecure() {
        if (Libsagernetcore.isLoopbackIP(serverAddress) || serverAddress.equals("localhost")) {
            return false;
        }
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
        return true;
    }

}
