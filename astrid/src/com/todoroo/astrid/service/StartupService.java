package com.todoroo.astrid.service;

import java.util.List;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.timsu.astrid.R;
import com.timsu.astrid.appwidget.AstridAppWidgetProvider.UpdateService;
import com.timsu.astrid.sync.SynchronizationService;
import com.timsu.astrid.utilities.BackupService;
import com.timsu.astrid.utilities.Notifications;
import com.timsu.astrid.utilities.Preferences;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.service.ExceptionService.TodorooUncaughtExceptionHandler;
import com.todoroo.astrid.utility.Constants;

/**
 * Service which handles jobs that need to be run when user's phone or Astrid
 * starts up.
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class StartupService extends BroadcastReceiver {

    static {
        AstridDependencyInjector.initialize();
    }

    // --- system startup

    @Override
    /** Called when the system is started up */
    public void onReceive(Context context, Intent intent) {
        ContextManager.setContext(context);
        Notifications.scheduleAllAlarms(context);
    }

    // --- application startup

    @Autowired
    ExceptionService exceptionService;

    @Autowired
    UpgradeService upgradeService;

    /**
     * bit to prevent multiple initializations
     */
    private static boolean hasStartedUp = false;

    /** Called when this application is started up */
    public synchronized void onStartupApplication(final Context context) {
        if(hasStartedUp)
            return;

        // set uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler(new TodorooUncaughtExceptionHandler());

        // sets up context manager
        ContextManager.setContext(context);

        // read current version
        int latestSetVersion = Preferences.getCurrentVersion(context);
        int version = 0;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(Constants.PACKAGE, 0);
            version = pi.versionCode;
        } catch (Exception e) {
            exceptionService.reportError("astrid-startup-package-read", e); //$NON-NLS-1$
        }

        // invoke upgrade service
        boolean justUpgraded = latestSetVersion != version;
        if(justUpgraded) {
            upgradeService.performUpgrade(latestSetVersion);
        	Preferences.setCurrentVersion(context, version);
        }

        // perform startup activities in a background thread
        new Thread(new Runnable() {
            public void run() {
                // schedule alarms
                Notifications.scheduleAllAlarms(context);

                // start widget updating alarm
                AlarmManager am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent(context, UpdateService.class);
                PendingIntent pendingIntent = PendingIntent.getService(context,
                        0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
                am.setInexactRepeating(AlarmManager.RTC, 0,
                        Constants.WIDGET_UPDATE_INTERVAL, pendingIntent);

                // start synchronization service
                if(Constants.SYNCHRONIZE)
                    SynchronizationService.scheduleService(context);

                // start backup service
                BackupService.scheduleService(context);
            }
        }).start();

        Preferences.setPreferenceDefaults(context);

        // check for task killers
        if(!Constants.OEM)
            showTaskKillerHelp(context);

        hasStartedUp = true;
    }

    private static void showTaskKillerHelp(final Context context) {
        if(!Preferences.shouldShowTaskKillerHelp(context))
            return;

        // search for task killers. if they exist, show the help!
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> apps = pm
                .getInstalledPackages(PackageManager.GET_PERMISSIONS);
        outer: for (PackageInfo app : apps) {
            if(app == null || app.requestedPermissions == null)
                continue;
            if(app.packageName.startsWith("com.android")) //$NON-NLS-1$
                continue;
            for (String permission : app.requestedPermissions) {
                if (Manifest.permission.RESTART_PACKAGES.equals(permission)) {
                    CharSequence appName = app.applicationInfo.loadLabel(pm);
                    OnClickListener listener = new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface arg0,
                                int arg1) {
                            Preferences.disableTaskKillerHelp(context);
                        }
                    };

                    new AlertDialog.Builder(context)
                    .setTitle(R.string.information_title)
                    .setMessage(context.getString(R.string.task_killer_help,
                            appName))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(R.string.task_killer_help_ok, listener)
                    .show();

                    break outer;
                }
            }
        }
    }
}
