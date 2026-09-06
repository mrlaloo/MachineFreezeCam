package com.mrlaloo.tapomonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(launch);
            } catch (Exception ignored) {
                // Some Android versions restrict background activity launches.
                // The app will still launch normally from the TV launcher.
            }
        }
    }
}
