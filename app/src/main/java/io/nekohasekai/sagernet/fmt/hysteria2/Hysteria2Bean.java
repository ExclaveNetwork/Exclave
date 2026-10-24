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

package io.nekohasekai.sagernet.fmt.hysteria2;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.ktx.NetsKt;
import libsagernetcore.Libsagernetcore;

public class Hysteria2Bean extends AbstractBean {

    public String auth;
    public String obfs;
    public String sni;
    public String pinnedPeerCertificateSha256;
    public String pinnedPeerCertificatePublicKeySha256;
    public String pinnedPeerCertificateChainSha256;
    public String certificates;
    public Boolean allowInsecure;
    public Long uploadMbps;
    public Long downloadMbps;
    public String serverPorts;
    public Long hopInterval;
    public Long hopIntervalMin;
    public Long hopIntervalMax;
    public Boolean echEnabled;
    public String echConfig;
    public String mtlsCertificate;
    public String mtlsCertificatePrivateKey;
    public String congestionControl;
    public String bbrProfile;
    public Boolean omitMaxDatagramFrameSize;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (auth == null) auth = "";
        if (obfs == null) obfs = "";
        if (sni == null) sni = "";
        if (pinnedPeerCertificateSha256 == null) pinnedPeerCertificateSha256 = "";
        if (pinnedPeerCertificatePublicKeySha256 == null) pinnedPeerCertificatePublicKeySha256 = "";
        if (pinnedPeerCertificateChainSha256 == null) pinnedPeerCertificateChainSha256 = "";
        if (certificates == null) certificates = "";
        if (allowInsecure == null) allowInsecure = false;
        if (uploadMbps == null) uploadMbps = 0L;
        if (downloadMbps == null) downloadMbps = 0L;
        if (serverPorts == null) serverPorts = "1080";
        if (hopInterval == null) hopInterval = 0L;
        if (hopIntervalMin == null) hopIntervalMin = 0L;
        if (hopIntervalMax == null) hopIntervalMax = 0L;
        if (echEnabled == null) echEnabled = false;
        if (echConfig == null) echConfig = "";
        if (mtlsCertificate == null) mtlsCertificate = "";
        if (mtlsCertificatePrivateKey == null) mtlsCertificatePrivateKey = "";
        if (congestionControl == null) congestionControl = "bbr";
        if (bbrProfile == null) bbrProfile = "standard";
        if (omitMaxDatagramFrameSize == null) omitMaxDatagramFrameSize = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(8);
        super.serialize(output);
        output.writeString(auth);
        output.writeString(obfs);
        output.writeString(sni);
        output.writeString(pinnedPeerCertificateSha256);
        output.writeString(pinnedPeerCertificatePublicKeySha256);
        output.writeString(pinnedPeerCertificateChainSha256);
        output.writeString(certificates);
        output.writeBoolean(allowInsecure);
        output.writeLong(uploadMbps);
        output.writeLong(downloadMbps);
        output.writeString(serverPorts);
        output.writeLong(hopInterval);
        output.writeString(echConfig);
        output.writeString(mtlsCertificate);
        output.writeString(mtlsCertificatePrivateKey);

        output.writeBoolean(echEnabled);
        output.writeLong(hopIntervalMin);
        output.writeLong(hopIntervalMax);
        output.writeString(congestionControl);
        output.writeString(bbrProfile);
        output.writeBoolean(omitMaxDatagramFrameSize);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        auth = input.readString();
        obfs = input.readString();
        sni = input.readString();
        pinnedPeerCertificateSha256 = input.readString();
        if (version >= 4) {
            pinnedPeerCertificatePublicKeySha256 = input.readString();
            pinnedPeerCertificateChainSha256 = input.readString();
        }
        certificates = input.readString();
        allowInsecure = input.readBoolean();
        if (version <= 2) {
            uploadMbps = (long) input.readInt();
        } else {
            uploadMbps = input.readLong();
        }
        if (version <= 2) {
            downloadMbps = (long) input.readInt();
        } else {
            downloadMbps = input.readLong();
        }
        if (version < 5) {
            input.readBoolean(); // disableMtuDiscovery, removed
            input.readInt(); // initStreamReceiveWindow, removed
            input.readInt(); // maxStreamReceiveWindow, removed
            input.readInt(); // initConnReceiveWindow, removed
            input.readInt(); // maxConnReceiveWindow, removed
        }
        if (version < 2) {
            serverPorts = serverPort.toString();
        }
        if (version >= 2) {
            serverPorts = input.readString();
            if (version == 2) {
                hopInterval = (long) input.readInt();
            } else {
                hopInterval = input.readLong();
            }
        }
        if (version >= 4) {
            echConfig = input.readString();
            if (version <= 5 && !echConfig.isEmpty()) {
                echEnabled = true;
            }
            mtlsCertificate = input.readString();
            mtlsCertificatePrivateKey = input.readString();
        }
        if (version >= 6) {
            echEnabled = input.readBoolean();
        }
        if (version >= 7) {
            hopIntervalMin = input.readLong();
            hopIntervalMax = input.readLong();
            congestionControl = input.readString();
            bbrProfile = input.readString();
        }
        if (version >= 8) {
            omitMaxDatagramFrameSize = input.readBoolean();
        }
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof Hysteria2Bean bean)) return;
        if (allowInsecure) {
            bean.allowInsecure = true;
        }
        bean.uploadMbps = uploadMbps;
        bean.downloadMbps = downloadMbps;
        if (bean.pinnedPeerCertificateSha256 == null || bean.pinnedPeerCertificateSha256.isEmpty() && !pinnedPeerCertificateSha256.isEmpty()) {
            bean.pinnedPeerCertificateSha256 = pinnedPeerCertificateSha256;
        }
        if (bean.pinnedPeerCertificatePublicKeySha256 == null || bean.pinnedPeerCertificatePublicKeySha256.isEmpty() &&
                !pinnedPeerCertificatePublicKeySha256.isEmpty()) {
            bean.pinnedPeerCertificatePublicKeySha256 = pinnedPeerCertificatePublicKeySha256;
        }
        if (bean.pinnedPeerCertificateChainSha256 == null || bean.pinnedPeerCertificateChainSha256.isEmpty() &&
                !pinnedPeerCertificateChainSha256.isEmpty()) {
            bean.pinnedPeerCertificateChainSha256 = pinnedPeerCertificateChainSha256;
        }
        if (bean.certificates == null || bean.certificates.isEmpty() && !certificates.isEmpty()) {
            bean.certificates = certificates;
        }
        bean.echEnabled = echEnabled;
        bean.echConfig = echConfig;
        bean.hopInterval = hopInterval;
        bean.hopIntervalMin = hopIntervalMin;
        bean.hopIntervalMax = hopIntervalMax;
        bean.congestionControl = congestionControl;
        bean.bbrProfile = bbrProfile;
        bean.omitMaxDatagramFrameSize = omitMaxDatagramFrameSize;
    }

    @Override
    public String displayAddress() {
        if (Libsagernetcore.isIPv6(serverAddress)) {
            return "[" + serverAddress + "]:" + serverPorts;
        } else {
            return NetsKt.wrapIDN(serverAddress) + ":" + serverPorts;
        }
    }

    @Override
    public String network() {
        return "udp";
    }

    @NotNull
    @Override
    public Hysteria2Bean clone() {
        return KryoConverters.deserialize(new Hysteria2Bean(), KryoConverters.serialize(this));
    }

    public static final Creator<Hysteria2Bean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public Hysteria2Bean newInstance() {
            return new Hysteria2Bean();
        }

        @Override
        public Hysteria2Bean[] newArray(int size) {
            return new Hysteria2Bean[size];
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
        if (!obfs.isEmpty()) {
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
