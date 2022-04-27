/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.AppIdInt;
import android.annotation.UserIdInt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Representation of a user on the device.
 */
public final class UserHandle implements Parcelable {
    // NOTE: keep logic in sync with system/core/libcutils/multiuser.c

    /**
     * @hide Range of uids allocated for a user.
     */

    public static final int PER_USER_RANGE = 100000;

    /** @hide A user id to indicate all users on the device */

    public static final @UserIdInt int USER_ALL = -1;

    /** @hide A user handle to indicate all users on the device */

    public static final @NonNull UserHandle ALL = new UserHandle(USER_ALL);

    /** @hide A user id to indicate the currently active user */

    public static final @UserIdInt
    int USER_CURRENT = -2;

    /** @hide A user handle to indicate the current user of the device */
    public static final @NonNull UserHandle CURRENT = new UserHandle(USER_CURRENT);

    /** @hide A user id to indicate that we would like to send to the current
     *  user, but if this is calling from a user process then we will send it
     *  to the caller's user instead of failing with a security exception */
    public static final @UserIdInt int USER_CURRENT_OR_SELF = -3;

    /** @hide A user handle to indicate that we would like to send to the current
     *  user, but if this is calling from a user process then we will send it
     *  to the caller's user instead of failing with a security exception */
    public static final @NonNull UserHandle CURRENT_OR_SELF = new UserHandle(USER_CURRENT_OR_SELF);

    /** @hide An undefined user id */
    public static final @UserIdInt int USER_NULL = -10000;

    private static final @NonNull UserHandle NULL = new UserHandle(USER_NULL);

    /**
     * @hide A user id constant to indicate the "owner" user of the device
     * @deprecated Consider using either {@link UserHandle#USER_SYSTEM} constant or
     * check the target user's flag {link android.content.pm.UserInfo#isAdmin}.
     */
    @Deprecated
    public static final @UserIdInt int USER_OWNER = 0;

    /**
     * @hide A user handle to indicate the primary/owner user of the device
     * @deprecated Consider using either {@link UserHandle#SYSTEM} constant or
     * check the target user's flag {link android.content.pm.UserInfo#isAdmin}.
     */
    @Deprecated
    public static final @NonNull UserHandle OWNER = new UserHandle(USER_OWNER);

    /** @hide A user id constant to indicate the "system" user of the device */
    public static final @UserIdInt int USER_SYSTEM = 0;

    /** @hide A user serial constant to indicate the "system" user of the device */
    // @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int USER_SERIAL_SYSTEM = 0;

    /** @hide A user handle to indicate the "system" user of the device */
    public static final @NonNull UserHandle SYSTEM = new UserHandle(USER_SYSTEM);

    /**
     * @hide Enable multi-user related side effects. Set this to false if
     * there are problems with single user use-cases.
     */
    public static final boolean MU_ENABLED = true;

    /** @hide */
    public static final int MIN_SECONDARY_USER_ID = 10;

    /**
     * Arbitrary user handle cache size. We use the cache even when {@link #MU_ENABLED} is false
     * anyway, so we can always assume in CTS that UserHandle.of(10) returns a cached instance
     * even on non-multiuser devices.
     */
    private static final int NUM_CACHED_USERS = 4;

    private static final UserHandle[] CACHED_USER_INFOS = new UserHandle[NUM_CACHED_USERS];

    static {
        // Not lazily initializing the cache, so that we can share them across processes.
        // (We'll create them in zygote.)
        for (int i = 0; i < CACHED_USER_INFOS.length; i++) {
            CACHED_USER_INFOS[i] = new UserHandle(MIN_SECONDARY_USER_ID + i);
        }
    }

    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int ERR_GID = -1;
    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int AID_ROOT = android.os.Process.ROOT_UID;
    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int AID_APP_START = android.os.Process.FIRST_APPLICATION_UID;
    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int AID_APP_END = android.os.Process.LAST_APPLICATION_UID;
    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int AID_SHARED_GID_START = 50000;
    /** @hide */
    //@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int AID_CACHE_GID_START = 20000;

    /** The userId represented by this UserHandle. */
    final @UserIdInt int mHandle;

    /**
     * Checks to see if the user id is the same for the two uids, i.e., they belong to the same
     * user.
     * @hide
     */
    public static boolean isSameUser(int uid1, int uid2) {
        return getUserId(uid1) == getUserId(uid2);
    }

    /**
     * Checks to see if both uids are referring to the same app id, ignoring the user id part of the
     * uids.
     * @param uid1 uid to compare
     * @param uid2 other uid to compare
     * @return whether the appId is the same for both uids
     * @hide
     */
    public static boolean isSameApp(int uid1, int uid2) {
        return getAppId(uid1) == getAppId(uid2);
    }

    /**
     * Whether a UID is an "isolated" UID.
     * @hide
     */
    public static boolean isIsolated(int uid) {
        throw new RuntimeException("Stub!");
    }

    /**
     * Whether a UID belongs to a regular app. *Note* "Not a regular app" does not mean
     * "it's system", because of isolated UIDs. Use {@link #isCore} for that.
     * @hide
     */
    public static boolean isApp(int uid) {
        if (uid > 0) {
            final int appId = getAppId(uid);
            return appId >= Process.FIRST_APPLICATION_UID && appId <= Process.LAST_APPLICATION_UID;
        } else {
            return false;
        }
    }

    /**
     * Whether a UID belongs to a system core component or not.
     * @hide
     */
    public static boolean isCore(int uid) {
        if (uid >= 0) {
            final int appId = getAppId(uid);
            return appId < Process.FIRST_APPLICATION_UID;
        } else {
            return false;
        }
    }

    /**
     * Whether a UID belongs to a shared app gid.
     * @hide
     */
    public static boolean isSharedAppGid(int uid) {
        return getAppIdFromSharedAppGid(uid) != -1;
    }

    /**
     * Returns the user for a given uid.
     * @param uid A uid for an application running in a particular user.
     * @return A {@link UserHandle} for that user.
     */
    public static UserHandle getUserHandleForUid(int uid) {
        return of(getUserId(uid));
    }

    /**
     * Returns the user id for a given uid.
     * @hide
     */
    public static @UserIdInt int getUserId(int uid) {
        if (MU_ENABLED) {
            return uid / PER_USER_RANGE;
        } else {
            return UserHandle.USER_SYSTEM;
        }
    }

    /** @hide */
    public static @UserIdInt int getCallingUserId() {
        return getUserId(Binder.getCallingUid());
    }

    /** @hide */
    public static @AppIdInt int getCallingAppId() {
        return getAppId(Binder.getCallingUid());
    }

    /** @hide */
    @NonNull
    public static int[] fromUserHandles(@NonNull List<UserHandle> users) {
        throw new RuntimeException("Stub!");
    }

    /** @hide */
    @NonNull
    public static List<UserHandle> toUserHandles(@NonNull int[] userIds) {
        List<UserHandle> users = new ArrayList<>(userIds.length);
        for (int i = 0; i < userIds.length; ++i) {
            users.add(UserHandle.of(userIds[i]));
        }
        return users;
    }

    /** @hide */
    public static UserHandle of(@UserIdInt int userId) {
        if (userId == USER_SYSTEM) {
            return SYSTEM; // Most common.
        }
        // These are sequential; so use a switch. Maybe they'll be optimized to a table lookup.
        switch (userId) {
            case USER_ALL:
                return ALL;

            case USER_CURRENT:
                return CURRENT;

            case USER_CURRENT_OR_SELF:
                return CURRENT_OR_SELF;
        }
        if (userId >= MIN_SECONDARY_USER_ID
                && userId < (MIN_SECONDARY_USER_ID + CACHED_USER_INFOS.length)) {
            return CACHED_USER_INFOS[userId - MIN_SECONDARY_USER_ID];
        }
        if (userId == USER_NULL) { // Not common.
            return NULL;
        }
        return new UserHandle(userId);
    }

    /**
     * Returns the uid that is composed from the userId and the appId.
     * @hide
     */
    public static int getUid(@UserIdInt int userId, @AppIdInt int appId) {
        if (MU_ENABLED) {
            return userId * PER_USER_RANGE + (appId % PER_USER_RANGE);
        } else {
            return appId;
        }
    }

    /**
     * Returns the uid representing the given appId for this UserHandle.
     *
     * @param appId the AppId to compose the uid
     * @return the uid representing the given appId for this UserHandle
     * @hide
     */
    public int getUid(@AppIdInt int appId) {
        return getUid(getIdentifier(), appId);
    }

    /**
     * Returns the app id (or base uid) for a given uid, stripping out the user id from it.
     * @hide
     */
    public static @AppIdInt int getAppId(int uid) {
        return uid % PER_USER_RANGE;
    }

    /**
     * Returns the gid shared between all apps with this userId.
     * @hide
     */
    public static int getUserGid(@UserIdInt int userId) {
        throw new RuntimeException("Stub!");
    }

    /** @hide */
    public static int getSharedAppGid(int uid) {
        return getSharedAppGid(getUserId(uid), getAppId(uid));
    }

    /** @hide */
    public static int getSharedAppGid(@UserIdInt int userId, @AppIdInt int appId) {
        if (appId >= AID_APP_START && appId <= AID_APP_END) {
            return (appId - AID_APP_START) + AID_SHARED_GID_START;
        } else if (appId >= AID_ROOT && appId <= AID_APP_START) {
            return appId;
        } else {
            return -1;
        }
    }

    /**
     * Returns the app id for a given shared app gid. Returns -1 if the ID is invalid.
     * @hide
     */
    public static @AppIdInt int getAppIdFromSharedAppGid(int gid) {
        throw new RuntimeException("Stub!");
    }

    /** @hide */
    public static int getCacheAppGid(int uid) {
        return getCacheAppGid(getUserId(uid), getAppId(uid));
    }

    /** @hide */
    public static int getCacheAppGid(@UserIdInt int userId, @AppIdInt int appId) {
        if (appId >= AID_APP_START && appId <= AID_APP_END) {
            return getUid(userId, (appId - AID_APP_START) + AID_CACHE_GID_START);
        } else {
            return -1;
        }
    }

    /**
     * Generate a text representation of the uid, breaking out its individual
     * components -- user, app, isolated, etc.
     * @hide
     */
    public static void formatUid(StringBuilder sb, int uid) {
        throw new RuntimeException("Stub!");
    }

    /**
     * Generate a text representation of the uid, breaking out its individual
     * components -- user, app, isolated, etc.
     *
     * @param uid The uid to format
     * @return A string representing the UID with its individual components broken out
     * @hide
     */
    @NonNull
    public static String formatUid(int uid) {
        StringBuilder sb = new StringBuilder();
        formatUid(sb, uid);
        return sb.toString();
    }

    /**
     * Generate a text representation of the uid, breaking out its individual
     * components -- user, app, isolated, etc.
     * @hide
     */
    // @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static void formatUid(PrintWriter pw, int uid) {
        throw new RuntimeException("Stub!");
    }

    /** @hide */
    public static @UserIdInt int parseUserArg(String arg) {
        int userId;
        if ("all".equals(arg)) {
            userId = UserHandle.USER_ALL;
        } else if ("current".equals(arg) || "cur".equals(arg)) {
            userId = UserHandle.USER_CURRENT;
        } else {
            try {
                userId = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad user number: " + arg);
            }
        }
        return userId;
    }

    /**
     * Returns the user id of the current process
     * @return user id of the current process
     * @hide
     */
    public static @UserIdInt int myUserId() {
        return getUserId(Process.myUid());
    }

    /**
     * Returns true if this UserHandle refers to the owner user; false otherwise.
     * @return true if this UserHandle refers to the owner user; false otherwise.
     * @hide
     * @deprecated please use {@link #isSystem()} or check for
     * {link android.content.pm.UserInfo#isPrimary()}
     * {link android.content.pm.UserInfo#isAdmin()} based on your particular use case.
     */
    @Deprecated
    public boolean isOwner() {
        return this.equals(OWNER);
    }

    /**
     * @return true if this UserHandle refers to the system user; false otherwise.
     * @hide
     */
    public boolean isSystem() {
        return this.equals(SYSTEM);
    }

    /** @hide */
    public UserHandle(@UserIdInt int userId) {
        mHandle = userId;
    }

    /**
     * Returns the userId stored in this UserHandle.
     * @hide
     */
    public @UserIdInt int getIdentifier() {
        return mHandle;
    }

    @Override
    public String toString() {
        return "UserHandle{" + mHandle + "}";
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public int hashCode() {
        return mHandle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mHandle);
    }

    /**
     * Write a UserHandle to a Parcel, handling null pointers.  Must be
     * read with {@link #readFromParcel(Parcel)}.
     *
     * @param h The UserHandle to be written.
     * @param out The Parcel in which the UserHandle will be placed.
     *
     * @see #readFromParcel(Parcel)
     */
    public static void writeToParcel(UserHandle h, Parcel out) {
        if (h != null) {
            h.writeToParcel(out, 0);
        } else {
            out.writeInt(USER_NULL);
        }
    }

    /**
     * Read a UserHandle from a Parcel that was previously written
     * with {@link #writeToParcel(UserHandle, Parcel)}, returning either
     * a null or new object as appropriate.
     *
     * @param in The Parcel from which to read the UserHandle
     * @return Returns a new UserHandle matching the previously written
     * object, or null if a null had been written.
     *
     * @see #writeToParcel(UserHandle, Parcel)
     */
    public static UserHandle readFromParcel(Parcel in) {
        int h = in.readInt();
        return h != USER_NULL ? new UserHandle(h) : null;
    }

    public static final Parcelable.Creator<UserHandle> CREATOR
            = new Parcelable.Creator<UserHandle>() {
        @Override
        public UserHandle createFromParcel(Parcel in) {
            // Try to avoid allocation; use of() here. Keep this and the constructor below
            // in sync.
            return UserHandle.of(in.readInt());
        }

        @Override
        public UserHandle[] newArray(int size) {
            return new UserHandle[size];
        }
    };

    /**
     * Instantiate a new UserHandle from the data in a Parcel that was
     * previously written with {@link #writeToParcel(Parcel, int)}.  Note that you
     * must not use this with data written by
     * {@link #writeToParcel(UserHandle, Parcel)} since it is not possible
     * to handle a null UserHandle here.
     *
     * @param in The Parcel containing the previously written UserHandle,
     * positioned at the location in the buffer where it was written.
     */
    public UserHandle(Parcel in) {
        mHandle = in.readInt(); // Keep this and createFromParcel() in sync.
    }
}
