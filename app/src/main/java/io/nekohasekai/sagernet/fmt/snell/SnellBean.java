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

    public static final int VERSION_3 = 3;
    public static final int VERSION_4 = 4;
    public static final int VERSION_5 = 5;
    public static final int VERSION_6 = 6;

    public static final String OBFS_OFF = "off";
    public static final String OBFS_HTTP = "http";
    public static final String OBFS_TLS = "tls";

    /** v6 modes: default | unshaped | unsafe-raw */
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_UNSHAPED = "unshaped";
    public static final String MODE_UNSAFE_RAW = "unsafe-raw";

    public String psk;
    public String obfs;
    public String obfsHost;
    public Integer version;
    public Boolean reuse;
    public String mode;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (psk == null) psk = "";
        if (obfs == null || obfs.isEmpty()) obfs = OBFS_OFF;
        if (obfsHost == null) obfsHost = "";
        if (version == null || version == 0) version = VERSION_4;
        if (reuse == null) reuse = true;
        if (mode == null || mode.isEmpty()) mode = MODE_DEFAULT;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        // v2: +obfsHost +mode
        output.writeInt(2);
        super.serialize(output);
        output.writeString(psk);
        output.writeString(obfs);
        output.writeInt(version);
        output.writeBoolean(reuse);
        output.writeString(obfsHost);
        output.writeString(mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int ver = input.readInt();
        super.deserialize(input);
        psk = input.readString();
        obfs = input.readString();
        version = input.readInt();
        if (ver >= 1) {
            reuse = input.readBoolean();
        }
        if (ver >= 2) {
            obfsHost = input.readString();
            mode = input.readString();
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
