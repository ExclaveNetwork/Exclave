/******************************************************************************
 *                                                                            *
 * Copyright (C) 2024  dyhkwong                                               *
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


package io.nekohasekai.sagernet.fmt.juicity;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import libsagernetcore.Libsagernetcore;

public class JuicityBean extends AbstractBean {

    public String uuid;
    public String password;
    public String sni;
    public Boolean allowInsecure;
    public String certificates;
    public String pinnedPeerCertificateChainSha256; // was “pinnedCertChainSha256”
    public String pinnedPeerCertificatePublicKeySha256;
    public String pinnedPeerCertificateSha256;
    public String mtlsCertificate;
    public String mtlsCertificatePrivateKey;
    public Boolean echEnabled;
    public String echConfig;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (uuid == null) uuid = "";
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (allowInsecure == null) allowInsecure = false;
        if (certificates == null) certificates = "";
        if (pinnedPeerCertificateChainSha256 == null) pinnedPeerCertificateChainSha256 = "";
        if (pinnedPeerCertificatePublicKeySha256 == null) pinnedPeerCertificatePublicKeySha256 = "";
        if (pinnedPeerCertificateSha256 == null) pinnedPeerCertificateSha256 = "";
        if (mtlsCertificate == null) mtlsCertificate = "";
        if (mtlsCertificatePrivateKey == null) mtlsCertificatePrivateKey = "";
        if (echEnabled == null) echEnabled = false;
        if (echConfig == null) echConfig = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(5);
        super.serialize(output);
        output.writeString(uuid);
        output.writeString(password);
        output.writeString(sni);
        output.writeBoolean(allowInsecure);
        output.writeString(certificates);
        output.writeString(pinnedPeerCertificateChainSha256);
        output.writeString(pinnedPeerCertificatePublicKeySha256);
        output.writeString(pinnedPeerCertificateSha256);
        output.writeString(mtlsCertificate);
        output.writeString(mtlsCertificatePrivateKey);
        output.writeString(echConfig);

        output.writeBoolean(echEnabled);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        uuid = input.readString();
        password = input.readString();
        sni = input.readString();
        allowInsecure = input.readBoolean();
        if (version < 4) {
            input.readString(); // congestionControl, removed
        }
        if (version >= 3) {
            certificates = input.readString();
        }
        if (version >= 2) {
            pinnedPeerCertificateChainSha256 = input.readString();
            if (version == 2 && !pinnedPeerCertificateChainSha256.isEmpty()) {
                allowInsecure = true;
            }
        }
        if (version >= 3) {
            pinnedPeerCertificatePublicKeySha256 = input.readString();
            pinnedPeerCertificateSha256 = input.readString();
            mtlsCertificate = input.readString();
            mtlsCertificatePrivateKey = input.readString();
            echConfig = input.readString();
            if (version <= 4 && !echConfig.isEmpty()) {
                echEnabled = true;
            }
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
        if (!(other instanceof JuicityBean bean)) return;
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
        bean.echEnabled = echEnabled;
        bean.echConfig = echConfig;
    }

    @NotNull
    @Override
    public JuicityBean clone() {
        return KryoConverters.deserialize(new JuicityBean(), KryoConverters.serialize(this));
    }

    public static final Creator<JuicityBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public JuicityBean newInstance() {
            return new JuicityBean();
        }

        @Override
        public JuicityBean[] newArray(int size) {
            return new JuicityBean[size];
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
