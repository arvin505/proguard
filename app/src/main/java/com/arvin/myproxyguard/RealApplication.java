package com.arvin.myproxyguard;

import android.app.Application;
import android.util.Log;

/**
 * Created by arvin on 2018-2-26.
 */

public class RealApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("Application","这才是真正的application");
    }
}
