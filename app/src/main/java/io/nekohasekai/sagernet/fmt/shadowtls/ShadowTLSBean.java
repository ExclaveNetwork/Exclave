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

package io.nekohasekai.sagernet.fmt.shadowtls;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import libsagernetcore.Libsagernetcore;

public class ShadowTLSBean extends AbstractBean {

    public static final int PROTOCOL_VERSION_2 = 2;
    public static final int PROTOCOL_VERSION_3 = 3;

    public String sni;
    public String password;
    public String alpn;
    public Boolean allowInsecure;
    public String certificates;
    public Integer protocolVersion;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (sni == null) sni = "";
        if (password == null) password = "";
        if (alpn == null) alpn = "";
        if (allowInsecure == null) allowInsecure = false;
        if (certificates == null) certificates = "";
        if (protocolVersion == null) protocolVersion = PROTOCOL_VERSION_2;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(sni);
        output.writeString(password);
        output.writeString(alpn);
        output.writeBoolean(allowInsecure);
        output.writeString(certificates);
        output.writeInt(protocolVersion);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        sni = input.readString();
        password = input.readString();
        alpn = input.readString();
        if (version == 0) {
            if (input.readBoolean()) {
                protocolVersion = PROTOCOL_VERSION_3;
            } else {
                protocolVersion = PROTOCOL_VERSION_2;
            }
        }
        if (version >= 1) {
            allowInsecure = input.readBoolean();
            certificates = input.readString();
            protocolVersion = input.readInt();
        }
    }

    @Override
    public String network() {
        return "tcp";
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof ShadowTLSBean bean)) return;
        if (allowInsecure) {
            bean.allowInsecure = true;
        }
        bean.certificates = certificates;
    }

    @NonNull
    @Override
    public ShadowTLSBean clone() {
        return KryoConverters.deserialize(new ShadowTLSBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowTLSBean> CREATOR = new CREATOR<ShadowTLSBean>() {
        @NonNull
        @Override
        public ShadowTLSBean newInstance() {
            return new ShadowTLSBean();
        }

        @Override
        public ShadowTLSBean[] newArray(int size) {
            return new ShadowTLSBean[size];
        }
    };

    @Override
    public boolean isInsecure() {
        if (Libsagernetcore.isLoopbackIP(serverAddress) || serverAddress.equals("localhost")) {
            return false;
        }
        if (!allowInsecure) {
            return false;
        }
        return true;
    }

}
