/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.fmt.mieru;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.ktx.NetsKt;
import libsagernetcore.Libsagernetcore;

public class MieruBean extends AbstractBean {

    public static final int PROTOCOL_TCP = 0;
    public static final int PROTOCOL_UDP = 1;

    public static final int MULTIPLEXING_DEFAULT = 0;
    public static final int MULTIPLEXING_OFF = 1;
    public static final int MULTIPLEXING_LOW = 2;
    public static final int MULTIPLEXING_MIDDLE = 3;
    public static final int MULTIPLEXING_HIGH = 4;

    public static final int HANDSHAKE_DEFAULT = 2;
    public static final int HANDSHAKE_STANDARD = 0;
    public static final int HANDSHAKE_NO_WAIT = 1;

    public Integer protocol;
    public String username;
    public String password;
    public Integer mtu;
    public Integer multiplexingLevel;
    public Integer handshakeMode;
    public String portRange;
    public String trafficPattern;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocol == null) protocol = PROTOCOL_TCP;
        if (username == null) username = "";
        if (password == null) password = "";
        if (mtu == null) mtu = 1400;
        if (multiplexingLevel == null) multiplexingLevel = MULTIPLEXING_DEFAULT;
        if (handshakeMode == null) handshakeMode = HANDSHAKE_DEFAULT;
        if (portRange == null) portRange = "";
        if (trafficPattern == null) trafficPattern = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4);
        super.serialize(output);
        output.writeInt(protocol);
        output.writeString(username);
        output.writeString(password);
        if (protocol == PROTOCOL_UDP) {
            output.writeInt(mtu);
        }
        output.writeInt(multiplexingLevel);
        output.writeInt(handshakeMode);
        output.writeString(portRange);
        output.writeString(trafficPattern);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        protocol = input.readInt();
        username = input.readString();
        password = input.readString();
        if (protocol == PROTOCOL_UDP) {
            mtu = input.readInt();
        }
        if (version >= 1) {
            multiplexingLevel = input.readInt();
        }
        if (version >= 2) {
            handshakeMode = input.readInt();
        }
        if (version >= 3) {
            portRange = input.readString();
        }
        if (version >= 4) {
            trafficPattern = input.readString();
        }
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof MieruBean bean)) return;
        bean.multiplexingLevel = multiplexingLevel;
        bean.handshakeMode = handshakeMode;
        bean.mtu = mtu;
        bean.trafficPattern = trafficPattern;
    }

    @Override
    public String displayAddress() {
        if (portRange.isEmpty()) {
            return super.displayAddress();
        }
        if (Libsagernetcore.isIPv6(serverAddress)) {
            return "[" + serverAddress + "]:" + String.join(",", NetsKt.listByLineOrComma(portRange));
        } else {
            return NetsKt.wrapIDN(serverAddress) + ":" + String.join(",", NetsKt.listByLineOrComma(portRange));
        }
    }

    @Override
    public String network() {
        if (protocol == PROTOCOL_UDP) {
            return "udp";
        }
        return "tcp";
    }

    @NotNull
    @Override
    public MieruBean clone() {
        return KryoConverters.deserialize(new MieruBean(), KryoConverters.serialize(this));
    }

    public static final Creator<MieruBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public MieruBean newInstance() {
            return new MieruBean();
        }

        @Override
        public MieruBean[] newArray(int size) {
            return new MieruBean[size];
        }
    };
}
