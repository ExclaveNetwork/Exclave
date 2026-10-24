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

package io.nekohasekai.sagernet.fmt.shadowquic;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class ShadowQUICBean extends AbstractBean {

    public String username;
    public String password;
    public String sni;
    public String alpn;
    public String congestionControl;
    public Boolean zeroRTT;
    public Boolean udpOverStream;
    public Boolean disableALPN;
    public Boolean useSunnyQUIC;
    public String certificate;
    public Long brutalUploadBandwidth;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (username == null) username = "";
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (congestionControl == null) congestionControl = "bbr";
        if (zeroRTT == null) zeroRTT = false;
        if (udpOverStream == null) udpOverStream = false;
        if (disableALPN == null) disableALPN = false;
        if (useSunnyQUIC == null) useSunnyQUIC = false;
        if (certificate == null) certificate = "";
        if (brutalUploadBandwidth == null) brutalUploadBandwidth = 0L;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        super.serialize(output);
        output.writeInt(3);
        output.writeString(username);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(alpn);
        output.writeString(congestionControl);
        output.writeBoolean(zeroRTT);
        output.writeBoolean(udpOverStream);
        output.writeBoolean(disableALPN);
        output.writeBoolean(useSunnyQUIC);
        output.writeString(certificate);
        output.writeLong(brutalUploadBandwidth);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        super.deserialize(input);
        int version = input.readInt();
        username = input.readString();
        password = input.readString();
        sni = input.readString();
        alpn = input.readString();
        congestionControl = input.readString();
        zeroRTT = input.readBoolean();
        udpOverStream = input.readBoolean();
        if (version >= 1) {
            disableALPN = input.readBoolean();
            useSunnyQUIC = input.readBoolean();
        }
        if (version >= 2) {
            certificate = input.readString();
        }
        if (version >= 3) {
            brutalUploadBandwidth = input.readLong();
        }
    }

    @Override
    public String network() {
        return "udp";
    }

    @NonNull
    @Override
    public ShadowQUICBean clone() {
        return KryoConverters.deserialize(new ShadowQUICBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowQUICBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public ShadowQUICBean newInstance() {
            return new ShadowQUICBean();
        }

        @Override
        public ShadowQUICBean[] newArray(int size) {
            return new ShadowQUICBean[size];
        }
    };


}
