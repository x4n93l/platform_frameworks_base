/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageStats;
import android.os.Build;
import android.os.IInstalld;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Slog;

import com.android.internal.os.InstallerConnection;
import com.android.internal.os.InstallerConnection.InstallerException;
import com.android.server.SystemService;

import dalvik.system.VMRuntime;

import java.util.Arrays;

public final class Installer extends SystemService {
    private static final String TAG = "Installer";

    /* ***************************************************************************
     * IMPORTANT: These values are passed to native code. Keep them in sync with
     * frameworks/native/cmds/installd/installd.h
     * **************************************************************************/
    /** Application should be visible to everyone */
    public static final int DEXOPT_PUBLIC         = 1 << 1;
    /** Application wants to run in VM safe mode */
    public static final int DEXOPT_SAFEMODE       = 1 << 2;
    /** Application wants to allow debugging of its code */
    public static final int DEXOPT_DEBUGGABLE     = 1 << 3;
    /** The system boot has finished */
    public static final int DEXOPT_BOOTCOMPLETE   = 1 << 4;
    /** Hint that the dexopt type is profile-guided. */
    public static final int DEXOPT_PROFILE_GUIDED = 1 << 5;
    /** This is an OTA update dexopt */
    public static final int DEXOPT_OTA            = 1 << 6;

    // NOTE: keep in sync with installd
    public static final int FLAG_CLEAR_CACHE_ONLY = 1 << 8;
    public static final int FLAG_CLEAR_CODE_CACHE_ONLY = 1 << 9;

    private final InstallerConnection mInstaller;
    private final IInstalld mInstalld;

    private volatile Object mWarnIfHeld;

    public Installer(Context context) {
        super(context);
        mInstaller = new InstallerConnection();
        // TODO: reconnect if installd restarts
        mInstalld = IInstalld.Stub.asInterface(ServiceManager.getService("installd"));
    }

    // Package-private installer that accepts a custom InstallerConnection. Used for
    // OtaDexoptService.
    Installer(Context context, InstallerConnection connection) {
        super(context);
        mInstaller = connection;
        // TODO: reconnect if installd restarts
        mInstalld = IInstalld.Stub.asInterface(ServiceManager.getService("installd"));
    }

    /**
     * Yell loudly if someone tries making future calls while holding a lock on
     * the given object.
     */
    public void setWarnIfHeld(Object warnIfHeld) {
        mInstaller.setWarnIfHeld(warnIfHeld);
        mWarnIfHeld = warnIfHeld;
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Waiting for installd to be ready.");
        mInstaller.waitForConnection();
    }

    private void checkLock() {
        if (mWarnIfHeld != null && Thread.holdsLock(mWarnIfHeld)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding 0x"
                    + Integer.toHexString(System.identityHashCode(mWarnIfHeld)), new Throwable());
        }
    }

    public void createAppData(String uuid, String packageName, int userId, int flags, int appId,
            String seInfo, int targetSdkVersion) throws InstallerException {
        checkLock();
        try {
            mInstalld.createAppData(uuid, packageName, userId, flags, appId, seInfo,
                    targetSdkVersion);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void restoreconAppData(String uuid, String packageName, int userId, int flags, int appId,
            String seInfo) throws InstallerException {
        checkLock();
        try {
            mInstalld.restoreconAppData(uuid, packageName, userId, flags, appId, seInfo);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void migrateAppData(String uuid, String packageName, int userId, int flags)
            throws InstallerException {
        checkLock();
        try {
            mInstalld.migrateAppData(uuid, packageName, userId, flags);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void clearAppData(String uuid, String packageName, int userId, int flags,
            long ceDataInode) throws InstallerException {
        checkLock();
        try {
            mInstalld.clearAppData(uuid, packageName, userId, flags, ceDataInode);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void destroyAppData(String uuid, String packageName, int userId, int flags,
            long ceDataInode) throws InstallerException {
        checkLock();
        try {
            mInstalld.destroyAppData(uuid, packageName, userId, flags, ceDataInode);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void moveCompleteApp(String fromUuid, String toUuid, String packageName,
            String dataAppName, int appId, String seInfo, int targetSdkVersion)
            throws InstallerException {
        checkLock();
        try {
            mInstalld.moveCompleteApp(fromUuid, toUuid, packageName, dataAppName, appId, seInfo,
                    targetSdkVersion);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void getAppSize(String uuid, String pkgname, int userid, int flags, long ceDataInode,
            String codePath, PackageStats stats) throws InstallerException {
        final String[] res = mInstaller.execute("get_app_size", uuid, pkgname, userid, flags,
                ceDataInode, codePath);
        try {
            stats.codeSize += Long.parseLong(res[1]);
            stats.dataSize += Long.parseLong(res[2]);
            stats.cacheSize += Long.parseLong(res[3]);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            throw new InstallerException("Invalid size result: " + Arrays.toString(res));
        }
    }

    public long getAppDataInode(String uuid, String packageName, int userId, int flags)
            throws InstallerException {
        checkLock();
        try {
            return mInstalld.getAppDataInode(uuid, packageName, userId, flags);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void dexopt(String apkPath, int uid, String instructionSet, int dexoptNeeded,
            int dexFlags, String compilerFilter, String volumeUuid, String sharedLibraries)
            throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.dexopt(apkPath, uid, instructionSet, dexoptNeeded, dexFlags,
                compilerFilter, volumeUuid, sharedLibraries);
    }

    public void dexopt(String apkPath, int uid, String pkgName, String instructionSet,
            int dexoptNeeded, @Nullable String outputPath, int dexFlags,
            String compilerFilter, String volumeUuid, String sharedLibraries)
            throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.dexopt(apkPath, uid, pkgName, instructionSet, dexoptNeeded,
                outputPath, dexFlags, compilerFilter, volumeUuid, sharedLibraries);
    }

    public boolean mergeProfiles(int uid, String pkgName) throws InstallerException {
        return mInstaller.mergeProfiles(uid, pkgName);
    }

    public boolean dumpProfiles(String gid, String packageName, String codePaths)
            throws InstallerException {
        return mInstaller.dumpProfiles(gid, packageName, codePaths);
    }

    public void idmap(String targetApkPath, String overlayApkPath, int uid)
            throws InstallerException {
        mInstaller.execute("idmap", targetApkPath, overlayApkPath, uid);
    }

    public void rmdex(String codePath, String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.execute("rmdex", codePath, instructionSet);
    }

    public void rmPackageDir(String packageDir) throws InstallerException {
        mInstaller.execute("rmpackagedir", packageDir);
    }

    public void clearAppProfiles(String pkgName) throws InstallerException {
        mInstaller.execute("clear_app_profiles", pkgName);
    }

    public void destroyAppProfiles(String pkgName) throws InstallerException {
        mInstaller.execute("destroy_app_profiles", pkgName);
    }

    public void createUserData(String uuid, int userId, int userSerial, int flags)
            throws InstallerException {
        checkLock();
        try {
            mInstalld.createUserData(uuid, userId, userSerial, flags);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void destroyUserData(String uuid, int userId, int flags) throws InstallerException {
        checkLock();
        try {
            mInstalld.destroyUserData(uuid, userId, flags);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new InstallerException(e.getMessage());
        }
    }

    public void markBootComplete(String instructionSet) throws InstallerException {
        assertValidInstructionSet(instructionSet);
        mInstaller.execute("markbootcomplete", instructionSet);
    }

    public void freeCache(String uuid, long freeStorageSize) throws InstallerException {
        mInstaller.execute("freecache", uuid, freeStorageSize);
    }

    /**
     * Links the 32 bit native library directory in an application's data
     * directory to the real location for backward compatibility. Note that no
     * such symlink is created for 64 bit shared libraries.
     */
    public void linkNativeLibraryDirectory(String uuid, String dataPath, String nativeLibPath32,
            int userId) throws InstallerException {
        mInstaller.execute("linklib", uuid, dataPath, nativeLibPath32, userId);
    }

    public void createOatDir(String oatDir, String dexInstructionSet)
            throws InstallerException {
        mInstaller.execute("createoatdir", oatDir, dexInstructionSet);
    }

    public void linkFile(String relativePath, String fromBase, String toBase)
            throws InstallerException {
        mInstaller.execute("linkfile", relativePath, fromBase, toBase);
    }

    public void moveAb(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        mInstaller.execute("move_ab", apkPath, instructionSet, outputPath);
    }

    public void deleteOdex(String apkPath, String instructionSet, String outputPath)
            throws InstallerException {
        mInstaller.execute("delete_odex", apkPath, instructionSet, outputPath);
    }

    private static void assertValidInstructionSet(String instructionSet)
            throws InstallerException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(instructionSet)) {
                return;
            }
        }
        throw new InstallerException("Invalid instruction set: " + instructionSet);
    }
}
