package com.arvin.myproxyguard.tool;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Created by arvin on 2018-2-26.
 */

public class Main {
    public static void main(String[] args) throws Exception {
        /**
         * 制作只包含解密代码的dex文件 即 壳dex
         */
        File aarFile = new File("proxy-guard-core/build/outputs/aar/proxy-guard-core-debug.aar");
        File aarTemp = new File("proxy-guard-tool/temp");

        //解压文件 得到壳dex
        Zip.unZip(aarFile,aarTemp);
        File classesJar = new File(aarTemp,"classes.jar");
        File classesDex = new File(aarTemp,"classes.dex");
        //将classes.jar打成dex包
        //dx --dex --output out.dex in.jar
        Process process = Runtime.getRuntime().exec(
                "cmd /c "+
                        "dx --dex --output " + classesDex.getAbsolutePath()
                + " " + classesJar.getAbsolutePath()
        );
        process.waitFor();
        
        if (process.exitValue()!=0){
            throw new RuntimeException("dex error");
        }

        /**
         * 加密apk中所有dex文件
         */
        File apkFile = new File("app/build/outputs/apk/debug/app-debug.apk");
        File apkTemp = new File("app/build/outputs/apk/debug/temp");
        Zip.unZip(apkFile,apkTemp);

        File[] dexFiles = apkTemp.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".dex");
            }
        });
        AES.init(AES.DEFAULT_PWD);
        for (File dex : dexFiles) {
            //读取文件数据
            byte[] bytes = getBytes(dex);
            //加密
            byte[] encrypt = AES.encrypt(bytes);
            //写到指定目录
            FileOutputStream fos = new FileOutputStream(new File(apkTemp, "secret-"
                    + dex.getName()));
            fos.write(encrypt);
            fos.flush();
            fos.close();
            System.out.println(""+dex.exists()+"   " + String.valueOf(dex.delete()));
        }

        /**
         * 3、把classes.dex 放入 apk解压目录 在压缩成apk
         */
        classesDex.renameTo(new File(apkTemp,"classes.dex"));
        File unSignedApk = new File("app/build/outputs/apk/debug/app-debug-unsigned.apk");
        Zip.zip(apkTemp,unSignedApk);

        /**
         * 4.对齐与签名
         */
        File alignedApk = new File("app/build/outputs/apk/debug/app-debug-unsigned-aligned.apk");
        Process zipalignProcess = Runtime.getRuntime().exec("cmd /c " +
                "zipalign -f 4 " +
                unSignedApk.getAbsolutePath()+ " " +
                alignedApk.getAbsolutePath() );
        zipalignProcess.waitFor();
        if (zipalignProcess.exitValue() != 0) {
            throw new RuntimeException("zipalignProcess error");
        }

        //签名
        //apksigner sign  --ks jks文件地址 --ks-key-alias 别名 --ks-pass pass:jsk密码 --key-pass
        // pass:别名密码 --out  out.apk in.apk
        File signedApk = new File("app/build/outputs/apk/debug/app-debug-signed-aligned.apk");
        File jksFile = new File("proxy-guard-tool/xiaoyi.jks");
        Runtime.getRuntime().exec("cmd /c " +
                "apksigner sign --ks " + jksFile.getAbsolutePath()+" " +
                "--ks-key-alias xiaoyi " +
                "--ks-pass pass:123456 " +
                "--key-pass pass:123456 " +
                "--out " + signedApk.getAbsolutePath() + " " +
                unSignedApk.getAbsolutePath());
    }

    private static byte[] getBytes(File file) throws Exception {
        RandomAccessFile random = new RandomAccessFile(file,"r");
        byte[] bytes = new byte[(int) random.length()];
        random.readFully(bytes);
        random.close();
        return bytes;
    }

}
