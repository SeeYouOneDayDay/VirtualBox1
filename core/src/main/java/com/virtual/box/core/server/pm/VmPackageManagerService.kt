package com.virtual.box.core.server.pm

import android.content.ComponentName
import android.content.Intent
import android.content.pm.*
import android.os.Debug
import android.os.Parcelable
import android.os.Process
import com.virtual.box.base.ext.isNotNullOrEmpty
import com.virtual.box.base.util.AppExecutors
import com.virtual.box.base.util.log.L
import com.virtual.box.base.util.log.Logger
import com.virtual.box.core.VirtualBox
import com.virtual.box.core.compat.ComponentFixCompat
import com.virtual.box.core.helper.PackageHelper
import com.virtual.box.core.manager.VmPackageInstallManager
import com.virtual.box.core.manager.VmProcessManager
import com.virtual.box.core.server.pm.data.VmPackageDataSource
import com.virtual.box.core.server.pm.data.VmPackageInfoDataSource
import com.virtual.box.core.server.pm.data.VmPackageResolverDataSource
import com.virtual.box.core.server.pm.entity.*
import com.virtual.box.core.server.user.VmUserManagerService
import java.io.File

/**
 *
 * @author zhangzhipeng
 * @date   2022/4/26
 **/
internal object VmPackageManagerService : IVmPackageManagerService.Stub() {
    private val logger = Logger.getLogger(L.SERVER_TAG, "VmPackageManagerService")

    /**
     * 跨进程监听包监听
     */
    private val packageObservers: MutableList<IVmPackageObserver> = ArrayList(10)

    /**
     * 安装线程锁
     */
    private val mInstallLock = Any()

    private val vmPackageRepo: VmPackageRepo = VmPackageRepo()

    override fun registerPackageObserver(observer: IVmPackageObserver?) {
        if (observer == null || packageObservers.contains(observer) || observer.asBinder()?.isBinderAlive == false) {
            return
        }
        packageObservers.add(observer)
    }

    override fun unregisterPackageObserver(observer: IVmPackageObserver?) {
        if (observer == null || !packageObservers.contains(observer) || observer.asBinder()?.isBinderAlive == false) {
            return
        }
        packageObservers.remove(observer)
    }

    override fun installPackageAsUser(installOptions: VmPackageInstallOption?, userId: Int): VmPackageResult {
        if (installOptions == null) {
            return VmPackageResult.installFail( "install fial installOptions == null")
        }
        logger.method("【IPC】开始安装应用 >> install = %s, userId = %s", installOptions, userId)
        val tid = Process.myTid()
        val threadId = Thread.currentThread().id
        logger.method("IPC >> 调用服务进程进行包安装", "tid = %s, threadId = %s, option = %s, userId = %s", tid, threadId, installOptions, userId)
        return installPackageAsUserLocked(installOptions, userId)
    }

    override fun installPackageAsUserAsync(installOptions: VmPackageInstallOption?, userId: Int): Int {
        if (installOptions == null) {
            logger.e("【IPC】异步安装应用失败，installOptions == null")
            return -1
        }
        logger.i("【IPC】开始异步安装应用 >> install = %s, userId = %s", installOptions, userId)
        AppExecutors.get().doBackground{
            installPackageAsUserLocked(installOptions, userId)
        }
        return 1
    }

    private fun installPackageAsUserLocked(option: VmPackageInstallOption, userId: Int): VmPackageResult {
        if (!option.checkOriginFlag()) {
            return VmPackageResult.installFail("安装失败，安装flag错误，flag = ${option.originFlags}")
        }
        return AppExecutors.get().executeMultiThreadWithLockAsResult res@ {
            val start = System.currentTimeMillis()
            val filePath = if (option.isOriginFlag(VmPackageInstallOption.FLAG_STORAGE)) {
                val file = File(option.filePath)
                if (!file.exists()) {
                    return@res VmPackageResult.installFail("安装失败，文件路径不存在：${option.filePath}")
                }
                option.filePath
            } else {
                if (!option.packageName.isNotNullOrEmpty()) {
                    return@res VmPackageResult.installFail("安装失败，packageName == null")
                }
                var packageInfo: PackageInfo? = null
                try {
                    packageInfo = VirtualBox.get().hostContext.packageManager.getPackageInfo(option.packageName, 0);
                } catch (e: Exception) {
                    return@res VmPackageResult.installFail("安装失败，未找到对应的package = ${option.packageName}")
                }
                packageInfo.applicationInfo.publicSourceDir
            }
            val installResult = VmPackageResult()
            // TODO 后续添加检查是否安装过此应用了
            logger.method("【IPC】安装应用（应用包），文件路径 path = %s", filePath)
            // 解析apk文件包
            val aPackage = PackageHelper.parserApk(filePath)
            if (aPackage == null) {
                installResult.msg = "解析apk文件：${filePath}失败"
                return@res installResult
            }
            val packageName = aPackage.packageName
            val versionCode = aPackage.mVersionCode
            val hostPackageInfo = VirtualBox.get().hostPm.getPackageInfo(VirtualBox.get().hostPkg, 0)
            val vmPackageInfo = PackageHelper.convertPackageInfo(hostPackageInfo, aPackage)
            // 检查是否安装或更新
            if (vmPackageRepo.checkNeedInstalledOrUpdated(packageName, versionCode.toLong())){
                VmPackageInstallManager.installBaseVmPackage(vmPackageInfo, filePath)
            }else{
                PackageHelper.fixInstallApplicationInfo(vmPackageInfo.applicationInfo)
            }
            // 创建用户
            VmUserManagerService.checkOrCreateUser(userId)
            // 停止同包名下的应用进程
            VmProcessManager.killProcess(vmPackageInfo.packageName, userId)
            // 安装用户数据
            VmPackageInstallManager.installVmPackageAsUserData(vmPackageInfo, userId)
            // 安装包配置
            val vmPackageSetting = VmPackageConfigInfo(vmPackageInfo, option)
            // 保存安装信息
            vmPackageRepo.addInstallPackageInfoWithLock(aPackage, vmPackageSetting, vmPackageInfo)
            installResult.packageName = vmPackageInfo.packageName
            installResult.success = true
            logger.i("应用包安装成功 end = ${System.currentTimeMillis() - start}")
            return@res installResult
        }!!
    }

    override fun uninstallPackageAsUser(packageName: String?, userId: Int): VmPackageResult {
        synchronized(mInstallLock){
            if (!isInstalled(packageName, userId)){
                logger.e("【IPC】用户卸载安装包失败，校验不通过")
                return VmPackageResult.installFail("用户卸载安装包失败，校验不通过")
            }
            logger.i("【IPC】正在卸载用户安装包 packageName = %s", packageName)
            // 关闭应用进程
            VmProcessManager.killProcess(packageName!!, userId)
            vmPackageRepo.remoteInstallPackageUserDataWithLock(packageName, userId)
        }
        return VmPackageResult.installSuccess(packageName!!)
    }

    override fun isInstalled(packageName: String?, userId: Int): Boolean {
        if (packageName.isNullOrEmpty()){
            logger.e("【IPC】检查是否安装，packageName == null")
            return false
        }

        if (!VmUserManagerService.exists(userId)){
            logger.e("【IPC】检查是否安装，用户不存在 userId = %s", userId)
            return false
        }

        if (!vmPackageRepo.checkPackageInstalled(packageName)){
            logger.e("【IPC】检查是否安装，未找到安装包配置")
            return false
        }

        if (!VmPackageInstallManager.checkPackageInstalled(packageName)){
            logger.e("【IPC】检查是否安装，未找到安装包文件：packageInfo.conf")
            return false
        }

        return true
    }

    override fun getVmInstalledPackageInfos(flag: Int): MutableList<VmInstalledPackageInfo> {
        logger.i("【IPC】查找安装的应用")
        val installPackageInfoList = vmPackageRepo.getPackageInfoList(flag)
        return installPackageInfoList.map { VmInstalledPackageInfo(0, it.packageName, it) }.toMutableList()
    }

    override fun getVmInstalledPackageInfo(packageName: String, flags: Int): VmInstalledPackageInfo?{
        logger.i("【IPC】查找指定包名的安装应用（非系统）")
        //return vmPackageRepo.getVmPackageInfo(packageName, flags)
        return null
    }

    override fun getPackageInfo(packageName: String, flags: Int, userId: Int): PackageInfo? {
        return vmPackageRepo.getVmPackageInfo(packageName,flags)
    }

    override fun getApplicationInfo(packageName: String, flags: Int, userId: Int): ApplicationInfo? {
        return vmPackageRepo.getApplicationInfo(packageName, flags)
    }

    override fun getActivityInfo(componentName: ComponentName, flags: Int, userId: Int): ActivityInfo? {
        val activityInfo = vmPackageRepo.getActivityInfo(componentName, flags) ?: return null
        val applicationInfo = activityInfo.applicationInfo
        ComponentFixCompat.fixApplicationAbi(applicationInfo)
        ComponentFixCompat.fixApplicationInfo(applicationInfo, userId)
        return activityInfo
    }

    override fun getReceiverInfo(componentName: ComponentName?, flags: Int, userId: Int): ActivityInfo? {
        componentName?: return null
        return vmPackageRepo.getReceiverInfo(componentName, flags, userId)
    }

    override fun getServiceInfo(componentName: ComponentName?, flags: Int, userId: Int): ServiceInfo? {
        componentName?: return null
        return vmPackageRepo.getServiceInfo(componentName, flags, userId)
    }

    override fun getProviderInfo(componentName: ComponentName?, flags: Int, userId: Int): ProviderInfo? {
        componentName ?: return null
        return vmPackageRepo.getProviderInfo(componentName, flags, userId)
    }
    /**
     * 解析所有的虚拟程序的Activity，查找指定匹配组件
     */
    override fun resolveActivity(intent: Intent, flags: Int, resolvedType: String, userId: Int): ResolveInfo? {
//        if (VmUserManagerService.exists(userId)){
//            logger.e("resolveActivity >> 用户 %s 不存在", userId)
//            return null
//        }
        val resolveActivities = vmPackageRepo.queryIntentActivities(intent, resolvedType, flags, userId)
        return PackageHelper.chooseBestActivity(intent,resolvedType,flags, resolveActivities )
    }

    override fun resolveIntent(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ResolveInfo? {
        intent ?: return null
        val queryIntentActivities = vmPackageRepo.queryIntentActivities(intent, resolvedType, flags, userId)
        return PackageHelper.chooseBestActivity(intent, resolvedType, flags, queryIntentActivities)
    }

    override fun findPersistentPreferredActivity(intent: Intent?, userId: Int): ResolveInfo {
        TODO("Not yet implemented")
    }

    override fun queryIntentActivities(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<*> {
        intent?: return ParceledListSlice.emptyList<Parcelable>()
        return ParceledListSlice(vmPackageRepo.queryIntentActivities(intent, resolvedType, flags, userId))
    }

    override fun queryIntentActivityOptions(
        componentName: ComponentName?,
        specifics: Array<out Intent>?,
        specificTypes: Array<out String>?,
        intent: Intent?,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): ParceledListSlice<*> {
        val resultList = mutableListOf<Parcelable>()
        val queryIntentActivityOptions = VirtualBox.get().hostPm
            .queryIntentActivityOptions(componentName, specifics, intent ?: Intent(), flags)
        resultList.addAll(queryIntentActivityOptions)

        val vmQueryList = vmPackageRepo.queryIntentActivityOptions(componentName, specifics, specificTypes, intent, resolvedType, flags, userId)
        resultList.addAll(vmQueryList)
        return ParceledListSlice(resultList)
    }

    override fun queryIntentReceivers(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<*> {
        return ParceledListSlice.emptyList<Parcelable>()
    }

    override fun resolveService(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ResolveInfo? {
        intent ?: return null
        val queryIntentServices = vmPackageRepo.queryIntentServices(intent, resolvedType, flags, userId)
        if (queryIntentServices.isEmpty()){
            return null
        }
        return queryIntentServices.first()
    }

    override fun queryIntentServices(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<*> {
        intent?: return ParceledListSlice.emptyList<Parcelable>()
        return ParceledListSlice(vmPackageRepo.queryIntentServices(intent, resolvedType, flags, userId))
    }

    override fun queryIntentContentProviders(intent: Intent?, resolvedType: String?, flags: Int, userId: Int): ParceledListSlice<*> {
        intent?: return ParceledListSlice.emptyList<Parcelable>()
        return ParceledListSlice(vmPackageRepo.queryIntentProviders(intent, resolvedType, flags, userId))
    }

    override fun resolveContentProvider(name: String?, flags: Int, userId: Int): ProviderInfo? {
        name?: return null
        return vmPackageRepo.resolveContentProvider(name, flags, userId)
    }

    override fun getInstrumentationInfo(className: ComponentName?, flags: Int): InstrumentationInfo? {
        className ?: return null
        return vmPackageRepo.getInstrumentationInfo(className, flags)
    }

    override fun queryInstrumentation(targetPackage: String?, flags: Int): ParceledListSlice<*> {
        targetPackage ?: return ParceledListSlice.emptyList<Parcelable>()
        return ParceledListSlice(vmPackageRepo.queryInstrumentation(targetPackage, flags))
    }

    private fun dispatcherPackageInstall(installResult: VmPackageResult){
        checkPackageObserver()
        for (packageObserver in packageObservers) {
            packageObserver.onPackageResult(installResult)
        }
    }

    private fun checkPackageObserver(){
        val iterator = packageObservers.iterator()
        while (iterator.hasNext()){
            val iter = iterator.next()
            if (!iter.asBinder().isBinderAlive){
                iterator.remove()
            }
        }
    }

    fun resolveApplicationInfo(intent: Intent, flags: Int, userId: Int): ApplicationInfo?{
        logger.i("解析ApplicationInfo intent = %s, userId = %s", intent, userId)
        if (!VmUserManagerService.exists(userId)){
            logger.e("解析ApplicationInfo 失败，用户 %s 不存在", userId)
            return null
        }
        val packageName = intent.getPackage() ?: intent.component?.packageName ?: return null
        return vmPackageRepo.getApplicationInfo(packageName, flags)
    }

    fun resolveActivityInfo(intent: Intent?, flags: Int, resolvedType: String, userId: Int): ActivityInfo?{
        logger.i("解析ActivityInfo intent = %s, userId = %s", intent, userId)
        intent?: return null
//        if (!VmUserManagerService.exists(userId)){
//            logger.e("解析ActivityInfo 失败，用户 %s 不存在", userId)
//            return null
//        }
        val packageName = intent.getPackage() ?: intent.component?.packageName
        val componentName = intent.component
        if (packageName.isNullOrEmpty()){
            logger.e("解析ActivityInfo 失败，包名不能为空")
            return null
        }

        if (componentName != null) {
            return vmPackageRepo.getActivityInfo(componentName, flags)
        }
        val queryIntentActivities = vmPackageRepo.queryIntentActivities(intent, resolvedType, flags, userId)
        val chooseBestActivity = PackageHelper.chooseBestActivity(intent, resolvedType, flags, queryIntentActivities)
        return chooseBestActivity?.activityInfo
    }
}