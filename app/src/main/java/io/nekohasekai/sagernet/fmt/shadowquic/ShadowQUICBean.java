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

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (username == null) username = "";
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "h3";
        if (congestionControl == null) congestionControl = "bbr";
        if (zeroRTT == null) zeroRTT = false;
        if (udpOverStream == null) udpOverStream = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        super.serialize(output);
        output.writeInt(4);
        output.writeString(username);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(alpn);
        output.writeString(congestionControl);
        output.writeBoolean(zeroRTT);
        output.writeBoolean(udpOverStream);
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
        if (version <= 3 && congestionControl.equals("new-reno")) {
            congestionControl = "new_reno";
        }
        if (version <= 3 && congestionControl.equals("brutal")) {
            congestionControl = "bbr";
        }
        zeroRTT = input.readBoolean();
        udpOverStream = input.readBoolean();
        if (version >= 1 && version <= 3) {
            boolean disableALPN = input.readBoolean(); // disableALPN, removed
            if (disableALPN) {
                alpn = "";
            }
            if (!disableALPN && alpn.isEmpty()) {
                alpn = "h3";
            }
            input.readBoolean(); // useSunnyQUIC, removed
        }
        if (version >= 2 && version <= 3) {
            input.readString(); // certificate, removed
        }
        if (version == 3) {
            input.readLong(); // brutalUploadBandwidth, removed
        }
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

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof ShadowQUICBean bean)) return;
        bean.congestionControl = congestionControl;
    }

}
