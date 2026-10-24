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

package io.nekohasekai.sagernet.database;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.SubscriptionType;
import io.nekohasekai.sagernet.fmt.Serializable;
import io.nekohasekai.sagernet.ktx.KryosKt;

public class SubscriptionBean extends Serializable {

    public Integer type;
    public String link;
    public Boolean deduplication;
    public Boolean updateWhenConnectedOnly;
    public String customUserAgent;
    public Boolean autoUpdate;
    public Integer autoUpdateDelay;
    public Long lastUpdated;
    public Long lastSuccess;
    public Long lastFailure;
    public Long cooldownUntil;
    public Integer failureCount;
    public Long lastAttempt;
    public Long bytesUsed;
    public Long bytesRemaining;
    public Long expiryDate;

    public String nameFilter;
    public String nameFilter1;

    public SubscriptionBean() {
    }

    @Override
    public void serializeToBuffer(ByteBufferOutput output) {
        output.writeInt(9);
        output.writeInt(type);
        output.writeString(link);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(autoUpdate);
        output.writeInt(autoUpdateDelay);
        output.writeLong(lastUpdated == null ? 0L : lastUpdated);
        output.writeLong(bytesUsed == null ? 0L : bytesUsed);
        output.writeLong(bytesRemaining == null ? 0L : bytesRemaining);
        output.writeLong(expiryDate == null ? 0L : expiryDate);
        output.writeString(nameFilter);
        output.writeString(nameFilter1);
        output.writeLong(lastSuccess == null ? 0L : lastSuccess);
        output.writeLong(lastFailure == null ? 0L : lastFailure);
        output.writeLong(cooldownUntil == null ? 0L : cooldownUntil);
        output.writeInt(failureCount == null ? 0 : failureCount);
        output.writeLong(lastAttempt == null ? 0L : lastAttempt);
    }

    public void serializeForShare(ByteBufferOutput output) {
        output.writeInt(7);
        output.writeInt(type);
        output.writeString(link);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeLong(bytesUsed);
        output.writeLong(bytesRemaining);
        output.writeLong(expiryDate);
        output.writeString(nameFilter);
        output.writeString(nameFilter1);
    }

    @Override
    public void deserializeFromBuffer(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();

        if (version < 7 && type == SubscriptionType.OOCv1) {
            input.readString(); // token, removed
            link = "";
        } else {
            link = input.readString();
        }
        if (version < 6) {
            input.readBoolean(); // forceResolve, removed
        }

        deduplication = input.readBoolean();
        if (version < 2) input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        autoUpdate = input.readBoolean();
        autoUpdateDelay = input.readInt();
        if (version <= 3) {
            lastUpdated = (long) input.readInt();
        } else {
            lastUpdated = input.readLong();
        }


        if (type == SubscriptionType.RAW && version == 3) {
            input.readString(); // subscriptionUserinfo, removed
        }

        if (type != SubscriptionType.RAW || version >= 4) {
            bytesUsed = input.readLong();
            bytesRemaining = input.readLong();
        }

        if (version >= 4) {
            expiryDate = input.readLong();
        }

        if (version >= 5) {
            nameFilter = input.readString();
        }

        if (version < 7 && type == SubscriptionType.OOCv1) {
            input.readString();
            if (version <= 3) {
                input.readInt();
            }
            KryosKt.readStringList(input);
            if (input.canReadVarInt()) {
                KryosKt.readStringSet(input);
                if (version >= 1) {
                    KryosKt.readStringSet(input);
                }
                KryosKt.readStringSet(input);
            }
        }

        if (version >= 8) {
            nameFilter1 = input.readString();
        }

        if (version >= 9) {
            lastSuccess = input.readLong();
            lastFailure = input.readLong();
            cooldownUntil = input.readLong();
            failureCount = input.readInt();
            lastAttempt = input.readLong();
        }
    }

    public void deserializeFromShare(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();

        if (version < 6 && type == SubscriptionType.OOCv1) {
            input.readString(); // token, removed
            link = "";
        } else {
            link = input.readString();
        }
        if (version < 5) {
            input.readBoolean(); // forceResolve, removed
        }
        deduplication = input.readBoolean();
        if (version < 1) input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();

        if (type == SubscriptionType.RAW && version == 2) {
            input.readString(); // subscriptionUserinfo, removed
        }

        if (type != SubscriptionType.RAW || version >= 3) {
            bytesUsed = input.readLong();
            bytesRemaining = input.readLong();
        }

        if (version >= 3) {
            expiryDate = input.readLong();
        }

        if (version >= 4) {
            nameFilter = input.readString();
        }

        if (version < 6 && type == SubscriptionType.OOCv1) {
            input.readString();
            if (version <= 2) {
                input.readInt();
            }
            KryosKt.readStringList(input);
        }

        if (version >= 7) {
            nameFilter1 = input.readString();
        }
    }

    @Override
    public void initializeDefaultValues() {
        if (type == null) type = SubscriptionType.RAW;
        if (link == null) link = "";
        if (deduplication == null) deduplication = true;
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false;
        if (customUserAgent == null) customUserAgent = "";
        if (autoUpdate == null) autoUpdate = true;
        if (autoUpdateDelay == null) autoUpdateDelay = 200;
        if (lastUpdated == null) lastUpdated = 0L;
        if (lastSuccess == null) lastSuccess = lastUpdated;
        if (lastFailure == null) lastFailure = 0L;
        if (cooldownUntil == null) cooldownUntil = 0L;
        if (failureCount == null) failureCount = 0;
        if (lastAttempt == null) lastAttempt = 0L;

        if (bytesUsed == null) bytesUsed = 0L;
        if (bytesRemaining == null) bytesRemaining = 0L;
        if (nameFilter == null) nameFilter = "";
        if (nameFilter1 == null) nameFilter1 = "";

        if (expiryDate == null) expiryDate = 0L;
    }

    public static final Creator<SubscriptionBean> CREATOR = new CREATOR<SubscriptionBean>() {
        @NonNull
        @Override
        public SubscriptionBean newInstance() {
            return new SubscriptionBean();
        }

        @Override
        public SubscriptionBean[] newArray(int size) {
            return new SubscriptionBean[size];
        }
    };

}
