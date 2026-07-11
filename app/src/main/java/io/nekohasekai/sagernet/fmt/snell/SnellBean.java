/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Snell support for Exclave                              *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

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
    /** sing-box private extension; only compatible with sing-box Snell server. */
    public String userPSK;
    public String obfsMode;
    /** v4-only */
    public String obfsHost;
    public Integer version;
    public Boolean reuse;
    /** v6-only */
    public String mode;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (psk == null) psk = "";
        if (userPSK == null) userPSK = "";
        if (obfsMode == null || obfsMode.isEmpty()) obfsMode = OBFS_NONE;
        if (obfsHost == null) obfsHost = "";
        if (version == null || version == 0) version = VERSION_4;
        if (reuse == null) reuse = true;
        if (mode == null || mode.isEmpty()) mode = MODE_DEFAULT;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        // v3: rename obfs->obfsMode, add userPSK; version 4/6 only
        output.writeInt(3);
        super.serialize(output);
        output.writeString(psk);
        output.writeString(obfsMode);
        output.writeInt(version);
        output.writeBoolean(reuse);
        output.writeString(obfsHost);
        output.writeString(mode);
        output.writeString(userPSK);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int ver = input.readInt();
        super.deserialize(input);
        psk = input.readString();
        obfsMode = input.readString();
        // migrate legacy "off" -> "none" for old profiles only
        if ("off".equals(obfsMode)) {
            obfsMode = OBFS_NONE;
        }
        version = input.readInt();
        // migrate legacy 3/5 to 4
        if (version != null && version != VERSION_4 && version != VERSION_6) {
            version = VERSION_4;
        }
        if (ver >= 1) {
            reuse = input.readBoolean();
        }
        if (ver >= 2) {
            obfsHost = input.readString();
            mode = input.readString();
        }
        if (ver >= 3) {
            userPSK = input.readString();
        }
    }

    @Override
    public String network() {
        return "tcp,udp";
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
