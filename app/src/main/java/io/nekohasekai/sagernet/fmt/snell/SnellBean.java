/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Snell support for Exclave                              *
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

package io.nekohasekai.sagernet.fmt.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.fmt.tuic5.Tuic5Bean;

public class SnellBean extends AbstractBean {

    public static final int VERSION_4 = 4;
    public static final int VERSION_6 = 6;

    public static final String OBFS_NONE = "none";
    public static final String OBFS_HTTP = "http";
    public static final String OBFS_TLS = "tls";

    public static final String MODE_DEFAULT = "default";
    public static final String MODE_UNSHAPED = "unshaped";
    public static final String MODE_UNSAFE_RAW = "unsafe-raw";

    public String psk;

    public Integer version;

    public Boolean reuse;
    public String obfsMode;
    public String obfsHost;
    public String mode;
    public String userKey;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (psk == null) psk = "";
        if (version == null) version = VERSION_4;
        if (reuse == null) reuse = true;
        if (obfsMode == null) obfsMode = OBFS_NONE;
        if (obfsHost == null) obfsHost = "";
        if (mode == null) mode = MODE_DEFAULT;
        if (userKey == null) userKey = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(psk);
        output.writeInt(version);
        output.writeBoolean(reuse);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeString(mode);
        output.writeString(userKey);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int ver = input.readInt();
        super.deserialize(input);
        psk = input.readString();
        version = input.readInt();
        reuse = input.readBoolean();
        obfsMode = input.readString();
        obfsHost = input.readString();
        mode = input.readString();
        userKey = input.readString();
    }

    public String protocolName() {
        return "Snell v" + version.toString();
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof SnellBean bean)) return;
        bean.reuse = reuse;
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SnellBean> CREATOR = new CREATOR<>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };
}
