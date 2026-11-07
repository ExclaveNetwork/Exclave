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

package io.nekohasekai.sagernet.fmt;

import static io.nekohasekai.sagernet.fmt.gson.GsonsKt.getGson;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.nekohasekai.sagernet.ExtraType;
import io.nekohasekai.sagernet.ktx.KryosKt;
import io.nekohasekai.sagernet.ktx.NetsKt;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;
    public String name;

    public transient String finalAddress;
    public transient int finalPort;

    public int extraType;
    public String profileId;

    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.joinHostPort(NetsKt.wrapIDN(this.serverAddress), this.serverPort);
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (serverAddress == null) serverAddress = "127.0.0.1";
        if (serverPort == null) serverPort = 1080;
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (profileId == null) profileId = "";
    }


    private transient boolean serializeWithoutName;

    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);
        output.writeInt(2);
        if (!serializeWithoutName) {
            output.writeString(name);
        }
        output.writeInt(extraType);
        if (extraType == ExtraType.NONE) return;
        output.writeString(profileId);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);
        int extraVersion = input.readInt();
        name = input.readString();
        extraType = input.readInt();
        if (extraType == ExtraType.NONE) return;
        profileId = input.readString();

        if (extraVersion < 2 && extraType == ExtraType.OOCv1) {
            input.readString();
            if (extraVersion >= 1) {
                input.readString();
            }
            KryosKt.readStringList(input);
        }
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    @NotNull
    @Override
    public abstract AbstractBean clone();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        try {
            serializeWithoutName = true;
            ((AbstractBean) o).serializeWithoutName = true;
            return Arrays.equals(KryoConverters.serialize(this), KryoConverters.serialize((AbstractBean) o));
        } finally {
            serializeWithoutName = false;
            ((AbstractBean) o).serializeWithoutName = false;
        }
    }

    @Override
    public int hashCode() {
        try {
            serializeWithoutName = true;
            return Arrays.hashCode(KryoConverters.serialize(this));
        } finally {
            serializeWithoutName = false;
        }
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + getGson().toJson(this);
    }

    public void applyFeatureSettings(AbstractBean other) {
    }

    public boolean isInsecure() {
        return false;
    }

}
