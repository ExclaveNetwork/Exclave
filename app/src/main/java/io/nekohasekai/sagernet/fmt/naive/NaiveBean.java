/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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

package io.nekohasekai.sagernet.fmt.naive;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class NaiveBean extends AbstractBean {

    /**
     * Available proto: https, quic.
     */
    public String proto;
    public String username;
    public String password;
    public String extraHeaders;
    public Integer insecureConcurrency;
    public Boolean noPostQuantum;
    public String sni;
    public String certificate;
    public Boolean singUoT; // Invented by sing-box, not part of NaïveProxy. This may get removed at any time.

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 443;
        super.initializeDefaultValues();
        if (proto == null) proto = "https";
        if (username == null) username = "";
        if (password == null) password = "";
        if (extraHeaders == null) extraHeaders = "";
        if (insecureConcurrency == null) insecureConcurrency = 0;
        if (noPostQuantum == null) noPostQuantum = false;
        if (sni == null) sni = "";
        if (certificate == null) certificate = "";
        if (singUoT == null) singUoT = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(7);
        super.serialize(output);
        output.writeString(proto);
        output.writeString(username);
        output.writeString(password);
        output.writeString(extraHeaders);
        output.writeInt(insecureConcurrency);
        output.writeBoolean(noPostQuantum);
        output.writeString(sni);
        output.writeString(certificate);
        output.writeBoolean(singUoT);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        proto = input.readString();
        username = input.readString();
        password = input.readString();
        extraHeaders = input.readString();
        if (version >= 1) {
            insecureConcurrency = input.readInt();
        }
        if (version == 2) {
            input.readBoolean(); // uot, removed
        }
        if (version >= 4) {
            noPostQuantum = input.readBoolean();
        }
        if (version >= 5) {
            sni = input.readString();
        }
        if (version >= 6) {
            certificate = input.readString();
        }
        if (version >= 7) {
            singUoT = input.readBoolean();
        }
    }

    @Override
    public String network() {
        if (proto.equals("quic")) {
            return "udp";
        }
        return "tcp";
    }

    @Override
    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof NaiveBean bean)) return;
        bean.noPostQuantum = noPostQuantum;
        bean.certificate = certificate;
        bean.insecureConcurrency = insecureConcurrency;
        bean.singUoT = singUoT;
    }

    @NotNull
    @Override
    public NaiveBean clone() {
        return KryoConverters.deserialize(new NaiveBean(), KryoConverters.serialize(this));
    }

    public static final Creator<NaiveBean> CREATOR = new CREATOR<NaiveBean>() {
        @NonNull
        @Override
        public NaiveBean newInstance() {
            return new NaiveBean();
        }

        @Override
        public NaiveBean[] newArray(int size) {
            return new NaiveBean[size];
        }
    };
}
