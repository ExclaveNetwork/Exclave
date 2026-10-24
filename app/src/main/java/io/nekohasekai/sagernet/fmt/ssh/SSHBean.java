/******************************************************************************
 * Copyright (C) 2021 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.fmt.ssh;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import libsagernetcore.Libsagernetcore;

public class SSHBean extends AbstractBean {

    public static final int AUTH_TYPE_NONE = 0;
    public static final int AUTH_TYPE_PASSWORD = 1;
    public static final int AUTH_TYPE_PUBLIC_KEY = 2;

    public String username;
    public Integer authType;
    public String password;
    public String privateKey;
    public String privateKeyPassphrase;
    public String publicKey;
    public Integer keepaliveInterval;

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 22;

        super.initializeDefaultValues();

        if (username == null) username = "root";
        if (authType == null) authType = AUTH_TYPE_NONE;
        if (password == null) password = "";
        if (privateKey == null) privateKey = "";
        if (privateKeyPassphrase == null) privateKeyPassphrase = "";
        if (publicKey == null) publicKey = "";
        if (keepaliveInterval == null) keepaliveInterval = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(username);
        output.writeInt(authType);
        switch (authType) {
            case AUTH_TYPE_NONE:
                break;
            case AUTH_TYPE_PASSWORD:
                output.writeString(password);
                break;
            case AUTH_TYPE_PUBLIC_KEY:
                output.writeString(privateKey);
                output.writeString(privateKeyPassphrase);
                break;
        }
        output.writeString(publicKey);
        output.writeInt(keepaliveInterval);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        authType = input.readInt();
        switch (authType) {
            case AUTH_TYPE_NONE:
                break;
            case AUTH_TYPE_PASSWORD:
                password = input.readString();
                break;
            case AUTH_TYPE_PUBLIC_KEY:
                privateKey = input.readString();
                privateKeyPassphrase = input.readString();
                break;
        }
        publicKey = input.readString();
        if (version >= 1) {
            keepaliveInterval = input.readInt();
        }
    }


    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof SSHBean bean)) return;
        if (bean.publicKey == null || bean.publicKey.isEmpty() && !publicKey.isEmpty()) {
            bean.publicKey = publicKey;
        }
        bean.keepaliveInterval = keepaliveInterval;
    }


    @NotNull
    @Override
    public SSHBean clone() {
        return KryoConverters.deserialize(new SSHBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SSHBean> CREATOR = new CREATOR<SSHBean>() {
        @NonNull
        @Override
        public SSHBean newInstance() {
            return new SSHBean();
        }

        @Override
        public SSHBean[] newArray(int size) {
            return new SSHBean[size];
        }
    };

    @Override
    public boolean isInsecure() {
        if (Libsagernetcore.isLoopbackIP(serverAddress) || serverAddress.equals("localhost")) {
            return false;
        }
        if (!publicKey.isEmpty()) {
            return false;
        }
        return true;
    }

}
