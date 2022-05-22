package com.virtual.box.core.manager

import android.content.ComponentName
import android.content.Intent
import android.content.pm.*
import android.os.Parcelable
import android.os.RemoteException
import com.virtual.box.base.util.log.L
import com.virtual.box.base.util.log.Logger
import com.virtual.box.core.VirtualBox
import com.virtual.box.core.server.pm.IVmPackageManagerService
import com.virtual.box.core.server.pm.IVmPackageObserver
import com.virtual.box.core.server.pm.entity.VmInstalledPackageInfo
import com.virtual.box.core.server.pm.entity.VmPackageInstallOption
import com.virtual.box.core.server.pm.entity.VmPackageResult
import java.lang.Exception

object VmPackageManager {
    private val logger = Logger.getLogger(L.HOST_TAG, "VmPackageManager")

    private var service: IVmPackageManagerService? = null

    private val packageMonitorList: MutableList<PackageMonitor> = mutableListOf()

    private val packageObserver: IVmPackageObserver = object : IVmPackageObserver.Stub() {

        override fun onPackageResult(installResult: VmPackageResult?) {
            if (installResult?.success == false) {
                dispatcherPackageOperatorFail(installResult)
                return
            }
            when (installResult?.resultType) {
                VmPackageResult.INSTALL_FLAG -> dispatcherPackageInstalled(installResult)
                VmPackageResult.UNINSTALL_FLAG -> dispatcherPackageUninstalled(installResult)
                VmPackageResult.UPDATE_FLAG -> dispatcherPackageUpgraded(installResult)
            }
        }
    }

    init {
        requireService().registerPackageObserver(packageObserver)
    }

    fun installPackage(installOption: VmPackageInstallOption): VmPackageResult {
        return try {
            requireService().installPackageAsUser(installOption, 0)
        } catch (e: RemoteException) {
            logger.e(e)
            VmPackageResult.installFail("调用服务进程安装失败：${e.message}")
        }
    }

    fun uninstallPackage(packageName: String, userId: Int): VmPackageResult {
        return try {
            requireService().uninstallPackageAsUser(packageName, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            VmPackageResult.installFail("调用服务进程卸载失败：${e.message}")
        }
    }

    fun isInstalled(packageName: String?, userId: Int): Boolean {
        return try {
            requireService().isInstalled(packageName, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            false
        }
    }

    fun getInstalledPackageInfoList(flag: Int): List<VmInstalledPackageInfo> {
        return try {
            requireService().getVmInstalledPackageInfos(flag)
        } catch (e: RemoteException) {
            logger.e(e)
            emptyList<VmInstalledPackageInfo>()
        }
    }

    fun getInstalledVmPackageInfo(packageName: String, flags: Int): VmInstalledPackageInfo? {
        return try {
            requireService().getVmInstalledPackageInfo(packageName, flags)
        } catch (e: RemoteException) {
            logger.e(e)
            return null
        }
    }

    fun getPackageInfo(packageName: String, flags: Int, userId: Int): PackageInfo? {
        return try {
            if (packageName == VirtualBox.get().hostPkg) {
                return VirtualBox.get().hostPm.getPackageInfo(packageName, flags)
            }
            requireService().getPackageInfo(packageName, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            null
        }
    }

    fun getApplicationInfo(packageName: String, flags: Int, userId: Int): ApplicationInfo? {
        return try {
            requireService().getApplicationInfo(packageName, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            null
        }
    }

    fun getActivityInfo(componentName: ComponentName, flags: Int, userId: Int): ActivityInfo? {
        return try {
            requireService().getActivityInfo(componentName, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            null
        }
    }

    fun getReceiverInfo(componentName: ComponentName?, flags: Int, userId: Int): ActivityInfo? {
        return try {
            requireService().getReceiverInfo(componentName, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
            null
        }
    }

    fun getServiceInfo(componentName: ComponentName?, flags: Int, userId: Int): ServiceInfo? {
        return try {
            return requireService().getServiceInfo(componentName, flags, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    fun getProviderInfo(componentName: ComponentName?, flags: Int, userId: Int): ProviderInfo? {
        return try {
            return requireService().getProviderInfo(componentName, flags, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    fun resolveIntent(intent: Intent?, resolveType: String?, flags: Int, userId: Int): ResolveInfo? {
        return try {
            return requireService().resolveIntent(intent, resolveType, flags, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    fun resolveActivity(intent: Intent, flags: Int, resolveType: String?, userId: Int): ResolveInfo? {
        return try {
            return requireService().resolveActivity(intent, flags, resolveType, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    fun queryIntentActivities(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<Parcelable> {
        try {
            return requireService().queryIntentActivities(intent, resolvedType, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
        }
        return ParceledListSlice.emptyList()
    }

    fun queryIntentReceivers(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<Parcelable> {
        try {
            return requireService().queryIntentReceivers(intent, resolvedType, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
        }
        return ParceledListSlice.emptyList()
    }

    fun resolveService(intent: Intent?, resolveType: String?, flags: Int, userId: Int): ResolveInfo? {
        return try {
            return requireService().resolveService(intent, resolveType, flags, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    fun queryIntentServices(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<Parcelable> {
        try {
            return requireService().queryIntentServices(intent, resolvedType, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
        }
        return ParceledListSlice.emptyList()
    }

    fun queryIntentContentProviders(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<Parcelable> {
        try {
            return requireService().queryIntentContentProviders(intent, resolvedType, flags, userId)
        } catch (e: RemoteException) {
            logger.e(e)
        }
        return ParceledListSlice.emptyList()
    }

    fun resolveContentProvider(authority: String?, flags: Int, userId: Int): ProviderInfo? {
        return try {
            return requireService().resolveContentProvider(authority, flags, userId)
        } catch (e: Exception) {
            logger.e(e)
            null
        }
    }

    private fun dispatcherPackageInstalled(installResult: VmPackageResult) {
        for (packageMonitor in packageMonitorList) {
            packageMonitor.onPackageInstalled(installResult)
        }
    }

    private fun dispatcherPackageUpgraded(installResult: VmPackageResult) {
        for (packageMonitor in packageMonitorList) {
            packageMonitor.onPackageUpgraded(installResult)
        }
    }

    private fun dispatcherPackageUninstalled(installResult: VmPackageResult) {
        for (packageMonitor in packageMonitorList) {
            packageMonitor.onPackageUninstalled(installResult)
        }
    }

    private fun dispatcherPackageOperatorFail(installResult: VmPackageResult) {
        for (packageMonitor in packageMonitorList) {
            packageMonitor.onPackageOperatorFail(installResult)
        }
    }

    @Synchronized
    private fun requireService(): IVmPackageManagerService {
        if (service == null || !service!!.asBinder().isBinderAlive) {
            service = IVmPackageManagerService.Stub.asInterface(ServiceManager.getService(VmServiceManager.PACKAGE_MANAGER))
        }
        return service!!
    }

    interface PackageMonitor {

        fun onPackageInstalled(installResult: VmPackageResult)

        fun onPackageUpgraded(installResult: VmPackageResult) {}

        fun onPackageUninstalled(installResult: VmPackageResult) {}

        fun onPackageOperatorFail(installResult: VmPackageResult) {}
    }

}