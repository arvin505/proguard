package com.myproxyguard.core;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by arvin on 2018-2-26.
 */

public class ProxyApplication extends Application{
    private String appName;
    private float appVersion;
    private Application delegate;
    private boolean isReal;
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        getMetaData();
        //获得当前的apk文件
        File apkFile  = new File(getApplicationInfo().sourceDir);
        //apk zip解压到appdir这个目录
        File versionDir = getDir(appName+"_"+appVersion,MODE_PRIVATE);
        File appDir = new File(versionDir,"app");
        //提取apk中需要解密的所有dex放入此目录
        File dexDir = new File(appDir,"dexDir");
        //需要我们加载的dex
        List<File> dexFiles =new ArrayList<>();

        if (!dexDir.exists()||dexDir.list().length == 0){ // 未解密
            Zip.unZip(apkFile,appDir);
            File[] files = appDir.listFiles();
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".dex")&&!TextUtils.equals(name,"classes.dex")){
                    try {
                        byte[] bytes = Utils.getBytes(file);
                        Utils.decrypt(bytes,file.getAbsolutePath());
                        dexFiles.add(file);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }else { //已经解密过
            for (File file : dexDir.listFiles()) {
                dexFiles.add(file);
            }

        }

        try {
            loadDex(dexFiles,versionDir);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 加载文件集合
     * @param dexFiles
     * @param optimizedDirectory  优化后的文件存放的路径
     */
    private void loadDex(List<File> dexFiles, File optimizedDirectory) throws NoSuchFieldException,
            IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        /**
         * 1.获得 系统 classloader中的dexElements数组
         */
        //获取classloader中的pathList  DexPathList
        Field pathListField = Utils.findField(getClassLoader(),"pathList");
        Object pathList = pathListField.get(getClassLoader());
        //获取pathList中的dexElements  Element[] dexElements;
        Field dexElementsField = Utils.findField(pathList,"dexElements");
        Object[] dexElements = (Object[]) dexElementsField.get(pathList);

        /**
         * 2.创建新的element数组 --解密后加载dex
         */
        Method makeDexMethod;
        makeDexMethod =  Utils.findMethod(pathList,"makeDexElements",ArrayList.class,
                File.class,ArrayList.class);
        /**
         * 3.合并两个数组
         */
        ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
        Object[] addElements = (Object[]) makeDexMethod.invoke(pathList,dexFiles,optimizedDirectory,suppressedExceptions);
        Object[] newElements = (Object[]) Array.newInstance(dexElements.getClass().getComponentType(),
                dexElements.length + addElements.length);
        System.arraycopy(dexElements,0,newElements,0,dexElements.length);
        System.arraycopy(addElements,0,newElements,dexElements.length,addElements.length);
        /**
         * 4.替换classloader中的 element数组
         */
        dexElementsField.set(pathList,newElements);
    }

    private void getMetaData(){
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(getPackageName(),
                    PackageManager.GET_META_DATA);
            Bundle metaData = appInfo.metaData;
            if (metaData != null) {
                if (metaData.containsKey("app_name")){
                    appName = metaData.getString("app_name");
                }
                if (metaData.containsKey("app_version")){
                    appVersion = metaData.getFloat("app_version");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (appName==null){
            return;
        }

        try {
            bindRealApplication();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void bindRealApplication() throws Exception {
        if (isReal) return;
        Class<?> delegateClass = Class.forName(appName);
        //反射创建出真实的application
        delegate = (Application) delegateClass.newInstance();
        Method attachMethod = Utils.findMethod(delegate,"attach",Context.class);
        Context baseContext = getBaseContext(); // 这个就是attachBaseContext中传入的context
        attachMethod.invoke(delegate,baseContext);
        /**
         * 替换ContextImpl -> mOuterContext
         */
        Class<?> contextImplCLass = Class.forName("android.app.ContextImpl");
        Field mOuterContextField = contextImplCLass.getDeclaredField("mOuterContext");
        mOuterContextField.setAccessible(true);
        mOuterContextField.set(baseContext,delegate);

        /**
         * 替换ActivityThread ->mAllApplications 与 mInitialApplication
         */
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
        mInitialApplicationField.setAccessible(true);

        //ActivityThread实例可以从ContextImpl 的 mMainThread获得
        Field mMainThreadField = Utils.findField(baseContext, "mMainThread");
        Object mMainThread = mMainThreadField.get(baseContext);
        //替换ActivityThread  mInitialApplication
        mInitialApplicationField.set(mMainThread,delegate);

        //替换ActivityThread mAllApplications
        Field mAllApplicationsField = activityThreadClass.getDeclaredField("mAllApplications");
        mAllApplicationsField.setAccessible(true);

        ArrayList<Application> mAllApplications = (ArrayList<Application>) mAllApplicationsField.get(mMainThread);
        mAllApplications.remove(this);
        mAllApplications.add(delegate);

        /**
         * 替换LoadedApk -> mApplication
         */
        Field mPackageInfoField = Utils.findField(baseContext,"mPackageInfo");
        Object mPackageInfo = mPackageInfoField.get(baseContext);

        Class<?> loadedApkClass = Class.forName("android.app.LoadedApk");
        Field mApplicationFailed = loadedApkClass.getDeclaredField("mApplication");
        mApplicationFailed.setAccessible(true);
        mApplicationFailed.set(mPackageInfo,delegate);

        /**
         * 修改applicationInfo classname
         * Loadedapk
         */
        Field mApplicationInfoField = loadedApkClass.getDeclaredField("mApplicationInfo");
        mApplicationInfoField.setAccessible(true);
        ApplicationInfo mApplicationInfo = (ApplicationInfo) mApplicationInfoField.get(mPackageInfo);
        mApplicationInfo.className = appName;

        delegate.onCreate();
    }

    @Override
    public String getPackageName() {
        if (!TextUtils.isEmpty(appName)){
            return "";
        }
        return super.getPackageName();
    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        if (!TextUtils.isEmpty(appName)){
            return super.createPackageContext(packageName, flags);
        }
        try {
            bindRealApplication();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return delegate;
    }
}
